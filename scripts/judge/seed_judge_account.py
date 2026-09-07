#!/usr/bin/env python3
"""심사 계정에 저장 동선·찜한 대회·저장 코스 표본을 채운다. (이슈 #293 · #225 BLOCKER 8)

빈 계정으로 제출하면 로그인해야 보이는 화면 — 마이의 동선·찜·저장 코스 — 이 전부
빈 상태로 심사위원에게 보인다. P0 기능 절반이 "없는 것" 처럼 보이는 자리라, 계정을
만든 뒤 이 스크립트로 표본을 채운다.

계정 자체는 만들지 않는다. 이메일 인증 코드가 있어야 가입이 되므로(§1-1) 사람이
앱이나 콘솔에서 만들고, 여기서는 **이미 있는 계정으로 로그인해 채우기만** 한다.

세 번 돌려도 결과가 같다.
  찜        PUT /me/favorites/{id}      멱등
  저장 코스  POST /me/courses            geometry 같으면 기존 id (멱등)
  저장 동선  POST /itineraries           같은 (대회, 시작일, 종료일) 이면 교체

자격 증명은 환경변수로만 받는다. 저장소에 넣지 않고 로그에도 남기지 않는다
(AGENTS 8장) — 이메일 주소는 요약에서 가린다.

사용:
    export RUNNINGGU_API_BASE_URL=https://staging-api.runninggu.store
    export RUNNINGGU_JUDGE_EMAIL=...        # 로그에 안 남는다
    export RUNNINGGU_JUDGE_PASSWORD=...
    python seed_judge_account.py --out seed-summary.json
    python seed_judge_account.py --dry-run  # 로그인과 고르기까지만, 쓰기 없음
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, timedelta

MAX_BODY_BYTES = 4 * 1024 * 1024
TIMEOUT_SEC = 20

# 표본 크기. 화면이 "여러 개일 때 어떻게 보이나" 까지 심사위원이 볼 수 있어야 해서
# 한 건씩 두지 않는다. 목록·정렬은 한 건으로는 안 드러난다.
FAVORITE_COUNT = 3
SAVED_COURSE_COUNT = 3

# 저장 코스를 찾을 출발지. 서울시청 — 수도권은 두루누비 코스가 사실상 없어서
# 걷기 스팟이 기본 경험이다(AGENTS 6장). ROUTE 가 모자라면 그 사실이 그대로 보고된다.
COURSE_ORIGIN = {"lat": "37.5663", "lng": "126.9779", "targetKm": "5", "radiusKm": "8"}

# 동선 생성 표본. 테마는 §4.8 의 "1개 이상" 을 채우는 최소 조합이다.
ITINERARY_THEMES = ["TOUR", "FOOD"]
ITINERARY_EVENT_FALLBACK = "K10"


class SeedError(Exception):
    """되돌릴 수 없는 실패. 메시지에 자격 증명을 담지 않는다."""


def mask_email(email: str) -> str:
    """`ab***@example.com`. 요약 파일이 공유돼도 주소가 그대로 남지 않게 한다."""
    local, _, domain = email.partition("@")
    if not domain:
        return "***"
    head = local[:2] if len(local) > 2 else local[:1]
    return head + "***@" + domain


class Client:
    """토큰을 들고 다니는 최소 HTTP 클라이언트.

    `requests` 를 쓰지 않는다 — `scripts/api/` 가 이미 표준 라이브러리만 쓰고 있어서
    심사 계정 하나 채우자고 의존성을 늘리지 않는다.
    """

    def __init__(self, base_url: str, opener=None):
        self.base_url = base_url.rstrip("/")
        self.token: str | None = None
        self._opener = opener or urllib.request.build_opener()

    def request(self, method: str, path: str, body=None) -> tuple[int, object]:
        headers = {"Accept": "application/json", "User-Agent": "RunningGu-JudgeSeed/1"}
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        request = urllib.request.Request(
            self.base_url + path, data=data, headers=headers, method=method)
        try:
            try:
                response = self._opener.open(request, timeout=TIMEOUT_SEC)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                status = response.code
                raw = response.read(MAX_BODY_BYTES + 1)
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise SeedError(
                method + " " + path + " — 서버에 닿지 못했다 (" + type(error).__name__ + ")"
            ) from None
        if len(raw) > MAX_BODY_BYTES:
            raise SeedError(method + " " + path + " — 응답이 너무 크다")
        if not raw:
            return status, None
        try:
            return status, json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            raise SeedError(
                method + " " + path + " — JSON 이 아니다 (status " + str(status) + ")"
            ) from None

    def json(self, method: str, path: str, body=None, *, expect=(200, 201)) -> object:
        status, payload = self.request(method, path, body)
        if status not in expect:
            raise SeedError(
                method + " " + path + " — " + str(status) + " " + error_code(payload))
        return payload


def error_code(payload: object) -> str:
    """RFC 9457 problem+json 의 `code` 만 꺼낸다. 본문을 통째로 찍지 않는다."""
    if isinstance(payload, dict) and isinstance(payload.get("code"), str):
        return payload["code"]
    return ""


# ── 무엇을 채울지 고르는 부분 — 순수 함수라 서버 없이 테스트한다 ──────────────


def pick_contests(items: list, count: int) -> list:
    """찜할 대회를 고른다. 목록은 `(contestDate, id)` 오름차순으로 오므로 앞에서 자른다.

    **좌표가 없는 대회는 건너뛴다** — 동선 생성이 `409 CONTEST_LOCATION_UNAVAILABLE`
    이라(§5-1), 찜은 되는데 거기서 동선을 못 만드는 대회가 표본에 섞인다.
    """
    usable = [item for item in items
              if item.get("id") and item.get("lat") is not None and item.get("lng") is not None]
    return usable[:count]


def pick_routes(items: list, count: int) -> list:
    """저장할 코스를 고른다. 걷기 스팟(`SPOT`)은 저장 대상이 아니다 — 코스가 아니다.

    `pathPolyline` 이 없는 항목도 뺀다. 저장 요청의 필수 값이고, 없는 것을 빈
    문자열로 채우면 서버가 `400` 을 준다.
    """
    routes = [item for item in items
              if item.get("kind") == "ROUTE" and item.get("pathPolyline")]
    routes.sort(key=lambda item: (item.get("distanceM", 0), str(item.get("routeId", ""))))
    return routes[:count]


def course_payload(item: dict) -> dict:
    """`GET /courses/near` 항목 → `POST /me/courses` 본문. (§6-1 · §7-2)

    받은 값을 그대로 옮긴다. 여기서 값을 만들어 내면 심사 계정의 저장 코스만
    실제 카탈로그와 다른 것이 된다.
    """
    return {
        "sourceCourseId": item.get("sourceCourseId"),
        "dataSource": item.get("dataSource"),
        "courseName": item.get("name"),
        "region": item.get("sido"),
        "distanceKm": item.get("routeKm"),
        "durationMin": item.get("durationMin"),
        "difficulty": item.get("difficulty"),
        "gainM": item.get("gainM"),
        "elevationProfileM": item.get("elevationProfileM") or [],
        "entryLat": item.get("lat"),
        "entryLng": item.get("lng"),
        "pathPolyline": item.get("pathPolyline"),
    }


def travel_period(contest_date: str) -> tuple:
    """대회 전날 ~ 대회 당일. **대회일을 반드시 포함**해야 한다(§5-1).

    1박 2일로 둔다 — 당일치기면 회복일 블록이 안 나와서 §5.6 의 특징이 화면에
    안 드러나고, 길게 잡으면 심사위원이 훑을 것만 늘어난다.
    """
    day = date.fromisoformat(contest_date)
    return (day - timedelta(days=1)).isoformat(), day.isoformat()


def itinerary_request(contest: dict) -> dict:
    """`POST /itineraries/generate` 본문. (§5-1)

    종목은 그 대회에 실제로 있는 것 중 첫 번째를 쓴다. `event` 는 대회 종목에 없어도
    보낼 수 있지만(§4.8), 심사 표본에 없는 종목이 적히면 화면과 실제가 어긋나 보인다.
    """
    start, end = travel_period(contest["contestDate"])
    events = [event for event in (contest.get("events") or []) if event]
    return {
        "contestId": contest["id"],
        "startDate": start,
        "endDate": end,
        "event": events[0] if events else ITINERARY_EVENT_FALLBACK,
        "themes": list(ITINERARY_THEMES),
        "hotel": None,
    }


# ── 실제로 채우는 부분 ────────────────────────────────────────────────


def login(client: Client, email: str, password: str) -> None:
    payload = client.json("POST", "/api/auth/login", {"email": email, "password": password})
    token = payload.get("accessToken") if isinstance(payload, dict) else None
    if not isinstance(token, str) or not token:
        raise SeedError("로그인 응답에 accessToken 이 없다")
    client.token = token


def seed_favorites(client: Client, contests: list) -> list:
    done = []
    for contest in contests:
        client.json("PUT", "/api/me/favorites/" + str(contest["id"]),
                    expect=(200, 201, 204))
        done.append(contest["id"])
    return done


def seed_saved_courses(client: Client, routes: list) -> list:
    done = []
    for route in routes:
        payload = client.json("POST", "/api/me/courses", course_payload(route))
        if isinstance(payload, dict) and payload.get("id") is not None:
            done.append(payload["id"])
    return done


def seed_itinerary(client: Client, contest: dict):
    generated = client.json("POST", "/api/itineraries/generate", itinerary_request(contest))
    if not isinstance(generated, dict):
        raise SeedError("동선 생성 응답이 객체가 아니다")
    # §5-2 는 "요청 = 5-1 응답 구조" 다. 다시 조립하지 않고 받은 것을 그대로 보낸다.
    saved = client.json("POST", "/api/itineraries", generated)
    return saved.get("id") if isinstance(saved, dict) else None


def seed(client: Client, dry_run: bool = False) -> dict:
    contests = client.json("GET", "/api/contests?size=20")
    chosen = pick_contests(list(contests.get("items") or []), FAVORITE_COUNT)
    if not chosen:
        raise SeedError("좌표가 있는 대회가 목록에 없다 — 서버 데이터부터 확인해야 한다")

    query = urllib.parse.urlencode(COURSE_ORIGIN)
    near = client.json("GET", "/api/courses/near?" + query)
    routes = pick_routes(list(near.get("items") or []), SAVED_COURSE_COUNT)

    summary = {
        "dryRun": dry_run,
        "contestIds": [contest["id"] for contest in chosen],
        "routeCandidates": len(routes),
        "favorites": [],
        "savedCourseIds": [],
        "itineraryId": None,
        "warnings": [],
    }
    if len(routes) < SAVED_COURSE_COUNT:
        # 서울 반경 8km 안에 두루누비 코스가 사실상 없다(AGENTS 6장). 조용히 넘기면
        # 저장 코스 화면이 왜 비었는지 아무도 모른다.
        summary["warnings"].append(
            "저장할 ROUTE 가 " + str(len(routes)) + "건뿐이다 — "
            "출발지를 바꾸거나 코스 동기화를 확인한다")
    if dry_run:
        return summary

    summary["favorites"] = seed_favorites(client, chosen)
    summary["savedCourseIds"] = seed_saved_courses(client, routes)
    summary["itineraryId"] = seed_itinerary(client, chosen[0])
    return summary


def verify(client: Client) -> dict:
    """채운 뒤 목록으로 되읽는다. 쓰기가 200 을 준 것과 화면에 보이는 것은 다른 말이다."""
    favorites = client.json("GET", "/api/me/favorites")
    courses = client.json("GET", "/api/me/courses")
    itineraries = client.json("GET", "/api/itineraries")
    return {
        "favoriteCount": len(rows(favorites)),
        "savedCourseCount": len(rows(courses)),
        "itineraryCount": len(rows(itineraries)),
    }


def rows(payload: object) -> list:
    """목록 응답의 행. 개인 목록은 `content`, 공개 목록은 `items` 다 (§0-4)."""
    if not isinstance(payload, dict):
        return []
    for key in ("content", "items"):
        value = payload.get(key)
        if isinstance(value, list):
            return value
    return []


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=os.environ.get("RUNNINGGU_API_BASE_URL"))
    parser.add_argument("--dry-run", action="store_true",
                        help="로그인과 고르기까지만 하고 쓰지 않는다")
    parser.add_argument("--out", help="요약 JSON 을 쓸 경로")
    args = parser.parse_args()

    email = os.environ.get("RUNNINGGU_JUDGE_EMAIL", "")
    password = os.environ.get("RUNNINGGU_JUDGE_PASSWORD", "")
    if not args.base_url or not email or not password:
        print("RUNNINGGU_API_BASE_URL · RUNNINGGU_JUDGE_EMAIL · RUNNINGGU_JUDGE_PASSWORD "
              "가 모두 필요하다 (scripts/.env)", file=sys.stderr)
        return 2

    client = Client(args.base_url)
    try:
        login(client, email, password)
        summary = seed(client, dry_run=args.dry_run)
        if not args.dry_run:
            summary["verified"] = verify(client)
    except SeedError as error:
        print("실패: " + str(error), file=sys.stderr)
        return 1

    summary["account"] = mask_email(email)
    summary["baseUrl"] = args.base_url
    text = json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True)
    print(text)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
    for warning in summary["warnings"]:
        print("경고: " + warning, file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
