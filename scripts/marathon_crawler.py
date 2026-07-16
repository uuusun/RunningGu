"""
marathon_crawler.py
===================
마라톤GO(marathongo.co.kr) + 마라톤온라인(roadrun.co.kr) 통합 크롤러 - 테스트 버전

[이번 버전의 핵심]
  1. 인코딩 깨짐 해결
     - requests가 응답 인코딩을 잘못 추측(Latin-1)해서 한글이 깨졌음.
     - 해결: resp.content 를 사이트별 인코딩으로 '직접' 디코딩.
       · 마라톤GO   = UTF-8
       · 마라톤온라인 = EUC-KR
  2. 두 사이트를 하나의 통일 스키마(Race)로 수집.
  3. 형식이 다른 값은 수집 시점에 변환(날짜/시간/접수기간/종목/지역).
  4. 테스트로 각 사이트 10개씩만 수집(LIMIT_PER_SITE).

[지난 버전과 동일하게 유지한 원칙]
  - robots.txt 준수, rate limit, 인증/차단 우회 없음, 출처·최근확인일 기록.
  - 개인정보(대표자명·이메일·휴대폰)는 기본 미수집(STORE_PERSONAL_CONTACT=False).
    특히 roadrun은 개인 메일/휴대폰이 그대로 노출되므로 더 주의.

requirements:  pip install requests beautifulsoup4 lxml
"""

from __future__ import annotations

import csv
import json
import logging
import random
import re
import time
from dataclasses import dataclass, asdict, field
from datetime import date, datetime
from pathlib import Path
from urllib.parse import urljoin, urlparse, urlunparse
from urllib.robotparser import RobotFileParser

import requests
from bs4 import BeautifulSoup

# ---------------------------------------------------------------------------
# 설정
# ---------------------------------------------------------------------------
LIMIT_PER_SITE = 10          # 테스트: 사이트당 10개. 전체 수집 시 늘리거나 None.

USER_AGENT = (
    "RunningguBot/0.2 (Korea Tourism Data Contest project; "
    "contact: your-email@example.com) python-requests"
)
REQUEST_DELAY = 2.0
DELAY_JITTER = 1.0
TIMEOUT = 15
MAX_RETRIES = 3
STORE_PERSONAL_CONTACT = False   # True로 바꾸면 대표자명/이메일/휴대폰 수집(개인정보보호법 주의)

MAX_DISTANCE_KM = 42.195         # 풀코스까지만 포함. 이보다 긴 종목(50km+ 울트라 등)은 대회째 제외.

# 카카오 로컬 API REST 키 (https://developers.kakao.com → 앱 → REST API 키).
# 비워두면 좌표는 채우지 않고 건너뜀(나머지는 정상 수집).
KAKAO_REST_API_KEY = ""

# 지오코딩이 안 잡히는 장소 수동 보정(오타/비표준 명칭 대응).
#  - key: 장소(venue)에 이 문자열이 포함되면 적용
#  - value: (lat, lng) 튜플이면 좌표를 그대로 사용 / 문자열이면 그 이름으로 재검색
VENUE_OVERRIDES = {
    "송바람해수욕장": "남해 송정솔바람해변",       # '송바람' = '솔바람' 오기
    "설익산한계령휴계소": "한계령휴게소",         # '설익산'=설악산, '휴계소'=휴게소 오기
    "화물터미널청계산옛골": "청계산 옛골",         # 청계산 등산 들머리로 정리
    "덕유산국립공원다목적광장": "덕유산국립공원",   # '다목적광장' 미등록 → 국립공원으로
}

MGO_BASE = "https://marathongo.co.kr"
MGO_LIST = f"{MGO_BASE}/raceSchedule/domestic"
ROADRUN_BASE = "http://www.roadrun.co.kr"
ROADRUN_LIST = f"{ROADRUN_BASE}/schedule/list.php"

OUTPUT_DIR = Path("./output")
OUTPUT_DIR.mkdir(exist_ok=True)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("crawler")

SIDO_MAP = {
    "서울": "서울특별시", "부산": "부산광역시", "대구": "대구광역시",
    "인천": "인천광역시", "광주": "광주광역시", "대전": "대전광역시",
    "울산": "울산광역시", "세종": "세종특별자치시", "경기": "경기도",
    "강원": "강원특별자치도", "충북": "충청북도", "충남": "충청남도",
    "전북": "전북특별자치도", "전남": "전라남도", "경북": "경상북도",
    "경남": "경상남도", "제주": "제주특별자치도",
}


# ---------------------------------------------------------------------------
# 통일 스키마
# ---------------------------------------------------------------------------
@dataclass
class Race:
    # 식별 / 출처
    source: str = ""             # 마라톤GO / 마라톤온라인
    race_id: str = ""            # mgo: slug, roadrun: roadrun-<no>
    detail_url: str = ""
    crawled_at: str = ""
    last_checked: str = ""

    # 원본 + 정규화 (두 사이트 공통 컬럼)
    name: str = ""
    date_raw: str = ""
    event_date: str = ""         # ISO yyyy-mm-dd (변환 결과)
    time_raw: str = ""           # mgo=집결시간, roadrun=출발시간
    start_time: str = ""         # HH:MM (변환 결과)
    region: str = ""             # 원본 시/도 토큰
    sido: str = ""               # 표준 시/도
    venue: str = ""              # 장소명
    road_address: str = ""       # 도로명주소 (roadrun에서 추출, mgo는 보통 빈값)
    events_raw: str = ""
    distances: list = field(default_factory=list)
    event_types: list = field(default_factory=list)
    has_full: bool = False
    has_half: bool = False
    has_10k: bool = False
    has_5k: bool = False
    category: str = ""
    organizer: str = ""
    reg_start: str = ""          # ISO
    reg_end: str = ""            # ISO
    reg_status: str = ""         # 접수전/접수중/마감/미정
    official_url: str = ""
    description: str = ""
    image_url: str = ""

    # 개인정보(기본 미수집)
    contact_email: str = ""
    contact_phone: str = ""

    # 외부 보강(카카오 지오코딩 후처리)
    latitude: float | None = None
    longitude: float | None = None


# ---------------------------------------------------------------------------
# 정중한 HTTP 계층 (호스트별 robots 캐시 + rate limit + 명시적 디코딩)
# ---------------------------------------------------------------------------
class PoliteFetcher:
    def __init__(self):
        self.session = requests.Session()
        self.session.headers.update({"User-Agent": USER_AGENT})
        self._robots: dict[str, RobotFileParser] = {}

    def _robot(self, url: str) -> RobotFileParser:
        host = urlparse(url).netloc
        if host not in self._robots:
            rp = RobotFileParser()
            rp.set_url(f"{urlparse(url).scheme}://{host}/robots.txt")
            try:
                rp.read()
            except Exception as e:
                # robots.txt 없거나 못 읽으면 관례상 허용으로 둠(차단 우회 아님).
                log.warning("robots.txt 읽기 실패(%s) host=%s → 허용 처리", e, host)
                rp.allow_all = True
            self._robots[host] = rp
        return self._robots[host]

    def allowed(self, url: str) -> bool:
        try:
            return self._robot(url).can_fetch(USER_AGENT, url)
        except Exception:
            return True

    def get(self, url: str, encoding: str, referer: str | None = None) -> str | None:
        """robots 확인 → 딜레이 → 요청 → content를 지정 인코딩으로 직접 디코딩."""
        if not self.allowed(url):
            log.warning("robots.txt가 금지 - 건너뜀: %s", url)
            return None
        headers = {"Referer": referer} if referer else {}
        for attempt in range(1, MAX_RETRIES + 1):
            time.sleep(REQUEST_DELAY + random.uniform(0, DELAY_JITTER))
            try:
                resp = self.session.get(url, timeout=TIMEOUT, headers=headers)
                if resp.status_code == 200:
                    # 핵심: requests의 인코딩 추측을 무시하고 직접 디코딩
                    return resp.content.decode(encoding, errors="replace")
                if resp.status_code in (429, 500, 502, 503, 504):
                    time.sleep(REQUEST_DELAY * attempt * 2)
                    continue
                log.warning("%s -> HTTP %s", url, resp.status_code)
                return None
            except requests.RequestException as e:
                log.warning("요청 오류(%s) %d/%d: %s", e, attempt, MAX_RETRIES, url)
        return None


# ---------------------------------------------------------------------------
# 공통 변환 함수 (형식이 달라도 같은 결과로)
# ---------------------------------------------------------------------------
_ISO_RE = re.compile(r"(\d{4})-(\d{1,2})-(\d{1,2})")
_KDATE_RE = re.compile(r"(\d{4})\s*년\s*(\d{1,2})\s*월\s*(\d{1,2})\s*일")
_DOTDATE_RE = re.compile(r"(\d{4})\.\s*(\d{1,2})\.\s*(\d{1,2})")


def to_iso_date(text: str) -> str:
    """'2026-06-13' / '2026년6월5일' / '2026.06.13' → '2026-06-13'. 실패 시 ''."""
    if not text:
        return ""
    for rx in (_ISO_RE, _KDATE_RE, _DOTDATE_RE):
        if (m := rx.search(text)):
            y, mo, d = map(int, m.groups())
            try:
                return date(y, mo, d).isoformat()
            except ValueError:
                return ""
    return ""


def to_hhmm(text: str) -> str:
    """'07:30 집결' / '오전 9시 00분' / '21시' / '출발시간:21시' → 'HH:MM'."""
    if not text:
        return ""
    t = text.replace("출발시간", "").replace("집결", "").replace("출발", "")
    pm = ("오후" in t) or ("PM" in t.upper())
    am = ("오전" in t) or ("AM" in t.upper())

    h = mnt = None
    if (m := re.search(r"([0-2]?\d)\s*:\s*([0-5]\d)", t)):          # HH:MM
        h, mnt = int(m.group(1)), int(m.group(2))
    elif (m := re.search(r"([0-2]?\d)\s*시\s*([0-5]?\d)\s*분", t)):  # N시 M분
        h, mnt = int(m.group(1)), int(m.group(2))
    elif (m := re.search(r"([0-2]?\d)\s*시", t)):                    # N시
        h, mnt = int(m.group(1)), 0
    if h is None:
        return ""
    if pm and h < 12:
        h += 12
    if am and h == 12:
        h = 0
    if not (0 <= h <= 23):
        return ""
    return f"{h:02d}:{mnt:02d}"


def parse_reg_period(text: str) -> tuple[str, str]:
    """'2026.03.26 ~ 2026.03.26' / '2026년2월3일~2026년4월30일' → (ISO, ISO)."""
    if not text or "~" not in text:
        return "", ""
    left, right = text.split("~", 1)
    return to_iso_date(left), to_iso_date(right)


_DIST_RE = re.compile(r"(\d+(?:\.\d+)?)\s*(?:km|k)\b", re.IGNORECASE)


def dist_label(d: float) -> str:
    """거리값 → 표시 라벨. 풀/하프는 한글, 나머지는 'Nkm'."""
    if abs(d - 42.195) < 0.01:
        return "풀"
    if abs(d - 21.0975) < 0.001:
        return "하프"
    return f"{int(d)}km" if float(d).is_integer() else f"{d}km"


def parse_events(events_raw: str) -> dict:
    ev = events_raw or ""
    has_full = "풀" in ev
    has_half = "하프" in ev
    has_10k = bool(re.search(r"10\s*km", ev, re.IGNORECASE))
    has_5k = bool(re.search(r"(?<!\d)5\s*km", ev, re.IGNORECASE))

    dists = []
    if has_full:
        dists.append(42.195)
    if has_half:
        dists.append(21.0975)
    for tok in _DIST_RE.findall(ev):
        try:
            dists.append(float(tok))
        except ValueError:
            pass
    dists = sorted(set(dists), reverse=True)

    return {
        "distances": dists,
        "event_types": [dist_label(d) for d in dists],  # 모든 거리를 라벨로
        "has_full": has_full, "has_half": has_half,
        "has_10k": has_10k, "has_5k": has_5k,
    }


def derive_category(name: str, events: str) -> str:
    blob = (name + events).lower()
    if "트레일" in blob or "trail" in blob:
        return "트레일"
    if "울트라" in blob or "ultra" in blob or re.search(r"\b(100|200|50)\s*km", blob):
        return "울트라"
    if "걷기" in blob:
        return "걷기"
    if "나이트" in blob or "야간" in blob or "night" in blob:
        return "야간"
    return "로드"


def derive_reg_status(reg_start: str, reg_end: str, today: date) -> str:
    def p(s):
        try:
            return date.fromisoformat(s)
        except (ValueError, TypeError):
            return None
    s, e = p(reg_start), p(reg_end)
    if not s or not e:
        return "미정"
    if today < s:
        return "접수전"
    if today > e:
        return "마감"
    return "접수중"


def in_scope(r: Race) -> bool:
    """풀코스(42.195km)까지만 포함. 그보다 긴 종목이 있으면 대회째 제외.
    (50km/100km 울트라, 장거리 트레일 등 → 제외). 거리 미상은 일단 포함."""
    if not r.distances:
        return True
    return max(r.distances) <= MAX_DISTANCE_KM


def clean_venue(v: str) -> str:
    """지오코딩 정확도용 장소 정리: 괄호·꼬리표 제거."""
    if not v:
        return ""
    v = re.sub(r"\(.*?\)", " ", v)                       # (예정) 등 괄호 제거
    v = re.sub(r"\s+", " ", v).strip()
    v = re.sub(r"(일원|일대|앞|인근|부근|근처|옆)$", "", v).strip()
    return v


class Geocoder:
    """카카오 로컬 API로 좌표 조회. 도로명주소 우선, 없으면 '지역+장소' 키워드 검색."""

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
        url = f"https://dapi.kakao.com/v2/local/search/{kind}.json"
        result = (None, None)
        try:
            time.sleep(0.25)  # 호출 간 최소 간격
            resp = self.session.get(url, params={"query": query}, timeout=TIMEOUT)
            docs = resp.json().get("documents", [])
            if docs:
                result = (float(docs[0]["y"]), float(docs[0]["x"]))  # (lat, lng)
        except Exception as e:
            log.warning("지오코딩 실패 [%s] %s: %s", kind, query, e)
        self.cache[(kind, query)] = result
        return result

    def _keyword_candidates(self, region: str, venue: str) -> list[str]:
        """정확한 이름이 안 잡힐 때를 대비해 점점 넓혀가는 검색어 후보."""
        v = clean_venue(venue)
        words = v.split()
        cands = []
        if region and v:
            cands.append(f"{region} {v}")           # 지역 + 전체 장소
        if v:
            cands.append(v)                         # 장소만
        if region and words:
            cands.append(f"{region} {' '.join(words[:2])}")  # 지역 + 앞 2단어
            cands.append(f"{region} {words[0]}")             # 지역 + 첫 단어(읍/면/동 등)
        seen, out = set(), []
        for c in (x.strip() for x in cands):
            if c and c not in seen:
                seen.add(c)
                out.append(c)
        return out

    def fill(self, r: Race):
        if not self.key:
            return
        # 0) 수동 보정 먼저
        for key, val in VENUE_OVERRIDES.items():
            if key in (r.venue or ""):
                if isinstance(val, tuple):
                    r.latitude, r.longitude = val
                    return
                lat, lng = self._search("keyword", val)
                if lat is not None:
                    r.latitude, r.longitude = lat, lng
                    return
                break  # 보정 검색도 실패하면 일반 절차로
        # 1) 도로명주소(roadrun) 우선
        if r.road_address:
            lat, lng = self._search("address", r.road_address)
            if lat is not None:
                r.latitude, r.longitude = lat, lng
                return
        # 2) 키워드 폴백 체인(전체 → 점점 넓게)
        for q in self._keyword_candidates(r.region, r.venue):
            lat, lng = self._search("keyword", q)
            if lat is not None:
                r.latitude, r.longitude = lat, lng
                return


def finalize(r: Race) -> Race:
    """공통 마무리: 파생 컬럼 채우기 + 메타데이터."""
    today = date.today()
    r.crawled_at = datetime.now().isoformat(timespec="seconds")
    r.last_checked = today.isoformat()
    r.event_date = to_iso_date(r.date_raw)
    r.start_time = to_hhmm(r.time_raw)
    r.sido = SIDO_MAP.get(r.region.strip(), r.region.strip())
    ev = parse_events(r.events_raw)
    for k, v in ev.items():
        setattr(r, k, v)
    r.category = derive_category(r.name, r.events_raw)
    r.reg_status = derive_reg_status(r.reg_start, r.reg_end, today)
    if not STORE_PERSONAL_CONTACT:
        r.contact_email = ""
        r.contact_phone = ""
    return r


def strip_query(url: str) -> str:
    if not url:
        return ""
    p = urlparse(url)
    return urlunparse((p.scheme, p.netloc, p.path, "", "", ""))


# ---------------------------------------------------------------------------
# 사이트 1: 마라톤GO (UTF-8)
# ---------------------------------------------------------------------------
def crawl_marathongo(fetcher: PoliteFetcher, geocoder: Geocoder, limit: int) -> list[Race]:
    html = fetcher.get(MGO_LIST, "utf-8")
    if not html:
        return []
    soup = BeautifulSoup(html, "lxml")
    urls, seen = [], set()
    for a in soup.select("a[href]"):
        href = urljoin(MGO_BASE, a["href"])
        if "/raceDetail/domestic/" in href and href not in seen:
            seen.add(href)
            urls.append(href)
    cap = "전체" if limit is None else limit
    log.info("[마라톤GO] 후보 %d건. 범위 내 %s건까지 수집", len(urls), cap)

    out, excluded, failed = [], 0, 0
    for url in urls:
        if limit is not None and len(out) >= limit:
            break
        page = fetcher.get(url, "utf-8")
        if not page:
            failed += 1
            log.warning("[마라톤GO] 수집실패(건너뜀): %s", url)
            continue
        r = parse_marathongo(page, url)
        if not in_scope(r):
            excluded += 1
            log.info("[마라톤GO] 범위초과 제외: %s %s", r.name, r.distances)
            continue
        geocoder.fill(r)
        out.append(r)
        log.info("[마라톤GO %d/%s] %s", len(out), cap, r.name)
    log.info("[마라톤GO] 집계 → 후보 %d / 수집 %d / 범위초과제외 %d / 수집실패 %d",
             len(urls), len(out), excluded, failed)
    return out


def parse_marathongo(html: str, url: str) -> Race:
    soup = BeautifulSoup(html, "lxml")
    slug = urlparse(url).path.rstrip("/").split("/")[-1]
    r = Race(source="마라톤GO", race_id=slug, detail_url=url)

    def meta(prop):
        tag = (soup.find("meta", attrs={"property": prop})
               or soup.find("meta", attrs={"name": prop}))
        return tag["content"].strip() if tag and tag.get("content") else ""

    # og:description = "대회명 | 일시 | 지역 | 장소 | 주최 | 종목 | 홈페이지\n소개..."
    og = meta("og:description")
    parts = [p.strip() for p in og.split("|")]
    if len(parts) >= 6:
        r.name = parts[0]
        r.date_raw = parts[1]
        r.time_raw = parts[1]
        r.region = parts[2]
        r.venue = parts[3]
        r.organizer = parts[4]
        r.events_raw = parts[5]
        if len(parts) >= 7 and parts[6]:
            r.official_url = strip_query(parts[6].split()[0])
    else:
        r.name = meta("og:title").replace(" | 마라톤GO", "").strip()

    text = soup.get_text(" ", strip=True)
    if (m := re.search(r"(\d{4}\.\d{2}\.\d{2})\s*~\s*(\d{4}\.\d{2}\.\d{2})", text)):
        r.reg_start, r.reg_end = to_iso_date(m.group(1)), to_iso_date(m.group(2))
    r.image_url = f"{MGO_BASE}/assets/image/race/domestic/{slug}.webp"
    if "\n" in og:
        r.description = og.split("\n", 1)[1].strip()
    return finalize(r)


# ---------------------------------------------------------------------------
# 사이트 2: 마라톤온라인 / roadrun (EUC-KR)
# ---------------------------------------------------------------------------
_ADDR_RE = re.compile(
    r"(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충청북도|충청남도|충북|충남|"
    r"전북|전남|전라북도|전라남도|경북|경남|경상북도|경상남도|제주)[가-힣0-9·\s]{2,40}?"
    r"(?:로|길)\s*\d+[가-힣0-9\s\-]*"
)


def crawl_roadrun(fetcher: PoliteFetcher, geocoder: Geocoder, limit: int) -> list[Race]:
    html = fetcher.get(ROADRUN_LIST, "euc-kr")
    if not html:
        return []
    # 목록에서 view.php?no=NNNN 의 no 값만 순서대로 추출(중복 제거)
    nos, seen = [], set()
    for m in re.finditer(r"view\.php\?no=(\d+)", html):
        no = m.group(1)
        if no not in seen:
            seen.add(no)
            nos.append(no)
    cap = "전체" if limit is None else limit
    log.info("[마라톤온라인] 후보 %d건. 범위 내 %s건까지 수집", len(nos), cap)

    out, excluded, failed = [], 0, 0
    for no in nos:
        if limit is not None and len(out) >= limit:
            break
        url = f"{ROADRUN_BASE}/schedule/view.php?no={no}"
        # 팝업 상세는 목록을 Referer로 두고 접근(정상 내비게이션 흉내)
        page = fetcher.get(url, "euc-kr", referer=ROADRUN_LIST)
        if not page:
            failed += 1
            log.warning("[마라톤온라인] 수집실패(건너뜀): %s", url)
            continue
        r = parse_roadrun(page, url, no)
        if not in_scope(r):
            excluded += 1
            log.info("[마라톤온라인] 범위초과 제외: %s %s", r.name, r.distances)
            continue
        geocoder.fill(r)
        out.append(r)
        log.info("[마라톤온라인 %d/%s] %s", len(out), cap, r.name)
    log.info("[마라톤온라인] 집계 → 후보 %d / 수집 %d / 범위초과제외 %d / 수집실패 %d",
             len(nos), len(out), excluded, failed)
    return out


# roadrun 상세표의 라벨 → 처리
ROADRUN_LABELS = {"대회명", "대회일시", "대회종목", "대회지역", "대회장소",
                  "주최단체", "접수기간", "홈페이지", "대회장", "기타소개"}


def parse_roadrun(html: str, url: str, no: str) -> Race:
    soup = BeautifulSoup(html, "lxml")
    r = Race(source="마라톤온라인", race_id=f"roadrun-{no}", detail_url=url)

    # 라벨 셀(왼쪽) → 값 셀(오른쪽 형제) 매핑
    cells = {}
    for cell in soup.find_all(["td", "th"]):
        label = cell.get_text(strip=True)
        if label in ROADRUN_LABELS:
            val = cell.find_next_sibling(["td", "th"])
            if val is not None:
                cells[label] = val

    def txt(label):
        return cells[label].get_text(" ", strip=True) if label in cells else ""

    r.name = txt("대회명")
    dt = txt("대회일시")            # 예: '2026년6월5일 출발시간:21시'
    r.date_raw = dt
    r.time_raw = dt
    r.events_raw = txt("대회종목")  # 예: '100km,200km'
    r.region = txt("대회지역")
    r.venue = txt("대회장소")
    r.organizer = txt("주최단체")
    r.reg_start, r.reg_end = parse_reg_period(txt("접수기간"))
    r.description = txt("기타소개")

    # 홈페이지: 링크 우선, 없으면 텍스트의 URL
    if "홈페이지" in cells:
        a = cells["홈페이지"].find("a", href=True)
        if a:
            r.official_url = strip_query(a["href"])
        elif (m := re.search(r"https?://\S+", txt("홈페이지"))):
            r.official_url = strip_query(m.group(0))

    # 도로명주소: '대회장' 셀 → 없으면 페이지 전체에서 best-effort
    src = txt("대회장") or soup.get_text(" ", strip=True)
    if (m := _ADDR_RE.search(src)):
        r.road_address = m.group(0).strip()

    return finalize(r)


# ---------------------------------------------------------------------------
# 저장
# ---------------------------------------------------------------------------
def save(records: list[Race], stem: str):
    rows = [asdict(r) for r in records]
    (OUTPUT_DIR / f"{stem}.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    if rows:
        # utf-8-sig: Excel/Numbers가 UTF-8로 인식하도록 BOM 추가
        with (OUTPUT_DIR / f"{stem}.csv").open("w", newline="", encoding="utf-8-sig") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            w.writeheader()
            for row in rows:
                row = {k: (json.dumps(v, ensure_ascii=False) if isinstance(v, list) else v)
                       for k, v in row.items()}
                w.writerow(row)
    log.info("저장: %s.json / %s.csv (%d건)", stem, stem, len(rows))


# ---------------------------------------------------------------------------
# 메인
# ---------------------------------------------------------------------------
def main():
    fetcher = PoliteFetcher()
    geocoder = Geocoder(KAKAO_REST_API_KEY)
    if not KAKAO_REST_API_KEY:
        log.warning("KAKAO_REST_API_KEY 비어 있음 → 좌표는 비워둠(나머지는 정상 수집)")

    mgo = crawl_marathongo(fetcher, geocoder, LIMIT_PER_SITE)
    mol = crawl_roadrun(fetcher, geocoder, LIMIT_PER_SITE)
    combined = mgo + mol
    save(combined, "races_sample")

    print("\n=== 수집 요약 ===")
    print(f"마라톤GO {len(mgo)}건, 마라톤온라인 {len(mol)}건, 합계 {len(combined)}건")
    for r in combined[:8]:
        coord = f"({r.latitude},{r.longitude})" if r.latitude else "(좌표없음)"
        print(f"- [{r.source}] {r.name} | {r.event_date} {r.start_time} | "
              f"{r.sido} {r.venue} | {r.event_types} {coord}")


if __name__ == "__main__":
    main()