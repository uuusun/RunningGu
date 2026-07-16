"""
add_coordinates.py
==================
이미 만들어둔 output/races_sample.(json/csv)을 읽어, 비어 있는 위도/경도만
카카오 로컬 API로 채워 다시 저장한다. 재크롤링하지 않는다.

동작
  - 입력은 JSON을 기준으로 읽고(타입 보존), 좌표만 채운 뒤 JSON+CSV 둘 다 갱신.
  - 좌표 조회 우선순위: 도로명주소(roadrun) → '지역+장소' 키워드(폴백 단계적 확장).
  - 이미 좌표가 있는 행은 건너뜀 → 여러 번 돌려도 안전(중단 후 재개 가능).
  - 못 찾은 장소는 끝에 목록으로 출력 → VENUE_OVERRIDES에 추가해 재실행하면 됨.

사용법
  1) 아래 KAKAO_REST_API_KEY 에 카카오 developers REST 키 입력
  2) (필요시) IN_JSON / OUT_JSON / OUT_CSV 경로 확인
  3) python3 add_coordinates.py

requirements:  pip install requests
"""

from __future__ import annotations

import csv
import json
import re
import time

import requests

# --- 설정 -------------------------------------------------------------------
KAKAO_REST_API_KEY = ""   # ← 카카오 developers REST API 키 입력 (비우면 좌표 못 채움)

IN_JSON = "output/races_sample.json"     # 읽을 파일(JSON 기준)
OUT_JSON = "output/races_sample.json"    # 저장 파일(같은 경로 = 덮어쓰기)
OUT_CSV = "output/races_sample.csv"

SLEEP_SEC = 0.25          # 카카오 호출 간 최소 간격
TIMEOUT = 15

# 지오코딩 안 잡히는 장소 수동 보정(오타/비표준 명칭).
#  값이 (lat, lng) 튜플이면 좌표 직접 사용 / 문자열이면 그 이름으로 재검색.
VENUE_OVERRIDES = {
    "송바람해수욕장": "남해 송정솔바람해변",       # '송바람' = '솔바람' 오기
    "설익산한계령휴계소": "한계령휴게소",         # '설익산'=설악산, '휴계소'=휴게소 오기
    "화물터미널청계산옛골": "청계산 옛골",         # 청계산 등산 들머리로 정리
    "덕유산국립공원다목적광장": "덕유산국립공원",   # '다목적광장' 미등록 → 국립공원으로
}


# --- 장소 문자열 정리 / 검색어 후보 ------------------------------------------
def clean_venue(v: str) -> str:
    if not v:
        return ""
    v = v.split(",")[0].split("·")[0]            # 여러 장소면 첫 장소만
    v = re.sub(r"\(.*?\)", " ", v)               # (예정) 등 괄호 제거
    v = re.sub(r"\s+", " ", v).strip()
    v = re.sub(r"(일원|일대|앞|인근|부근|근처|옆)$", "", v).strip()
    return v


def keyword_candidates(region: str, venue: str) -> list[str]:
    """정확한 이름이 안 잡힐 때를 대비해 점점 넓혀가는 검색어 후보."""
    v = clean_venue(venue)
    words = v.split()
    cands = []
    if region and v:
        cands.append(f"{region} {v}")
    if v:
        cands.append(v)
    if region and words:
        cands.append(f"{region} {' '.join(words[:2])}")
        cands.append(f"{region} {words[0]}")
    seen, out = set(), []
    for c in (x.strip() for x in cands):
        if c and c not in seen:
            seen.add(c)
            out.append(c)
    return out


# --- 카카오 지오코더 ---------------------------------------------------------
class KakaoGeocoder:
    def __init__(self, key: str):
        self.key = key
        self.cache: dict[tuple[str, str], tuple] = {}
        self.session = requests.Session()
        if key:
            self.session.headers.update({"Authorization": f"KakaoAK {key}"})

    def _search(self, kind: str, query: str) -> tuple:
        if not query:
            return (None, None)
        if (kind, query) in self.cache:
            return self.cache[(kind, query)]
        result = (None, None)
        try:
            time.sleep(SLEEP_SEC)
            r = self.session.get(
                f"https://dapi.kakao.com/v2/local/search/{kind}.json",
                params={"query": query}, timeout=TIMEOUT,
            )
            docs = r.json().get("documents", [])
            if docs:
                result = (float(docs[0]["y"]), float(docs[0]["x"]))  # (lat, lng)
        except Exception as e:
            print(f"  [지오코딩 오류] {kind} '{query}': {e}")
        self.cache[(kind, query)] = result
        return result

    def locate(self, road_address: str, region: str, venue: str) -> tuple:
        if not self.key:
            return (None, None)
        # 0) 수동 보정
        for key, val in VENUE_OVERRIDES.items():
            if key in (venue or ""):
                if isinstance(val, tuple):
                    return val
                ll = self._search("keyword", val)
                if ll[0] is not None:
                    return ll
                break
        # 1) 도로명주소 우선
        if road_address:
            ll = self._search("address", road_address)
            if ll[0] is not None:
                return ll
        # 2) 키워드 폴백 체인
        for q in keyword_candidates(region, venue):
            ll = self._search("keyword", q)
            if ll[0] is not None:
                return ll
        return (None, None)


# --- 저장 --------------------------------------------------------------------
def save_json(data: list[dict], path: str):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def save_csv(data: list[dict], path: str):
    if not data:
        return
    fieldnames = list(data[0].keys())  # 원본 컬럼 순서 유지
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        for r in data:
            row = {}
            for k, v in r.items():
                if isinstance(v, list):
                    row[k] = json.dumps(v, ensure_ascii=False)  # 리스트는 JSON 문자열
                elif v is None:
                    row[k] = ""
                else:
                    row[k] = v
            w.writerow(row)


# --- 메인 --------------------------------------------------------------------
def main():
    with open(IN_JSON, encoding="utf-8") as f:
        data = json.load(f)

    geo = KakaoGeocoder(KAKAO_REST_API_KEY)
    if not KAKAO_REST_API_KEY:
        print("⚠ KAKAO_REST_API_KEY가 비어 있습니다. 키를 넣어야 좌표가 채워집니다.")

    todo = [r for r in data if not r.get("latitude")]
    print(f"전체 {len(data)}건 중 좌표 없는 {len(todo)}건 처리 시작")

    filled, failed = 0, []
    for i, r in enumerate(todo, 1):
        lat, lng = geo.locate(
            r.get("road_address", ""), r.get("region", ""), r.get("venue", "")
        )
        r["latitude"], r["longitude"] = lat, lng
        if lat is not None:
            filled += 1
        else:
            failed.append(f"[{r.get('source')}] {r.get('region')} {r.get('venue')}")
        if i % 20 == 0:
            print(f"  ...{i}/{len(todo)} (채움 {filled})")

    save_json(data, OUT_JSON)
    save_csv(data, OUT_CSV)

    print(f"\n완료: {filled}건 채움 / {len(todo) - filled}건 실패")
    print(f"저장: {OUT_JSON}, {OUT_CSV}")
    if failed:
        print(f"\n좌표 못 찾은 장소 {len(failed)}건(상위 20):")
        for v in failed[:20]:
            print("  -", v)
        print("→ VENUE_OVERRIDES에 추가 후 다시 실행하면 그 행만 채워집니다.")


if __name__ == "__main__":
    main()