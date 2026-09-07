#!/usr/bin/env python3
"""`seed_judge_account.py` 단위 테스트. 네트워크를 타지 않는다.

가짜 서버를 세워 요청 순서와 본문을 본다. 고르는 규칙(순수 함수)과 자격 증명이
새지 않는지가 핵심이다.
"""

from __future__ import annotations

import json
import unittest

from seed_judge_account import (
    Client,
    SeedError,
    course_payload,
    itinerary_request,
    mask_email,
    pick_contests,
    pick_routes,
    rows,
    seed,
    travel_period,
    verify,
)


class FakeResponse:
    def __init__(self, status: int, payload):
        self.code = status
        self._body = b"" if payload is None else json.dumps(payload).encode("utf-8")

    def read(self, _limit=None):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


class FakeOpener:
    """경로별 응답을 돌려주고 받은 요청을 전부 기록한다."""

    def __init__(self, routes: dict):
        self.routes = routes
        self.calls = []

    def open(self, request, timeout=None):
        path = request.full_url.split("://", 1)[-1].split("/", 1)[-1]
        path = "/" + path
        body = None
        if request.data:
            body = json.loads(request.data.decode("utf-8"))
        self.calls.append((request.get_method(), path, body,
                           dict(request.header_items())))
        key = (request.get_method(), path)
        if key not in self.routes:
            return FakeResponse(404, {"code": "NOT_FOUND", "status": 404})
        status, payload = self.routes[key]
        return FakeResponse(status, payload)


CONTEST_PAGE = {
    "items": [
        {"id": 11, "contestDate": "2026-10-10", "events": ["HALF", "K10"],
         "lat": 37.5, "lng": 127.0},
        {"id": 12, "contestDate": "2026-10-11", "events": [], "lat": None, "lng": None},
        {"id": 13, "contestDate": "2026-10-12", "events": ["FULL"],
         "lat": 35.1, "lng": 129.0},
        {"id": 14, "contestDate": "2026-10-13", "events": ["K5"],
         "lat": 36.3, "lng": 127.4},
        {"id": 15, "contestDate": "2026-10-14", "events": ["K10"],
         "lat": 37.4, "lng": 126.7},
    ],
}

NEAR_PAGE = {
    "items": [
        {"kind": "ROUTE", "routeId": "b", "name": "한강 코스", "distanceM": 900,
         "lat": 37.51, "lng": 126.99, "routeKm": 5.1, "durationMin": 40,
         "gainM": 12, "elevationProfileM": [1, 2], "pathPolyline": "aaa",
         "dataSource": "API_GPX", "difficulty": "EASY", "sido": "서울",
         "sourceCourseId": "DN-2"},
        {"kind": "SPOT", "name": "걷기 스팟", "distanceM": 100, "lat": 37.5,
         "lng": 126.98},
        {"kind": "ROUTE", "routeId": "a", "name": "남산 코스", "distanceM": 400,
         "lat": 37.55, "lng": 126.98, "routeKm": 4.2, "durationMin": 35,
         "gainM": 80, "elevationProfileM": [3], "pathPolyline": "bbb",
         "dataSource": "GPX_ONLY", "difficulty": "NORMAL", "sido": "서울",
         "sourceCourseId": "DN-1"},
        {"kind": "ROUTE", "routeId": "c", "name": "폴리라인 없음", "distanceM": 200,
         "lat": 37.52, "lng": 126.97, "pathPolyline": None},
    ],
}


def seeded_opener() -> FakeOpener:
    return FakeOpener({
        ("POST", "/api/auth/login"): (200, {"accessToken": "T0KEN"}),
        ("GET", "/api/contests?size=20"): (200, CONTEST_PAGE),
        ("GET", "/api/courses/near?lat=37.5663&lng=126.9779&targetKm=5&radiusKm=8"):
            (200, NEAR_PAGE),
        ("PUT", "/api/me/favorites/11"): (204, None),
        ("PUT", "/api/me/favorites/13"): (204, None),
        ("PUT", "/api/me/favorites/14"): (204, None),
        ("POST", "/api/me/courses"): (201, {"id": 77}),
        ("POST", "/api/itineraries/generate"): (200, {"contestId": 11, "days": []}),
        ("POST", "/api/itineraries"): (201, {"id": 42}),
        ("GET", "/api/me/favorites"): (200, {"items": [1, 2, 3]}),
        ("GET", "/api/me/courses"): (200, {"content": [1, 2]}),
        ("GET", "/api/itineraries"): (200, {"content": [1]}),
    })


class PickTest(unittest.TestCase):

    def test_좌표_없는_대회는_안_고른다(self):
        # 찜은 되는데 동선 생성이 409 인 대회가 표본에 섞이면, 심사위원이 그 대회에서
        # 위저드를 눌렀을 때 오류만 본다.
        chosen = pick_contests(CONTEST_PAGE["items"], 3)

        self.assertEqual([11, 13, 14], [item["id"] for item in chosen])

    def test_걷기_스팟과_폴리라인_없는_것은_저장하지_않는다(self):
        routes = pick_routes(NEAR_PAGE["items"], 3)

        self.assertEqual(["a", "b"], [route["routeId"] for route in routes])

    def test_대회일을_반드시_포함한다(self):
        # §5-1 위반이면 400 INVALID_TRAVEL_PERIOD 다.
        start, end = travel_period("2026-10-10")

        self.assertEqual(("2026-10-09", "2026-10-10"), (start, end))

    def test_그_대회에_있는_종목을_쓴다(self):
        body = itinerary_request(CONTEST_PAGE["items"][0])

        self.assertEqual("HALF", body["event"])
        self.assertEqual(["TOUR", "FOOD"], body["themes"])

    def test_종목이_없으면_기본값으로_간다(self):
        body = itinerary_request({"id": 9, "contestDate": "2026-10-10", "events": []})

        self.assertEqual("K10", body["event"])

    def test_저장_코스는_받은_값을_그대로_옮긴다(self):
        payload = course_payload(NEAR_PAGE["items"][0])

        self.assertEqual("한강 코스", payload["courseName"])
        self.assertEqual("aaa", payload["pathPolyline"])
        self.assertEqual(5.1, payload["distanceKm"])
        self.assertEqual([1, 2], payload["elevationProfileM"])

    def test_목록_행은_content_와_items_둘_다_읽는다(self):
        self.assertEqual([1], rows({"content": [1]}))
        self.assertEqual([2], rows({"items": [2]}))
        self.assertEqual([], rows(None))


class SeedTest(unittest.TestCase):

    def client(self, opener) -> Client:
        client = Client("https://example.test", opener=opener)
        client.token = "T0KEN"
        return client

    def test_찜_저장코스_동선을_모두_채운다(self):
        opener = seeded_opener()

        summary = seed(self.client(opener))

        self.assertEqual([11, 13, 14], summary["favorites"])
        self.assertEqual([77, 77], summary["savedCourseIds"])
        self.assertEqual(42, summary["itineraryId"])

    def test_생성_응답을_그대로_저장한다(self):
        # §5-2 는 "요청 = 5-1 응답 구조" 다. 다시 조립하면 서버가 필드를 늘렸을 때
        # 한쪽만 따라간다.
        opener = seeded_opener()

        seed(self.client(opener))

        saves = [body for method, path, body, _ in opener.calls
                 if (method, path) == ("POST", "/api/itineraries")]
        self.assertEqual([{"contestId": 11, "days": []}], saves)

    def test_dry_run_은_아무것도_쓰지_않는다(self):
        opener = seeded_opener()

        summary = seed(self.client(opener), dry_run=True)

        writes = [(method, path) for method, path, _, _ in opener.calls
                  if method in {"POST", "PUT", "DELETE", "PATCH"}]
        self.assertEqual([], writes)
        self.assertEqual([], summary["favorites"])

    def test_저장할_코스가_모자라면_경고로_남긴다(self):
        # 서울 반경 8km 안에 두루누비 코스가 사실상 없다. 조용히 넘기면 저장 코스
        # 화면이 왜 비었는지 아무도 모른다.
        opener = seeded_opener()
        opener.routes[("GET", "/api/courses/near?lat=37.5663&lng=126.9779"
                              "&targetKm=5&radiusKm=8")] = (200, {"items": []})

        summary = seed(self.client(opener))

        self.assertEqual(1, len(summary["warnings"]))
        self.assertIn("ROUTE 가 0건", summary["warnings"][0])

    def test_대회가_없으면_멈춘다(self):
        opener = seeded_opener()
        opener.routes[("GET", "/api/contests?size=20")] = (200, {"items": []})

        with self.assertRaises(SeedError):
            seed(self.client(opener))

    def test_토큰을_모든_요청에_싣는다(self):
        opener = seeded_opener()

        seed(self.client(opener))

        for _method, path, _body, headers in opener.calls:
            self.assertEqual("Bearer T0KEN", headers.get("Authorization"), path)

    def test_되읽어서_확인한다(self):
        opener = seeded_opener()

        counts = verify(self.client(opener))

        self.assertEqual({"favoriteCount": 3, "savedCourseCount": 2,
                          "itineraryCount": 1}, counts)


class SecretTest(unittest.TestCase):

    def test_이메일을_가린다(self):
        # 요약 JSON 은 이슈나 PR 에 붙는다. 주소가 그대로 남으면 안 된다(AGENTS 8장).
        self.assertEqual("ru***@example.com", mask_email("runninggu.judge@example.com"))
        self.assertEqual("a***@b.com", mask_email("a@b.com"))
        self.assertEqual("***", mask_email("도메인없음"))

    def test_실패_메시지에_본문을_담지_않는다(self):
        opener = FakeOpener({
            ("POST", "/api/me/courses"): (400, {"code": "VALIDATION_FAILED",
                                                "detail": "비밀이 섞인 본문"}),
        })
        client = Client("https://example.test", opener=opener)

        with self.assertRaises(SeedError) as caught:
            client.json("POST", "/api/me/courses", {"a": 1})

        self.assertIn("VALIDATION_FAILED", str(caught.exception))
        self.assertNotIn("비밀이 섞인 본문", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
