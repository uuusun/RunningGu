"""두루누비(한국관광공사) 걷기길 어댑터. — SPEC §5.8 · §8.4

`courseList` 로 메타를 받고 각 코스의 GPX 에서 좌표+고도를 얻는다. 기존
`durunubi_courses.json` 은 GPX 의 `<ele>` 를 버렸는데, 그 고도가 있어야 조각 단위
난이도와 고도 스트립(SPEC §4.11-5)을 만들 수 있다. 그래서 여기서 다시 살린다.

**⚠ API 만 보고 만들면 코스가 반토막 난다** (`docs/domain-logic-audit.md` §C4)

두루누비 API 가 지금 주는 코스는 144개뿐인데 우리 파일에는 261개가 있다.
겹침 142 · API 에만 2 · **우리에만 119**. API 응답으로 덮어쓰면 119개를 잃는다.

그래서 수집 기준은 **API 가 아니라 시드 파일 261개**다.

    시드 261개 id
      ├─ API 에도 있음  → API_GPX   : 메타를 API 최신값으로 보강
      └─ API 에 없음    → GPX_ONLY  : 시드 메타를 그대로 쓴다
    API 에만 있는 코스   → 신규로 추가 (경로 있는 것만)
    경로가 없는 코스     → API_ONLY  : 지도·추천에 못 쓰므로 제외 (SPEC §8.4)

GPX 는 `crsIdx` 로 URL 이 정해지므로 API 에서 사라진 코스도 그대로 내려받을 수 있다.

키: `scripts/.env` 의 `KTO_SERVICE_KEY`(디코딩 키). data.go.kr 페어 키 중 디코딩
쪽을 쓴다 — `requests` 의 `params=` 가 URL 인코딩을 해 주기 때문이다(SPEC §7.2).
"""
from __future__ import annotations

import json
import logging
import os
import re
import time
from collections.abc import Iterator
from pathlib import Path

import requests

from ..geo import cum_dist, haversine_m
from ..model import RawCourse
from .base import CourseSource, register

log = logging.getLogger(__name__)

API_BASE = "http://apis.data.go.kr/B551011/Durunubi"
WALKING = "DNWW"  # 걷기길. 자전거길(DNBW)은 제외한다.

#: crsIdx 로 GPX 를 직접 받는 URL. API 응답의 gpxpath 와 같은 형식이다.
GPX_URL = "https://www.durunubi.kr/editImgUp.do?filePath=/data/koreamobility/course/summap/{crs_id}.gpx"

REQUEST_DELAY = 0.4  # 공공 API 배려. GPX 261개를 연달아 받으므로 여유를 둔다.
TIMEOUT = 30

ROOT = Path(__file__).resolve().parents[3]
SEED = ROOT / "reference-web" / "src" / "data" / "durunubi_courses.json"
CACHE_DIR = ROOT / ".cache" / "durunubi_gpx"

_TRK = re.compile(r"<trk>(.*?)</trk>", re.S)
_TRKPT = re.compile(r'<trkpt\s+lat="([-\d.]+)"\s+lon="([-\d.]+)"\s*>(.*?)</trkpt>', re.S)
_ELE = re.compile(r"<ele>([-\d.]+)</ele>")
_TAG = re.compile(r"<[^>]+>")
JUMP_THRESHOLD_M = 500.0


def _points_of(fragment: str) -> list[tuple[float, float, float]]:
    out = []
    for m in _TRKPT.finditer(fragment):
        ele = _ELE.search(m.group(3))
        out.append(
            (float(m.group(1)), float(m.group(2)), float(ele.group(1)) if ele else 0.0)
        )
    return out


def _split_at_jumps(points: list[tuple[float, float, float]]) -> list[list[tuple[float, float, float]]]:
    """같은 trk 안에 잘못 이어진 500m 초과 순간이동을 서로 다른 경로로 분리한다."""
    if not points:
        return []
    segments = [[points[0]]]
    for point in points[1:]:
        if haversine_m(segments[-1][-1], point) > JUMP_THRESHOLD_M:
            segments.append([point])
        else:
            segments[-1].append(point)
    return [segment for segment in segments if len(segment) >= 2]


def _distance_km(points: list[tuple[float, float, float]]) -> float:
    return cum_dist(points)[-1] / 1000


def _parse_gpx(xml: str, declared_km: float | None = None) -> list[tuple[float, float, float]]:
    """GPX → [(lat, lng, ele)].

    **`<trk>` 를 이어붙이지 않고, 같은 `<trk>` 안의 500m 초과 점프도 끊는다.** 두루누비
    원본에는 중복 트랙뿐 아니라 떨어진 두 조각을 한 트랙으로 넣은 코스도 있다(#130).
    후보가 여러 개면 원천 표기 거리는 올바른 트랙을 고르는 데만 쓰고, 최종 거리는 선택한
    GPX 좌표로 다시 잰다. 표기값으로 실측값을 덮어쓰지 않는다(SPEC §8.4).
    """
    tracks = [_points_of(track) for track in _TRK.findall(xml)]
    if not tracks:
        tracks = [_points_of(xml)]  # <trk> 없이 trkpt 만 있는 변형 대비
    candidates = [segment for track in tracks for segment in _split_at_jumps(track)]
    if not candidates:
        return []
    if declared_km is not None and declared_km > 0:
        return min(candidates, key=lambda candidate: (abs(_distance_km(candidate) - declared_km), -len(candidate)))
    return max(candidates, key=lambda candidate: (_distance_km(candidate), len(candidate)))


@register
class Durunubi(CourseSource):
    key = "durunubi"
    attribution = "두루누비 걷기길(한국관광공사)"
    license = "공공데이터포털 이용약관 — 출처표시"
    derivable = True

    def __init__(self, service_key: str | None = None, use_cache: bool = True):
        # `durunubi:seed`는 승인된 261개 시드와 공개 GPX만으로 장애 대응 번들을 만든다.
        # 이때 메타는 런타임 전체 동기화 전 상태이므로 전부 GPX_ONLY가 맞다.
        self.seed_only = service_key == "seed"
        self.service_key = "" if self.seed_only else (service_key or os.environ.get("KTO_SERVICE_KEY", ""))
        if not self.seed_only and not self.service_key:
            raise SystemExit(
                "환경변수 KTO_SERVICE_KEY 가 없다. scripts/.env 를 만들고 넣어라.\n"
                "  cp scripts/.env.example scripts/.env\n"
                "  set -a; source scripts/.env; set +a\n"
                "data.go.kr 페어 키 중 **디코딩** 키를 쓴다(SPEC §7.2)."
            )
        self.use_cache = use_cache
        self.session = requests.Session()
        CACHE_DIR.mkdir(parents=True, exist_ok=True)

    # ── 시드 ──────────────────────────────────────────────────

    def _seed(self) -> dict[str, dict]:
        """기존 261코스. API 에서 사라진 119개를 지키는 기준이다(§C4)."""
        if not SEED.exists():
            log.warning("시드 %s 가 없다 — API 응답만 쓰면 코스가 줄어든다", SEED)
            return {}
        rows = json.loads(SEED.read_text(encoding="utf-8"))
        return {r["id"]: r for r in rows if r.get("id")}

    # ── API 메타 ──────────────────────────────────────────────

    def _api_meta(self) -> dict[str, dict]:
        """courseList 를 끝까지 페이징해 crsIdx → row 로 모은다."""
        out: dict[str, dict] = {}
        page, size = 1, 100
        while True:
            r = self.session.get(
                f"{API_BASE}/courseList",
                params={
                    "serviceKey": self.service_key,
                    "pageNo": page,
                    "numOfRows": size,
                    "MobileOS": "ETC",
                    "MobileApp": "RunningGu",
                    "brdDiv": WALKING,
                    "_type": "json",
                },
                timeout=TIMEOUT,
            )
            r.raise_for_status()
            body = r.json().get("response", {}).get("body", {})
            rows = (body.get("items") or {}).get("item") or []
            if isinstance(rows, dict):
                rows = [rows]
            if not rows:
                break
            for row in rows:
                crs_id = (row.get("crsIdx") or "").strip()
                if crs_id:
                    out[crs_id] = row
            if page * size >= int(body.get("totalCount", 0)):
                break
            page += 1
            time.sleep(REQUEST_DELAY)
        return out

    # ── GPX ───────────────────────────────────────────────────

    def _gpx(self, crs_id: str, url: str | None = None) -> str | None:
        cached = CACHE_DIR / f"{crs_id}.gpx"
        if self.use_cache and cached.exists():
            return cached.read_text(encoding="utf-8", errors="replace")
        try:
            r = self.session.get(url or GPX_URL.format(crs_id=crs_id), timeout=TIMEOUT)
            r.raise_for_status()
        except requests.RequestException as e:
            log.warning("%s GPX 실패: %s", crs_id, e)
            return None
        text = r.content.decode("utf-8", errors="replace")
        cached.write_text(text, encoding="utf-8")
        time.sleep(REQUEST_DELAY)
        return text

    # ── 수집 ──────────────────────────────────────────────────

    def fetch(self) -> Iterator[RawCourse]:
        seed = self._seed()
        api = {} if self.seed_only else self._api_meta()

        ids = list(seed) + [i for i in api if i not in seed]
        log.info(
            "시드 %d · API %d · 합집합 %d (API 에만 %d, 시드에만 %d)",
            len(seed), len(api), len(ids),
            len(set(api) - set(seed)), len(set(seed) - set(api)),
        )

        for crs_id in ids:
            row = api.get(crs_id) or {}
            s = seed.get(crs_id) or {}

            declared_km = (
                float(row["crsDstnc"])
                if row.get("crsDstnc")
                else s.get("distKm")
            )

            xml = self._gpx(crs_id, (row.get("gpxpath") or "").strip() or None)
            points = _parse_gpx(xml, declared_km) if xml else []

            if len(points) < 2:
                # GPX 를 못 얻었으면 시드의 좌표라도 쓴다. 고도가 없어 난이도는 못 매기지만
                # 코스를 잃는 것보다 낫다. 둘 다 없으면 API_ONLY 라 서비스 대상이 아니다.
                fallback = s.get("points") or []
                if len(fallback) < 2:
                    log.warning("%s: 좌표 없음 — API_ONLY 로 보고 제외", crs_id)
                    continue
                log.info("%s: GPX 실패 — 시드 좌표로 대체(고도 없음)", crs_id)
                points = [(p[0], p[1]) for p in fallback]

            sigun = (row.get("sigun") or s.get("sigun") or "").strip()
            level_raw = row.get("crsLevel") or s.get("level")
            summary = row.get("crsSummary") or s.get("summary") or ""

            yield RawCourse(
                id=crs_id,
                name=(row.get("crsKorNm") or s.get("name") or "").strip(),
                points=points,
                # sigun 이 좌표와 어긋나는 사례가 있다(서울 강동구 표기 코스의 실제 좌표는
                # 철원). 파이프라인의 --regeocode 가 좌표 기준으로 다시 매긴다.
                sido=(s.get("sido") or (sigun.split()[0] if sigun else "")).strip(),
                sigun=sigun,
                level=int(level_raw) if str(level_raw or "").isdigit() else None,
                cycle=(row.get("crsCycle") or s.get("cycle") or "").strip(),
                summary=_TAG.sub(" ", summary).strip(),
                declared_km=declared_km,
                data_source="API_GPX" if crs_id in api else "GPX_ONLY",
            )
