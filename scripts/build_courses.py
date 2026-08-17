#!/usr/bin/env python3
"""러닝코스 데이터 빌드 — 여러 소스를 하나의 `courses.json` 으로 정규화한다.

    python scripts/build_courses.py --sources durunubi
    python scripts/build_courses.py --sources durunubi,gpx:forest --regeocode
    python scripts/build_courses.py --sources durunubi --out data/courses.json

소스별 어댑터는 `scripts/courses/sources/` 에 있다. 새 소스를 붙이려면 파일 하나를
추가하고 `@register` 를 붙이면 되며, 이 파일은 고치지 않는다.

**설계 요점**

1. 거리·상승고도는 GPX **원본 해상도**로 재고, 그 값을 축약 포인트에 실어 나른다.
   축약본으로 계산하면 실측에서 41% 과소평가됐다(해파랑길 1코스 732m → 434m).
2. 지역(sido/sigun)은 `--regeocode` 로 **좌표 기준 재계산**한다. 두루누비 메타에
   서울 강동구로 적힌 코스의 실제 좌표가 철원(약 70km 밖)인 사례가 있다.
3. 난이도는 원본 `level` 이 있으면 쓰고, 없으면 상승고도(m/km)로 매긴다. 다만
   원본 level 은 코스 **전체**의 등급이라 조각 추천에 그대로 쓰면 안 된다 —
   조각 난이도는 앱/서버가 `cumGainM` 차이로 그때그때 계산한다.
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import sys
import time
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))

from courses.model import REGIONS, Course, normalize  # noqa: E402
from courses.sources import REGISTRY  # noqa: E402

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT = ROOT / "data" / "courses.json"

log = logging.getLogger("build_courses")

KAKAO_REGION_URL = "https://dapi.kakao.com/v2/local/geo/coord2regioncode.json"
SIDO_SHORT = {
    "서울특별시": "서울", "부산광역시": "부산", "대구광역시": "대구",
    "인천광역시": "인천", "광주광역시": "광주", "대전광역시": "대전",
    "울산광역시": "울산", "세종특별자치시": "세종", "경기도": "경기",
    "강원특별자치도": "강원", "강원도": "강원", "충청북도": "충북",
    "충청남도": "충남", "전북특별자치도": "전북", "전라북도": "전북",
    "전라남도": "전남", "경상북도": "경북", "경상남도": "경남",
    "제주특별자치도": "제주",
}

#: 카카오가 통합 행정구역명을 준다. 우리 데이터(`races.json` · SPEC 캘린더 필터)는
#: 아직 17개 시도 체계라 되돌려 맞춘다. 전남에는 자치구가 없고 광주에만 있으므로
#: 2depth 가 "…구" 면 광주, "…시/군" 이면 전남이다.
MERGED_SIDO = {"전남광주통합특별시": ("광주", "전남")}


def to_region(depth1: str, depth2: str) -> str:
    """카카오 시도명 → 우리 17개 시도 표기."""
    if depth1 in MERGED_SIDO:
        gu, do = MERGED_SIDO[depth1]
        return gu if depth2.endswith("구") else do
    return SIDO_SHORT.get(depth1, depth1)


def build_source(spec: str):
    """`durunubi` 또는 `gpx:forest` 형태의 인자를 어댑터 인스턴스로 만든다."""
    key, _, arg = spec.partition(":")
    cls = REGISTRY.get(key)
    if cls is None:
        raise SystemExit(f"모르는 소스 '{key}'. 가능한 값: {', '.join(sorted(REGISTRY))}")
    return cls(arg) if arg else cls()


def regeocode(courses: list[Course], rest_key: str, delay: float = 0.1) -> int:
    """코스 중간 지점의 좌표로 시도·시군을 다시 매긴다. 고친 건수를 돌려준다."""
    session = requests.Session()
    session.headers["Authorization"] = f"KakaoAK {rest_key}"
    fixed = 0
    for c in courses:
        mid = c.points[len(c.points) // 2]
        try:
            r = session.get(KAKAO_REGION_URL, params={"x": mid[1], "y": mid[0]}, timeout=15)
            r.raise_for_status()
            docs = [d for d in r.json().get("documents", []) if d.get("region_type") == "B"]
            if not docs:
                continue
            d = docs[0]
            depth2 = d.get("region_2depth_name", "")
            sido = to_region(d.get("region_1depth_name", ""), depth2)
            sigun = f"{sido} {depth2}".strip()
        except requests.RequestException as e:
            log.warning("%s 지역 재계산 실패: %s", c.id, e)
            continue
        if sido and (c.sido != sido or c.sigun != sigun):
            log.info("지역 정정 %s: %s → %s", c.name, c.sigun or c.sido or "(없음)", sigun)
            c.sido, c.sigun = sido, sigun
            fixed += 1
        time.sleep(delay)
    return fixed


def report(courses: list[Course], raw_declared: dict[str, float | None]) -> None:
    """수집 결과를 요약한다. 조용히 틀리는 것보다 시끄럽게 알리는 편이 낫다."""
    print(f"\n총 {len(courses)}코스")

    by_source: dict[str, int] = {}
    by_sido: dict[str, int] = {}
    for c in courses:
        by_source[c.source] = by_source.get(c.source, 0) + 1
        by_sido[c.sido or "(미상)"] = by_sido.get(c.sido or "(미상)", 0) + 1
    print("  소스별:", ", ".join(f"{k} {v}" for k, v in sorted(by_source.items())))

    by_ds: dict[str, int] = {}
    for c in courses:
        if c.data_source:
            by_ds[c.data_source] = by_ds.get(c.data_source, 0) + 1
    if by_ds:
        print("  수집구분:", ", ".join(f"{k} {v}" for k, v in sorted(by_ds.items())))
        # API 만 보고 만들면 119개를 잃는다(domain-logic-audit §C4). 눈에 보이게 둔다.
        if by_ds.get("GPX_ONLY", 0) == 0:
            print("  ⚠ GPX_ONLY 가 0건이다 — 시드 파일을 못 읽었을 수 있다(§C4 확인)")
    print("  지역별:", ", ".join(f"{k} {v}" for k, v in sorted(by_sido.items(), key=lambda x: -x[1])))

    unknown = sorted({c.sido for c in courses if c.sido and c.sido not in REGIONS})
    if unknown:
        print(f"  ⚠ 표준 시도명이 아닌 값: {unknown}")

    no_ele = [c for c in courses if c.gain_m == 0]
    if no_ele:
        print(f"  ⚠ 고도 정보 없음 {len(no_ele)}건 — 난이도·고도 스트립을 쓸 수 없다")

    gaps = [
        (c.name, d, c.dist_km)
        for c in courses
        if (d := raw_declared.get(c.id)) and abs(d - c.dist_km) > max(1.0, d * 0.2)
    ]
    if gaps:
        print(f"  ⚠ 원본 표기 거리와 20% 이상 차이 {len(gaps)}건 (GPX 결손 의심)")
        for name, declared, measured in gaps[:5]:
            print(f"      {name}: 표기 {declared}km / 실측 {measured}km")

    total_pts = sum(len(c.points) for c in courses)
    print(f"  포인트 {total_pts:,}개 (코스당 평균 {total_pts // max(1, len(courses))})")


def main() -> int:
    ap = argparse.ArgumentParser(description="러닝코스 데이터 빌드")
    ap.add_argument("--sources", default="durunubi", help="쉼표 구분. 예: durunubi,gpx:forest")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--tolerance", type=float, default=8.0, help="축약 허용오차(m)")
    ap.add_argument("--regeocode", action="store_true", help="좌표 기준으로 시도·시군 재계산")
    ap.add_argument("--include-nonderivable", action="store_true",
                    help="구간 잘라내기가 금지된 소스도 포함한다(지역별 탭 전용)")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    logging.basicConfig(
        level=logging.INFO if args.verbose else logging.WARNING,
        format="%(levelname)s %(message)s",
    )

    courses: list[Course] = []
    declared: dict[str, float | None] = {}
    attributions: list[str] = []

    for spec in [s.strip() for s in args.sources.split(",") if s.strip()]:
        src = build_source(spec)
        if not src.derivable and not args.include_nonderivable:
            print(f"건너뜀: {spec} — 라이선스상 구간 잘라내기 불가 ({src.license})")
            print("        지역별 탭 전용으로 포함하려면 --include-nonderivable")
            continue

        print(f"수집 중: {spec} ...")
        n = 0
        for raw in src.fetch():
            try:
                c = normalize(raw, source=src.key, tolerance_m=args.tolerance)
            except ValueError as e:
                log.warning("건너뜀: %s", e)
                continue
            courses.append(c)
            declared[c.id] = raw.declared_km
            n += 1
        print(f"  → {n}코스")
        if src.attribution:
            attributions.append(src.attribution)

    if not courses:
        print("수집된 코스가 없다.")
        return 1

    if args.regeocode:
        rest_key = os.environ.get("KAKAO_REST_KEY", "")
        if not rest_key:
            print("⚠ KAKAO_REST_KEY 가 없어 지역 재계산을 건너뛴다. (scripts/.env)")
        else:
            print("지역 재계산 중 ...")
            print(f"  → {regeocode(courses, rest_key)}건 정정")

    courses.sort(key=lambda c: (c.sido, c.name))
    payload = {
        "attribution": attributions,
        "courses": [c.to_json() for c in courses],
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
    )

    report(courses, declared)
    print(f"\n저장: {args.out} ({args.out.stat().st_size / 1024:.0f} KB)")
    print(f"출처 표기: {' · '.join(attributions)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
