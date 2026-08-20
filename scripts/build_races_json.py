#!/usr/bin/env python3
"""races_sample.csv → reference-web/public/data/races.json

SPEC.md §6.2 대회 초기 데이터 계약(camelCase)으로 출력한다.
- RFC 4180 파싱 (description 멀티라인 대응)
- 종목 표준화: has_* 플래그 우선 + event_types 토큰 보강 → ['풀','하프','10K','5K'] 순서
- region: CSV region(17개 단축명) 사용, 비표준 값은 sido/venue/주소에서 보정
- 중복 병합: (정규화 이름, 날짜) 그룹 → 최근 확인일 우선, 빈 필드는 상대 레코드로 보충,
  source는 병기("마라톤GO·마라톤온라인"), checked는 최신값

사용: python scripts/build_races_json.py [--out 경로]
"""
import csv
import json
import re
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "data" / "races_sample.csv"
OUT = ROOT / "reference-web" / "public" / "data" / "races.json"

REGIONS = [
    "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종",
    "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주",
]
SIDO_TO_REGION = {
    "서울특별시": "서울", "부산광역시": "부산", "대구광역시": "대구",
    "인천광역시": "인천", "광주광역시": "광주", "대전광역시": "대전",
    "울산광역시": "울산", "세종특별자치시": "세종", "경기도": "경기",
    "강원특별자치도": "강원", "충청북도": "충북", "충청남도": "충남",
    "전북특별자치도": "전북", "전라남도": "전남", "경상북도": "경북",
    "경상남도": "경남", "제주특별자치도": "제주",
}
EVENT_ORDER = ["풀", "하프", "10K", "5K"]


def std_event_km(km):
    """거리(km) → 표준 종목. SPEC §5.4 `stdEventKm` — ≥32 풀 · ≥18 하프 · ≥9 10K · 그 외 5K."""
    if km >= 32:
        return "풀"
    if km >= 18:
        return "하프"
    if km >= 9:
        return "10K"
    return "5K"


def std_events(row):
    """SPEC §5.4 `eventsFromContest` — ① has_* 플래그 ∪ ② distances 버킷 ∪ ③ event_types 토큰.

    셋은 우선순위가 아니라 합집합이다. 앞 단계가 뒤를 가리면 has_5k=true 인 대회의
    distances=[42,21,10,5] 가 5K 하나로 줄어든다.

    ② 를 빼면 `15km` 같은 토큰이 어느 종목인지 알 수 없다. 문자열 패턴만으로는
    `15km` 의 `5k`, `110km` 의 `10k` 를 오매칭하거나(구 동작) 아무것도 못 잡는다.
    거리는 이미 `distances` 컬럼에 숫자로 있으므로 그걸 버킷에 넣는 게 정답이다.

    토큰 단계는 거리로 못 채운 것만 보강한다. **불명 토큰을 5K 로 강제하지 않는다** —
    SPEC 의 `stdEvent` 는 "그 외 5K" 지만 그건 종목 하나를 표준화할 때의 규칙이고,
    여기서는 종목 목록을 만드는 중이라 모르는 토큰을 5K 로 넣으면 없는 종목이 생긴다.
    """
    ev = set()

    # ① has_* 플래그
    for col, label in (("has_full", "풀"), ("has_half", "하프"), ("has_10k", "10K"), ("has_5k", "5K")):
        if (row.get(col) or "").strip().lower() == "true":
            ev.add(label)

    # ② 거리 버킷
    try:
        distances = json.loads(row.get("distances") or "[]")
    except json.JSONDecodeError:
        distances = []
    for d in distances:
        try:
            km = float(d)
        except (TypeError, ValueError):
            continue
        if km > 0:
            ev.add(std_event_km(km))

    # ③ 토큰 보강
    try:
        tokens = json.loads(row.get("event_types") or "[]")
    except json.JSONDecodeError:
        tokens = []
    for t in tokens:
        s = str(t).lower()
        if re.search(r"풀|full|(^|\D)42", s):
            ev.add("풀")
        elif re.search(r"하프|half|(^|\D)21", s):
            ev.add("하프")
        elif re.search(r"(?<![\d.])10\s*k", s):  # '110km'의 10k 오매칭 방지
            ev.add("10K")
        elif re.search(r"(?<![\d.])5\s*k", s):  # '15km'·'4.5km'의 5k 오매칭 방지
            ev.add("5K")
    return [e for e in EVENT_ORDER if e in ev]


def region_of(row, warnings):
    r = (row.get("region") or "").strip()
    if r in REGIONS:
        return r
    sido = (row.get("sido") or "").strip()
    if sido in SIDO_TO_REGION:
        return SIDO_TO_REGION[sido]
    text = f"{row.get('venue', '')} {row.get('road_address', '')} {sido} {r}"
    for full, short in SIDO_TO_REGION.items():
        if full[:2] in text:  # '세종', '서울' 등 앞 2글자 힌트
            warnings.append(f"region 보정: {row.get('name')} — {r!r}/{sido!r} → {short} (텍스트 힌트)")
            return short
    warnings.append(f"region 미해결: {row.get('name')} — {r!r}/{sido!r} (원값 유지)")
    return r or sido


def norm_name(s):
    """병합 그룹 키. NFC 정규화로 자모 분리형(NFD) 입력도 같은 키를 만든다."""
    return re.sub(r"[\s\W_]+", "", unicodedata.normalize("NFC", s or ""), flags=re.UNICODE).lower()


def merge_group(rows):
    """최근 last_checked 우선(동률 시 마라톤GO — 이미지 보유율 높음), 빈 필드는 상대에서 보충."""
    rows = sorted(rows, key=lambda r: ((r.get("last_checked") or ""), r.get("source") == "마라톤GO"), reverse=True)
    base = dict(rows[0])
    for other in rows[1:]:
        for k, v in other.items():
            if not (base.get(k) or "").strip() and (v or "").strip():
                base[k] = v
    sources = "·".join(sorted({r["source"] for r in rows}))
    checked = max((r.get("last_checked") or "") for r in rows)
    return base, sources, checked


def to_race(base, sources, checked, warnings):
    lat = lng = None
    try:
        lat, lng = float(base["latitude"]), float(base["longitude"])
    except (ValueError, KeyError, TypeError):
        warnings.append(f"좌표 없음: {base.get('name')} ({base.get('event_date')})")
    return {
        "id": base["race_id"],
        "name": (base.get("name") or "").strip(),
        "region": region_of(base, warnings),
        "venue": (base.get("venue") or "").strip(),
        "date": base.get("event_date") or "",
        "startTime": (base.get("start_time") or "").strip(),
        "eventTypes": std_events(base),
        "regStatus": (base.get("reg_status") or "미정").strip(),
        "regStart": base.get("reg_start") or "",
        "regEnd": base.get("reg_end") or "",
        "organizer": (base.get("organizer") or "").strip(),
        "source": sources,
        "checked": checked,
        "officialUrl": (base.get("official_url") or "").strip(),
        "detailUrl": (base.get("detail_url") or "").strip(),
        "imageUrl": (base.get("image_url") or "").strip(),
        "lat": lat,
        "lng": lng,
        "category": (base.get("category") or "").strip(),
    }


def main():
    out_path = OUT
    if "--out" in sys.argv:
        out_path = Path(sys.argv[sys.argv.index("--out") + 1])

    with open(SRC, encoding="utf-8-sig", newline="") as f:
        rows = [r for r in csv.DictReader(f)]

    warnings = []
    skipped = [r for r in rows if not (r.get("name") or "").strip() or not (r.get("event_date") or "").strip()]
    rows = [r for r in rows if r not in skipped]
    for r in skipped:
        warnings.append(f"필수값 누락 스킵: {r.get('race_id')}")

    groups = defaultdict(list)
    for r in rows:
        groups[(norm_name(r["name"]), r["event_date"])].append(r)

    races = []
    for key, grp in groups.items():
        base, sources, checked = merge_group(grp)
        races.append(to_race(base, sources, checked, warnings))
    races.sort(key=lambda x: (x["date"], x["name"]))

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(races, f, ensure_ascii=False, separators=(",", ":"))

    # 리포트
    print(f"입력 {len(rows) + len(skipped)}행 → 고유 대회 {len(races)}개 (중복 병합 {len(rows) - len(groups)}건, 스킵 {len(skipped)}건)")
    print("지역:", dict(Counter(r["region"] for r in races)))
    print("접수상태:", dict(Counter(r["regStatus"] for r in races)))
    print("종목없음:", sum(1 for r in races if not r["eventTypes"]), "| 이미지:", sum(1 for r in races if r["imageUrl"]),
          "| 병기출처:", sum(1 for r in races if "·" in r["source"]))
    print("날짜범위:", races[0]["date"], "~", races[-1]["date"])
    print(f"출력: {out_path} ({out_path.stat().st_size // 1024}KB)")
    if warnings:
        print(f"\n경고 {len(warnings)}건:")
        for w in warnings[:15]:
            print(" -", w)


if __name__ == "__main__":
    main()
