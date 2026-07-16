"""
geocode.py — 장소명/주소 → 좌표(lat, lng) 변환 (카카오 로컬 API)

런닝구 백엔드 공용 유틸. 대회 위치·POI 둘 다 이걸로 좌표를 채운다.
- 1차: 키워드 검색(장소명, 예: "수원화성")
- 2차: 주소 검색(키워드 실패 시, 예: "경기 수원시 ...")
- 캐시: 같은 질의 재호출 방지 + 카카오 요청제한(QPS) 완화 (geocode_cache.json)

사전 준비:
  1) https://developers.kakao.com → 내 애플리케이션 → REST API 키 복사
     (※ 프론트의 JavaScript 키와 다름!)
  2) 환경변수로 키 주입:  export KAKAO_REST_KEY="발급받은_REST_키"
  3) pip install requests

사용(코드):
  from geocode import geocode
  lat, lng = geocode("수원화성")          # (37.2880, 127.0140) 비슷하게 반환
  lat, lng = geocode("해운대 해수욕장")

사용(CLI 테스트):
  python geocode.py "수원화성"
"""

from __future__ import annotations
import json
import os
import sys
import time
from pathlib import Path

import requests

KAKAO_REST_KEY = os.environ.get("KAKAO_REST_KEY", "")
KEYWORD_URL = "https://dapi.kakao.com/v2/local/search/keyword.json"
ADDRESS_URL = "https://dapi.kakao.com/v2/local/search/address.json"
CACHE_PATH = Path(__file__).with_name("geocode_cache.json")

# ── 캐시 로드/저장 ────────────────────────────────────────────────
_cache: dict[str, list | None] = {}
if CACHE_PATH.exists():
    try:
        _cache = json.loads(CACHE_PATH.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        _cache = {}


def _save_cache() -> None:
    CACHE_PATH.write_text(json.dumps(_cache, ensure_ascii=False, indent=2), encoding="utf-8")


def _headers() -> dict[str, str]:
    if not KAKAO_REST_KEY:
        raise RuntimeError(
            "KAKAO_REST_KEY 환경변수가 비어있어요. "
            'export KAKAO_REST_KEY="..." 로 REST API 키를 넣어주세요.'
        )
    return {"Authorization": f"KakaoAK {KAKAO_REST_KEY}"}


def _first_coord(url: str, query: str) -> tuple[float, float] | None:
    """카카오 응답 documents[0]에서 (lat, lng) 추출. 결과 없으면 None."""
    resp = requests.get(url, headers=_headers(), params={"query": query, "size": 1}, timeout=10)
    resp.raise_for_status()
    docs = resp.json().get("documents", [])
    if not docs:
        return None
    d = docs[0]
    # 카카오: x=경도(lng), y=위도(lat)  ← 헷갈리기 쉬움
    return (round(float(d["y"]), 6), round(float(d["x"]), 6))


def geocode(query: str, *, sleep: float = 0.2) -> tuple[float, float] | None:
    """
    장소명/주소 → (lat, lng). 못 찾으면 None.
    키워드검색 → 주소검색 순으로 시도. 결과는 캐시에 저장.
    """
    query = (query or "").strip()
    if not query:
        return None

    # 캐시 히트
    if query in _cache:
        hit = _cache[query]
        return tuple(hit) if hit else None

    coord = _first_coord(KEYWORD_URL, query)
    if coord is None:
        coord = _first_coord(ADDRESS_URL, query)

    _cache[query] = list(coord) if coord else None
    _save_cache()
    time.sleep(sleep)  # 요청제한 완화
    return coord


def geocode_records(records: list[dict], name_key: str = "location",
                    lat_key: str = "lat", lng_key: str = "lng") -> tuple[list[dict], list[dict]]:
    """
    레코드 리스트에 좌표를 일괄 채운다.
    - name_key 필드(장소명/주소)를 지오코딩해 lat_key/lng_key에 기록
    반환: (성공 리스트, 실패 리스트)  ← 실패는 unresolved로 따로 관리
    """
    ok, failed = [], []
    for r in records:
        coord = geocode(str(r.get(name_key, "")))
        if coord:
            r[lat_key], r[lng_key] = coord
            ok.append(r)
        else:
            failed.append(r)
    return ok, failed


if __name__ == "__main__":
    q = " ".join(sys.argv[1:]) or "수원화성"
    try:
        result = geocode(q)
    except RuntimeError as e:
        print(f"[설정 필요] {e}")
        sys.exit(1)
    if result:
        print(f"{q} → lat={result[0]}, lng={result[1]}")
    else:
        print(f"{q} → 좌표를 찾지 못했어요. 질의어를 더 구체적으로 (시/구 포함) 해보세요.")
