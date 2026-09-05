#!/usr/bin/env python3
"""승인된 staging 요청 세트로 5분 준비 + 30분 앱 API 부하를 실행한다.

비밀 입력은 터미널에서 echo 없이 받고 메모리에만 둔다. 응답 원문·토큰·이메일·좌표는
결과에 쓰지 않는다. 기본 실행은 검증만 하며 실제 HTTP는 --execute를 명시해야 한다.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import dataclasses
import datetime as dt
import getpass
import hashlib
import json
import math
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path
from typing import Callable

import plan_api_arrivals as arrivals


STAGING_ORIGIN = "https://staging-api.runninggu.store"
MAX_BODY_BYTES = 4 * 1024 * 1024
MAX_DISPATCH_DELAY_MS = 500.0
RUN_ID = re.compile(r"[A-Za-z0-9._-]{1,64}")
GROUP_LIMITS_MS = {
    "simple": (1_000, 3_000),
    "external": (3_000, 10_000),
    "heavy": (5_000, 15_000),
}
HEAVY_SCENARIOS = ("near_curated", "near_osm", "itinerary_generate")
CASE_GROUPS = {
    "contest_list": "simple",
    "contest_detail": "simple",
    "contest_closing": "simple",
    "contest_daily": "simple",
    "course_list": "simple",
    "course_regions": "simple",
    "near_curated": "heavy",
    "near_osm": "heavy",
    "festival": "external",
    "poi": "external",
    "geocode": "external",
    "itinerary_generate": "heavy",
    "me": "simple",
    "favorite_list": "simple",
    "favorite_add": "simple",
    "favorite_delete": "simple",
}
TOP_LEVEL_KEYS = {
    "schemaVersion",
    "approvalStatus",
    "environment",
    "origin",
    "inputs",
    "expectations",
}


class LoadError(Exception):
    """결과에 그대로 기록해도 비밀이 없는 고정 오류 코드다."""

    def __init__(self, code: str, duration_ms: float | None = None, response_bytes: int = 0):
        super().__init__(code)
        self.code = code
        self.duration_ms = duration_ms
        self.response_bytes = response_bytes


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


@dataclasses.dataclass
class AccountSession:
    label: str
    user_id: int
    access_token: str = dataclasses.field(repr=False)
    refresh_token: str = dataclasses.field(repr=False)


@dataclasses.dataclass(frozen=True)
class RequestSpec:
    case_id: str
    group: str
    method: str
    path: str
    expected_status: int
    body: dict | None = dataclasses.field(default=None, repr=False)
    account_label: str | None = None


@dataclasses.dataclass(frozen=True)
class Exchange:
    status: int
    payload: object | None
    duration_ms: float
    response_bytes: int


@dataclasses.dataclass(frozen=True)
class TaskResult:
    sequence: int
    phase: str
    case_id: str
    group: str
    success: bool
    duration_ms: float | None
    response_bytes: int
    error: str | None
    dispatch_delay_ms: float = 0.0
    dispatched: bool = True


def require(condition: bool, code: str = "fixture_contract") -> None:
    if not condition:
        raise LoadError(code)


def exact_keys(value: object, keys: set[str]) -> dict:
    require(isinstance(value, dict) and set(value) == keys)
    return value


def positive_int(value: object) -> bool:
    return type(value) is int and value > 0


def finite_number(value: object, low: float, high: float) -> bool:
    return (
        type(value) in (int, float)
        and math.isfinite(value)
        and low <= value <= high
    )


def canonical_sha256(value: object) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def validate_fixture(value: object, require_approved: bool = False) -> dict:
    root = exact_keys(value, TOP_LEVEL_KEYS)
    require(root["schemaVersion"] == 1)
    require(root["approvalStatus"] in {"CANDIDATE", "APPROVED"})
    if require_approved:
        require(root["approvalStatus"] == "APPROVED", "request_set_not_approved")
    require(root["environment"] == "staging")
    require(root["origin"] == STAGING_ORIGIN)

    inputs = exact_keys(
        root["inputs"],
        {
            "yearMonth",
            "contest",
            "courseList",
            "nearCurated",
            "nearOsm",
            "festival",
            "poi",
            "geocode",
            "itinerary",
            "favoriteContestId",
        },
    )
    require(isinstance(inputs["yearMonth"], str))
    require(re.fullmatch(r"[0-9]{4}-(0[1-9]|1[0-2])", inputs["yearMonth"]) is not None)

    contest = exact_keys(inputs["contest"], {"id", "date", "event"})
    require(positive_int(contest["id"]))
    contest_date = dt.date.fromisoformat(contest["date"])
    require(contest["event"] in {"K5", "K10", "HALF", "FULL"})

    course_list = exact_keys(inputs["courseList"], {"page", "size"})
    require(type(course_list["page"]) is int and course_list["page"] >= 0)
    require(type(course_list["size"]) is int and 1 <= course_list["size"] <= 50)

    for name in ("nearCurated", "nearOsm"):
        near = exact_keys(
            inputs[name],
            {"lat", "lng", "targetKm", "radiusKm", "size"},
        )
        require(finite_number(near["lat"], -90, 90))
        require(finite_number(near["lng"], -180, 180))
        require(finite_number(near["targetKm"], 1, 21))
        require((float(near["targetKm"]) * 2).is_integer())
        require(finite_number(near["radiusKm"], 0.1, 100))
        require(type(near["size"]) is int and 1 <= near["size"] <= 12)

    festival = exact_keys(inputs["festival"], {"yearMonth", "size"})
    require(festival["yearMonth"] == inputs["yearMonth"])
    require(type(festival["size"]) is int and 1 <= festival["size"] <= 20)

    poi = exact_keys(inputs["poi"], {"category", "lat", "lng", "radius", "size"})
    require(poi["category"] in {"TOUR", "FOOD", "CAFE", "WELLNESS", "NATURE", "HISTORY", "LODGING"})
    require(finite_number(poi["lat"], -90, 90))
    require(finite_number(poi["lng"], -180, 180))
    require(type(poi["radius"]) is int and 1 <= poi["radius"] <= 20_000)
    require(type(poi["size"]) is int and 1 <= poi["size"] <= 20)

    geocode = exact_keys(inputs["geocode"], {"query"})
    require(isinstance(geocode["query"], str) and len(geocode["query"].strip()) >= 2)

    itinerary = exact_keys(
        inputs["itinerary"],
        {"contestId", "startDate", "endDate", "event", "themes", "hotel"},
    )
    start_date = dt.date.fromisoformat(itinerary["startDate"])
    end_date = dt.date.fromisoformat(itinerary["endDate"])
    require(itinerary["contestId"] == contest["id"])
    require(itinerary["event"] == contest["event"])
    require(start_date <= contest_date <= end_date)
    require(1 <= (end_date - start_date).days + 1 <= 7)
    require(
        isinstance(itinerary["themes"], list)
        and bool(itinerary["themes"])
        and len(itinerary["themes"]) == len(set(itinerary["themes"]))
        and all(theme in {"TOUR", "FOOD", "CAFE", "WELLNESS", "NATURE", "HISTORY", "LODGING"}
                for theme in itinerary["themes"])
    )
    require(itinerary["hotel"] is None)
    require(inputs["favoriteContestId"] == contest["id"])

    expectations = exact_keys(
        root["expectations"],
        {
            "contestActive",
            "closingSoonNonEmpty",
            "dailyCountsNonEmpty",
            "courseListNonEmpty",
            "nearCuratedRouteSources",
            "nearOsmRouteSource",
            "festivalNonEmpty",
            "poiSource",
            "poiNonEmpty",
            "itineraryDayCount",
            "itineraryRaceBlockCount",
        },
    )
    for key in (
        "contestActive",
        "closingSoonNonEmpty",
        "dailyCountsNonEmpty",
        "courseListNonEmpty",
        "festivalNonEmpty",
        "poiNonEmpty",
    ):
        require(expectations[key] is True)
    require(
        isinstance(expectations["nearCuratedRouteSources"], list)
        and bool(expectations["nearCuratedRouteSources"])
        and set(expectations["nearCuratedRouteSources"]) <= {"API_GPX", "GPX_ONLY"}
    )
    require(expectations["nearOsmRouteSource"] == "OSM_GENERATED")
    require(expectations["poiSource"] == "LIVE")
    require(positive_int(expectations["itineraryDayCount"]))
    require(positive_int(expectations["itineraryRaceBlockCount"]))
    return root


def load_fixture(path: Path, require_approved: bool = False) -> tuple[dict, str]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        fixture = validate_fixture(value, require_approved=require_approved)
        return fixture, canonical_sha256(fixture)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError, TypeError):
        raise LoadError("fixture_contract") from None


def query_path(path: str, values: dict) -> str:
    return path + "?" + urllib.parse.urlencode(values)


def account_for_item(item: dict) -> str:
    return "A" if item["plannedOffsetMs"] // 60_000 % 2 == 0 else "B"


def request_spec(case_id: str, fixture: dict, account_label: str | None = None) -> RequestSpec:
    inputs = fixture["inputs"]
    contest = inputs["contest"]
    if case_id == "contest_list":
        return RequestSpec(case_id, "simple", "GET", "/api/contests?size=20", 200)
    if case_id == "contest_detail":
        return RequestSpec(case_id, "simple", "GET", f"/api/contests/{contest['id']}", 200)
    if case_id == "contest_closing":
        return RequestSpec(case_id, "simple", "GET", "/api/contests/closing-soon?limit=4", 200)
    if case_id == "contest_daily":
        year, month = inputs["yearMonth"].split("-")
        return RequestSpec(case_id, "simple", "GET", f"/api/contests/daily-counts?year={year}&month={int(month)}", 200)
    if case_id == "course_list":
        return RequestSpec(case_id, "simple", "GET", query_path("/api/courses", inputs["courseList"]), 200)
    if case_id == "course_regions":
        return RequestSpec(case_id, "simple", "GET", "/api/courses/regions", 200)
    if case_id in {"near_curated", "near_osm"}:
        source = inputs["nearCurated" if case_id == "near_curated" else "nearOsm"]
        return RequestSpec(case_id, "heavy", "GET", query_path("/api/courses/near", source), 200)
    if case_id == "festival":
        return RequestSpec(case_id, "external", "GET", query_path("/api/festivals", inputs["festival"]), 200)
    if case_id == "poi":
        return RequestSpec(case_id, "external", "GET", query_path("/api/pois", inputs["poi"]), 200)
    if case_id == "geocode":
        return RequestSpec(case_id, "external", "GET", query_path("/api/geocode", inputs["geocode"]), 200)
    if case_id == "itinerary_generate":
        return RequestSpec(case_id, "heavy", "POST", "/api/itineraries/generate", 200, inputs["itinerary"])
    require(account_label in {"A", "B"}, "account_contract")
    if case_id == "me":
        return RequestSpec(case_id, "simple", "GET", "/api/me", 200, account_label=account_label)
    if case_id == "favorite_list":
        return RequestSpec(case_id, "simple", "GET", "/api/me/favorites?page=0&size=20", 200, account_label=account_label)
    favorite_path = f"/api/me/favorites/{inputs['favoriteContestId']}"
    if case_id == "favorite_add":
        return RequestSpec(case_id, "simple", "PUT", favorite_path, 204, account_label=account_label)
    if case_id == "favorite_delete":
        return RequestSpec(case_id, "simple", "DELETE", favorite_path, 204, account_label=account_label)
    raise LoadError("unknown_case")


class HttpClient:
    def __init__(self):
        self._thread_local = threading.local()
        self.network_started = False

    def opener(self):
        opener = getattr(self._thread_local, "opener", None)
        if opener is None:
            opener = urllib.request.build_opener(NoRedirect())
            self._thread_local.opener = opener
        return opener

    def exchange(
        self,
        spec: RequestSpec,
        access_token: str | None = None,
        before_send: Callable[[], None] | None = None,
    ) -> Exchange:
        headers = {
            "Accept": "application/json",
            "User-Agent": "RunningGu-ApiLoad/1",
        }
        body = None
        if spec.body is not None:
            body = json.dumps(spec.body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if access_token is not None:
            headers["Authorization"] = "Bearer " + access_token
        request = urllib.request.Request(
            STAGING_ORIGIN + spec.path,
            data=body,
            headers=headers,
            method=spec.method,
        )
        opener = self.opener()
        try:
            if before_send is not None:
                before_send()
            started = time.perf_counter_ns()
            self.network_started = True
            try:
                response = opener.open(request, timeout=20)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                status = response.code
                raw = response.read(MAX_BODY_BYTES + 1)
                content_type = response.headers.get_content_type()
            duration_ms = (time.perf_counter_ns() - started) / 1_000_000
            if len(raw) > MAX_BODY_BYTES:
                raise LoadError("body_too_large", duration_ms, len(raw))
            if not raw:
                payload = None
            else:
                if content_type not in {"application/json", "application/problem+json"}:
                    raise LoadError("content_type", duration_ms, len(raw))
                try:
                    payload = json.loads(raw)
                except (UnicodeError, ValueError):
                    raise LoadError("invalid_json", duration_ms, len(raw)) from None
            return Exchange(status, payload, duration_ms, len(raw))
        except LoadError:
            raise
        except (urllib.error.URLError, TimeoutError, OSError):
            duration_ms = (time.perf_counter_ns() - started) / 1_000_000
            raise LoadError("transport", duration_ms) from None


def object_list(payload: object, key: str, nonempty: bool = True) -> list[dict]:
    require(isinstance(payload, dict), "response_contract")
    values = payload.get(key)
    require(isinstance(values, list) and all(isinstance(value, dict) for value in values), "response_contract")
    if nonempty:
        require(bool(values), "empty_fixture")
    return values


def validate_exchange(spec: RequestSpec, exchange: Exchange, fixture: dict, account: AccountSession | None) -> None:
    require(exchange.status == spec.expected_status, "unexpected_http")
    if spec.expected_status == 204:
        require(exchange.payload is None, "response_contract")
        return

    payload = exchange.payload
    inputs = fixture["inputs"]
    expected = fixture["expectations"]
    case_id = spec.case_id
    require(isinstance(payload, dict), "response_contract")
    if case_id == "contest_list":
        items = object_list(payload, "items")
        require(all(positive_int(item.get("id")) and item.get("active") is True for item in items), "response_contract")
    elif case_id == "contest_detail":
        contest = inputs["contest"]
        require(payload.get("id") == contest["id"] and payload.get("contestDate") == contest["date"], "response_contract")
        require(payload.get("active") is expected["contestActive"], "response_contract")
        require(contest["event"] in (payload.get("events") or []), "response_contract")
        require(finite_number(payload.get("lat"), -90, 90) and finite_number(payload.get("lng"), -180, 180), "response_contract")
    elif case_id == "contest_closing":
        items = object_list(payload, "items", expected["closingSoonNonEmpty"])
        require(len(items) <= 4 and all(item.get("regStatus") == "OPEN" for item in items), "response_contract")
    elif case_id == "contest_daily":
        counts = object_list(payload, "counts", expected["dailyCountsNonEmpty"])
        require(all(positive_int(row.get("count")) and str(row.get("date", "")).startswith(inputs["yearMonth"] + "-") for row in counts), "response_contract")
    elif case_id == "course_list":
        content = object_list(payload, "content", expected["courseListNonEmpty"])
        require(all(row.get("dataSource") in {"API_GPX", "GPX_ONLY"} for row in content), "response_contract")
        require(isinstance(payload.get("page"), dict), "response_contract")
    elif case_id == "course_regions":
        items = object_list(payload, "items")
        require(all(isinstance(row.get("region"), str) and positive_int(row.get("count")) for row in items), "response_contract")
    elif case_id in {"near_curated", "near_osm"}:
        items = object_list(payload, "items")
        routes = [row for row in items if row.get("kind") == "ROUTE"]
        require(bool(routes), "empty_fixture")
        if case_id == "near_curated":
            require(any(row.get("dataSource") in expected["nearCuratedRouteSources"] for row in routes), "response_contract")
            require(all(row.get("dataSource") != "OSM_GENERATED" for row in routes), "response_contract")
        else:
            require(any(row.get("dataSource") == expected["nearOsmRouteSource"] for row in routes), "response_contract")
        require(payload.get("degradedSources") == [], "degraded_response")
    elif case_id == "festival":
        items = object_list(payload, "items", expected["festivalNonEmpty"])
        require(all(isinstance(row.get("contentId"), str) and bool(row["contentId"]) for row in items), "response_contract")
    elif case_id == "poi":
        items = object_list(payload, "items", expected["poiNonEmpty"])
        require(payload.get("source") == expected["poiSource"], "response_contract")
        require(all(row.get("provider") in {"KAKAO", "KTO"} and isinstance(row.get("name"), str) and bool(row["name"]) for row in items), "response_contract")
    elif case_id == "geocode":
        require(isinstance(payload.get("name"), str) and bool(payload["name"]), "response_contract")
        require(isinstance(payload.get("address"), str) and bool(payload["address"]), "response_contract")
        require(finite_number(payload.get("lat"), -90, 90) and finite_number(payload.get("lng"), -180, 180), "response_contract")
    elif case_id == "itinerary_generate":
        itinerary = inputs["itinerary"]
        require(payload.get("contestId") == itinerary["contestId"] and payload.get("event") == itinerary["event"], "response_contract")
        require(payload.get("startDate") == itinerary["startDate"] and payload.get("endDate") == itinerary["endDate"], "response_contract")
        days = object_list(payload, "days")
        require(len(days) == expected["itineraryDayCount"], "response_contract")
        blocks = [block for day in days for block in (day.get("blocks") or []) if isinstance(block, dict)]
        require(sum(block.get("blockType") == "RACE" for block in blocks) == expected["itineraryRaceBlockCount"], "response_contract")
    elif case_id == "me":
        require(account is not None and payload.get("id") == account.user_id, "account_isolation")
    elif case_id == "favorite_list":
        object_list(payload, "content", nonempty=False)
        require(isinstance(payload.get("page"), dict), "response_contract")
    else:
        raise LoadError("unknown_case")


def session_token(spec: RequestSpec, sessions: dict[str, AccountSession]) -> str | None:
    return None if spec.account_label is None else sessions[spec.account_label].access_token


def perform(spec: RequestSpec, client: HttpClient, fixture: dict, sessions: dict[str, AccountSession]) -> Exchange:
    account = sessions.get(spec.account_label) if spec.account_label else None
    exchange = client.exchange(spec, session_token(spec, sessions))
    validate_exchange(spec, exchange, fixture, account)
    return exchange


def login_sessions(
    client: HttpClient,
    secret_prompt: Callable[[str], str] = getpass.getpass,
) -> dict[str, AccountSession]:
    sessions = {}
    try:
        for label in ("A", "B"):
            email = secret_prompt(f"테스트 계정 {label} 이메일(숨김 입력): ")
            password = secret_prompt(f"테스트 계정 {label} 비밀번호(숨김 입력): ")
            require(bool(email) and bool(password), "authentication_input")
            spec = RequestSpec(
                "auth_login",
                "maintenance",
                "POST",
                "/api/auth/login",
                200,
                {"email": email, "password": password},
            )
            exchange = client.exchange(spec)
            email = password = ""
            require(exchange.status == 200 and isinstance(exchange.payload, dict), "authentication_failed")
            payload = exchange.payload
            user = payload.get("user")
            require(isinstance(user, dict) and positive_int(user.get("id")), "authentication_failed")
            require(isinstance(payload.get("accessToken"), str) and bool(payload["accessToken"]), "authentication_failed")
            require(isinstance(payload.get("refreshToken"), str) and bool(payload["refreshToken"]), "authentication_failed")
            sessions[label] = AccountSession(label, user["id"], payload["accessToken"], payload["refreshToken"])
        require(sessions["A"].user_id != sessions["B"].user_id, "account_isolation")
    except (Exception, KeyboardInterrupt):
        for session in sessions.values():
            logout_session(client, session)
        raise
    return sessions


def refresh_session(client: HttpClient, session: AccountSession) -> float:
    spec = RequestSpec(
        "auth_refresh",
        "maintenance",
        "POST",
        "/api/auth/refresh",
        200,
        {"refreshToken": session.refresh_token},
    )
    exchange = client.exchange(spec)
    require(exchange.status == 200 and isinstance(exchange.payload, dict), "refresh_failed")
    access = exchange.payload.get("accessToken")
    refresh = exchange.payload.get("refreshToken")
    require(isinstance(access, str) and bool(access) and isinstance(refresh, str) and bool(refresh), "refresh_failed")
    session.access_token = access
    session.refresh_token = refresh
    return exchange.duration_ms


def logout_session(client: HttpClient, session: AccountSession) -> bool:
    try:
        spec = RequestSpec(
            "auth_logout",
            "maintenance",
            "POST",
            "/api/auth/logout",
            204,
            {"refreshToken": session.refresh_token},
        )
        exchange = client.exchange(spec)
        return exchange.status == 204
    except Exception:
        return False


def favorite_ids(payload: object) -> set[int]:
    content = object_list(payload, "content", nonempty=False)
    values = set()
    for row in content:
        require(positive_int(row.get("id")), "response_contract")
        values.add(row["id"])
    return values


def verify_account_isolation(client: HttpClient, fixture: dict, sessions: dict[str, AccountSession]) -> None:
    target = fixture["inputs"]["favoriteContestId"]

    def change(label: str, method: str) -> None:
        case_id = "favorite_add" if method == "PUT" else "favorite_delete"
        perform(request_spec(case_id, fixture, label), client, fixture, sessions)

    def listed(label: str) -> set[int]:
        spec = request_spec("favorite_list", fixture, label)
        exchange = perform(spec, client, fixture, sessions)
        return favorite_ids(exchange.payload)

    for label in ("A", "B"):
        change(label, "DELETE")
    change("A", "PUT")
    require(target in listed("A") and target not in listed("B"), "account_isolation")
    change("A", "DELETE")
    change("B", "PUT")
    require(target in listed("B") and target not in listed("A"), "account_isolation")
    change("B", "DELETE")
    require(target not in listed("A") and target not in listed("B"), "account_isolation")


def cleanup_favorites(client: HttpClient, fixture: dict, sessions: dict[str, AccountSession]) -> bool:
    success = True
    for label in ("A", "B"):
        try:
            perform(request_spec("favorite_delete", fixture, label), client, fixture, sessions)
        except Exception:
            success = False
    return success


def preflight(client: HttpClient, fixture: dict, sessions: dict[str, AccountSession]) -> list[dict]:
    results = []
    for case_id in CASE_GROUPS:
        if case_id in {"favorite_add", "favorite_delete"}:
            continue
        account_label = "A" if case_id in {"me", "favorite_list"} else None
        spec = request_spec(case_id, fixture, account_label)
        exchange = perform(spec, client, fixture, sessions)
        results.append({"caseId": case_id, "durationMs": round(exchange.duration_ms, 3)})
    verify_account_isolation(client, fixture, sessions)
    return results


def percentile(values: list[float], percent: int) -> float:
    require(bool(values), "metrics_contract")
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * percent / 100) - 1)
    return ordered[index]


def execute_task(
    item: dict,
    spec: RequestSpec,
    client: HttpClient,
    fixture: dict,
    sessions: dict[str, AccountSession],
    semaphore: threading.BoundedSemaphore,
    *,
    planned_start: float | None = None,
    monotonic: Callable[[], float] = time.monotonic,
) -> TaskResult:
    exchange = None
    dispatch_delay_ms = 0.0
    dispatched = planned_start is None

    def measure_dispatch_delay() -> None:
        nonlocal dispatch_delay_ms, dispatched
        if planned_start is None:
            return
        dispatched = False
        dispatch_delay_ms = round(
            max(0.0, (monotonic() - planned_start) * 1000),
            3,
        )
        if dispatch_delay_ms > MAX_DISPATCH_DELAY_MS:
            raise LoadError("missed_start")
        dispatched = True

    try:
        account = sessions.get(spec.account_label) if spec.account_label else None
        access_token = session_token(spec, sessions)
        if planned_start is None:
            exchange = client.exchange(spec, access_token)
        else:
            exchange = client.exchange(
                spec,
                access_token,
                before_send=measure_dispatch_delay,
            )
        validate_exchange(spec, exchange, fixture, account)
        return TaskResult(
            item["sequence"], item["phase"], spec.case_id, spec.group, True,
            exchange.duration_ms, exchange.response_bytes, None,
            dispatch_delay_ms, dispatched,
        )
    except LoadError as error:
        return TaskResult(
            item["sequence"], item["phase"], spec.case_id, spec.group, False,
            exchange.duration_ms if exchange is not None else error.duration_ms,
            exchange.response_bytes if exchange is not None else error.response_bytes,
            error.code,
            dispatch_delay_ms, dispatched,
        )
    except Exception:
        return TaskResult(
            item["sequence"], item["phase"], spec.case_id, spec.group, False,
            None, 0, "internal_runner", dispatch_delay_ms, dispatched,
        )
    finally:
        semaphore.release()


def execute_schedule(
    client: HttpClient,
    fixture: dict,
    sessions: dict[str, AccountSession],
    schedule: list[dict],
    *,
    monotonic: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
    refresh_at_seconds: float | None = 1_200.5,
) -> tuple[list[TaskResult], list[dict], int]:
    semaphore = threading.BoundedSemaphore(arrivals.MAX_IN_FLIGHT)
    stop = threading.Event()
    results: list[TaskResult] = []
    maintenance: list[dict] = []
    results_lock = threading.Lock()
    futures: dict[int, concurrent.futures.Future] = {}
    start = monotonic() + 1.0

    def refresh_worker() -> None:
        if refresh_at_seconds is None:
            return
        delay = start + refresh_at_seconds - monotonic()
        if delay > 0:
            sleep(delay)
        for label in ("A", "B"):
            if stop.is_set():
                return
            semaphore.acquire()
            try:
                if stop.is_set():
                    return
                duration = refresh_session(client, sessions[label])
                with results_lock:
                    maintenance.append({"operation": "refresh", "account": label, "success": True, "durationMs": round(duration, 3)})
            except LoadError as error:
                with results_lock:
                    maintenance.append({"operation": "refresh", "account": label, "success": False, "error": error.code})
                stop.set()
                return
            except Exception:
                with results_lock:
                    maintenance.append({"operation": "refresh", "account": label, "success": False, "error": "internal_runner"})
                stop.set()
                return
            finally:
                semaphore.release()

    refresh_thread = threading.Thread(target=refresh_worker, name="api-load-refresh", daemon=True)
    refresh_thread.start()
    with concurrent.futures.ThreadPoolExecutor(max_workers=arrivals.MAX_IN_FLIGHT) as executor:
        for item in schedule:
            if stop.is_set():
                break
            target = start + item["plannedOffsetMs"] / 1000
            delay = target - monotonic()
            if delay > 0:
                sleep(delay)
            if stop.is_set():
                break

            dependency = item.get("dependsOnSequence")
            if dependency is not None:
                dependency_future = futures.get(dependency)
                if dependency_future is None or not dependency_future.done():
                    with results_lock:
                        results.append(TaskResult(
                            item["sequence"], item["phase"], item["caseId"],
                            CASE_GROUPS[item["caseId"]], False, None, 0,
                            "dependency_not_complete", 0.0, False,
                        ))
                    stop.set()
                    break
                dependency_result = dependency_future.result()
                if not dependency_result.success:
                    with results_lock:
                        results.append(TaskResult(
                            item["sequence"], item["phase"], item["caseId"],
                            CASE_GROUPS[item["caseId"]], False, None, 0,
                            "dependency_failed", 0.0, False,
                        ))
                    stop.set()
                    break

            if not semaphore.acquire(blocking=False):
                dispatch_delay_ms = round(
                    max(0.0, (monotonic() - target) * 1000),
                    3,
                )
                with results_lock:
                    results.append(TaskResult(
                        item["sequence"], item["phase"], item["caseId"],
                        CASE_GROUPS[item["caseId"]], False, None, 0,
                        "missed_start", dispatch_delay_ms, False,
                    ))
                stop.set()
                break

            dispatch_delay_ms = round(
                max(0.0, (monotonic() - target) * 1000),
                3,
            )
            if dispatch_delay_ms > MAX_DISPATCH_DELAY_MS:
                semaphore.release()
                with results_lock:
                    results.append(TaskResult(
                        item["sequence"], item["phase"], item["caseId"],
                        CASE_GROUPS[item["caseId"]], False, None, 0,
                        "missed_start", dispatch_delay_ms, False,
                    ))
                stop.set()
                break

            account_label = account_for_item(item) if item["caseId"] in {"me", "favorite_list", "favorite_add", "favorite_delete"} else None
            spec = request_spec(item["caseId"], fixture, account_label)
            future = executor.submit(
                execute_task,
                item,
                spec,
                client,
                fixture,
                sessions,
                semaphore,
                planned_start=target,
                monotonic=monotonic,
            )
            futures[item["sequence"]] = future

            def capture(completed: concurrent.futures.Future) -> None:
                result = completed.result()
                if not result.success:
                    stop.set()

            future.add_done_callback(capture)

        for future in futures.values():
            results.append(future.result())
    stop.set()
    refresh_thread.join(timeout=5)
    sorted_results = sorted(results, key=lambda row: row.sequence)
    dispatched = sum(row.dispatched for row in sorted_results)
    return sorted_results, maintenance, dispatched


def summarize_run(
    run_id: str,
    fixture_hash: str,
    schedule: list[dict],
    results: list[TaskResult],
    maintenance: list[dict],
    dispatched: int,
    preflight_results: list[dict],
    cleanup_succeeded: bool,
    logout_succeeded: bool,
    cpu_seconds: float,
    wall_seconds: float,
) -> dict:
    measurement = [row for row in results if row.phase == "measurement"]
    phases = []
    for phase in ("warmup", "measurement"):
        rows = [row for row in results if row.phase == phase]
        successful = sum(row.success for row in rows)
        phases.append({
            "phase": phase,
            "planned": sum(item["phase"] == phase for item in schedule),
            "completed": len(rows),
            "successful": successful,
            "failed": len(rows) - successful,
        })
    groups = []
    threshold_failures = 0
    for group, (p95_limit, max_limit) in GROUP_LIMITS_MS.items():
        rows = [row for row in measurement if row.group == group]
        durations = [row.duration_ms for row in rows if row.duration_ms is not None]
        if durations:
            p50 = percentile(durations, 50)
            p95 = percentile(durations, 95)
            maximum = max(durations)
            passed = p95 <= p95_limit and maximum <= max_limit
        else:
            p50 = p95 = maximum = None
            passed = False
        if not passed:
            threshold_failures += 1
        successful = sum(row.success for row in rows)
        groups.append({
            "group": group,
            "planned": sum(CASE_GROUPS[item["caseId"]] == group and item["phase"] == "measurement" for item in schedule),
            "completed": len(rows),
            "successful": successful,
            "failed": len(rows) - successful,
            "p50Ms": None if p50 is None else round(p50, 3),
            "p95Ms": None if p95 is None else round(p95, 3),
            "maxMs": None if maximum is None else round(maximum, 3),
            "p95LimitMs": p95_limit,
            "maxLimitMs": max_limit,
            "passed": passed,
        })

    heavy_scenarios = []
    p95_limit, max_limit = GROUP_LIMITS_MS["heavy"]
    for case_id in HEAVY_SCENARIOS:
        rows = [row for row in measurement if row.case_id == case_id]
        durations = [row.duration_ms for row in rows if row.duration_ms is not None]
        if durations:
            p50 = percentile(durations, 50)
            p95 = percentile(durations, 95)
            maximum = max(durations)
            passed = p95 <= p95_limit and maximum <= max_limit
        else:
            p50 = p95 = maximum = None
            passed = False
        if not passed:
            threshold_failures += 1
        successful = sum(row.success for row in rows)
        heavy_scenarios.append({
            "caseId": case_id,
            "planned": sum(
                item["caseId"] == case_id and item["phase"] == "measurement"
                for item in schedule
            ),
            "completed": len(rows),
            "successful": successful,
            "failed": len(rows) - successful,
            "p50Ms": None if p50 is None else round(p50, 3),
            "p95Ms": None if p95 is None else round(p95, 3),
            "maxMs": None if maximum is None else round(maximum, 3),
            "p95LimitMs": p95_limit,
            "maxLimitMs": max_limit,
            "passed": passed,
        })

    failures = Counter(row.error for row in results if row.error is not None)
    max_dispatch_delay_ms = max(
        (row.dispatch_delay_ms for row in results),
        default=0.0,
    )
    late_dispatches = sum(
        row.dispatch_delay_ms > MAX_DISPATCH_DELAY_MS
        for row in results
    )
    all_scheduled = len(results) == len(schedule)
    all_success = all(row.success for row in results) and all_scheduled
    maintenance_pairs = Counter(
        (row.get("operation"), row.get("account"))
        for row in maintenance
        if row.get("success") is True
    )
    expected_preflight = Counter(
        case_id for case_id in CASE_GROUPS
        if case_id not in {"favorite_add", "favorite_delete"}
    )
    actual_preflight = Counter(row.get("caseId") for row in preflight_results)
    maintenance_success = (
        len(maintenance) == 4
        and maintenance_pairs
        == Counter({
            ("refresh_before_load", "A"): 1,
            ("refresh_before_load", "B"): 1,
            ("refresh", "A"): 1,
            ("refresh", "B"): 1,
        })
    )
    passed = (
        all_success
        and dispatched == len(schedule)
        and late_dispatches == 0
        and threshold_failures == 0
        and actual_preflight == expected_preflight
        and maintenance_success
        and cleanup_succeeded
        and logout_succeeded
    )
    return {
        "runId": run_id,
        "requestSetSha256": fixture_hash,
        "scheduleSha256": arrivals.schedule_sha256(schedule),
        "plannedRequests": len(schedule),
        "dispatchedRequests": dispatched,
        "completedResults": len(results),
        "successfulRequests": sum(row.success for row in results),
        "measurementSuccessful": sum(row.success for row in measurement),
        "missedStarts": failures.get("missed_start", 0),
        "dispatchDelayLimitMs": MAX_DISPATCH_DELAY_MS,
        "maxDispatchDelayMs": round(max_dispatch_delay_ms, 3),
        "lateDispatches": late_dispatches,
        "failureClasses": dict(sorted(failures.items())),
        "degradedResponses": failures.get("degraded_response", 0),
        "emptyResponses": failures.get("empty_fixture", 0),
        "responseBytes": sum(row.response_bytes for row in results),
        "preflight": preflight_results,
        "maintenance": maintenance,
        "phases": phases,
        "groups": groups,
        "heavyScenarios": heavy_scenarios,
        "cleanupSucceeded": cleanup_succeeded,
        "logoutSucceeded": logout_succeeded,
        "processCpuSeconds": round(cpu_seconds, 3),
        "processCpuPercentOfOneCore": round(
            0.0 if wall_seconds <= 0 else cpu_seconds / wall_seconds * 100,
            3,
        ),
        "wallSeconds": round(wall_seconds, 3),
        "passed": passed,
    }


def dry_run_summary(fixture: dict, fixture_hash: str) -> dict:
    schedule = arrivals.build_schedule()
    return {
        "mode": "validation_only",
        "approvalStatus": fixture["approvalStatus"],
        "requestSetSha256": fixture_hash,
        "scheduleSha256": arrivals.schedule_sha256(schedule),
        "plannedRequests": len(schedule),
        "requestsPerMinute": 60,
        "maxInFlight": arrivals.MAX_IN_FLIGHT,
        "dispatchDelayLimitMs": MAX_DISPATCH_DELAY_MS,
        "loadExecuted": False,
        "readyForLoad": fixture["approvalStatus"] == "APPROVED",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--run-id")
    args = parser.parse_args()
    client = None
    try:
        fixture, fixture_hash = load_fixture(args.fixture, require_approved=args.execute)
        if not args.execute:
            print(json.dumps(dry_run_summary(fixture, fixture_hash), ensure_ascii=False, sort_keys=True))
            return 0
        require(args.run_id is not None and RUN_ID.fullmatch(args.run_id) is not None, "run_id_contract")

        client = HttpClient()
        sessions = login_sessions(client)
        schedule = arrivals.build_schedule()
        cleanup_succeeded = False
        logout_succeeded = False
        started_wall = time.monotonic()
        started_cpu = time.process_time()
        preflight_results: list[dict] = []
        results: list[TaskResult] = []
        maintenance: list[dict] = []
        dispatched = 0
        try:
            preflight_results = preflight(client, fixture, sessions)
            for label in ("A", "B"):
                duration = refresh_session(client, sessions[label])
                maintenance.append({"operation": "refresh_before_load", "account": label, "success": True, "durationMs": round(duration, 3)})
            results, scheduled_maintenance, dispatched = execute_schedule(
                client, fixture, sessions, schedule
            )
            maintenance.extend(scheduled_maintenance)
        finally:
            cleanup_succeeded = cleanup_favorites(client, fixture, sessions)
            logout_results = [logout_session(client, session) for session in sessions.values()]
            logout_succeeded = all(logout_results)
        summary = summarize_run(
            args.run_id,
            fixture_hash,
            schedule,
            results,
            maintenance,
            dispatched,
            preflight_results,
            cleanup_succeeded,
            logout_succeeded,
            time.process_time() - started_cpu,
            time.monotonic() - started_wall,
        )
        print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
        return 0 if summary["passed"] else 1
    except LoadError as error:
        print(json.dumps({"loadExecuted": bool(client and client.network_started), "passed": False, "error": error.code}, sort_keys=True))
        return 2
    except (KeyboardInterrupt, EOFError):
        print(json.dumps({"loadExecuted": bool(client and client.network_started), "passed": False, "error": "operator_interrupted"}, sort_keys=True))
        return 130
    except Exception:
        print(json.dumps({"loadExecuted": bool(client and client.network_started), "passed": False, "error": "internal_runner"}, sort_keys=True))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
