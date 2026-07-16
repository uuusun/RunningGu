"""
count_races.py
==============
마라톤GO + 마라톤온라인(roadrun)에 '몇 개의 대회'가 등록돼 있는지 개수만 빠르게 확인.

- 상세 페이지는 수집하지 않음. 목록 페이지를 사이트당 1번씩만 요청.
- 마라톤GO  : /raceDetail/domestic/<slug> 링크의 고유 개수
- 마라톤온라인: view.php?no=<번호> 의 고유 개수 (+ 사이트가 표시하는 '총 N개' 숫자)
- 여기서 세는 건 거리 필터(풀코스 초과 제외) 적용 '이전'의 원본 개수.
  → 실제 크롤러가 수집하는 수보다 보통 많음.

requirements:  pip install requests beautifulsoup4 lxml
"""

from __future__ import annotations

import re
import requests
from bs4 import BeautifulSoup

UA = "RunningguBot/0.2 (count-only; contact: your-email@example.com)"
HEADERS = {"User-Agent": UA}
TIMEOUT = 15

MGO_LIST = "https://marathongo.co.kr/raceSchedule/domestic"
ROADRUN_LIST = "http://www.roadrun.co.kr/schedule/list.php"


def fetch(url: str, encoding: str) -> str:
    """content를 지정 인코딩으로 직접 디코딩(인코딩 추측 오류 방지)."""
    try:
        r = requests.get(url, headers=HEADERS, timeout=TIMEOUT)
        r.raise_for_status()
        return r.content.decode(encoding, errors="replace")
    except requests.RequestException as e:
        print(f"[요청 실패] {url}: {e}")
        return ""


def count_marathongo() -> int:
    html = fetch(MGO_LIST, "utf-8")
    if not html:
        return 0
    soup = BeautifulSoup(html, "lxml")
    slugs = {
        a["href"].rstrip("/").split("/")[-1]
        for a in soup.select('a[href*="/raceDetail/domestic/"]')
    }
    return len(slugs)


def count_roadrun() -> tuple[int, int | None]:
    html = fetch(ROADRUN_LIST, "euc-kr")
    if not html:
        return 0, None
    nos = set(re.findall(r"view\.php\?no=(\d+)", html))
    stated = None  # 사이트가 직접 표기하는 '현재 총 N개의 대회' 숫자
    if (m := re.search(r"총\s*([\d,]+)\s*개의\s*대회", html)):
        stated = int(m.group(1).replace(",", ""))
    return len(nos), stated


def main():
    mgo = count_marathongo()
    mol, mol_stated = count_roadrun()

    print("=== 등록 대회 개수 (상세 미수집, 거리필터 적용 전) ===")
    print(f"마라톤GO        : {mgo}개")
    if mol_stated is not None:
        print(f"마라톤온라인    : {mol}개  (사이트 표기: 총 {mol_stated}개)")
    else:
        print(f"마라톤온라인    : {mol}개")
    print(f"합계(중복 미제거): {mgo + mol}개")


if __name__ == "__main__":
    main()
