#!/usr/bin/env python3
"""앱 부하 전 공개 조회·인증 경계만 확인한다. 반복 부하·쓰기·외부 프록시는 실행하지 않는다.

기준: docs/deploy/api-load-test-plan.md §2, API 명세 §0·3·6.
원문 응답·이메일·토큰·좌표·예외 메시지는 출력하지 않는다.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import urllib.error
import urllib.request
from datetime import date


STAGING_ORIGIN = "https://staging-api.runninggu.store"
MAX_BODY_BYTES = 1024 * 1024


class ProbeError(Exception):
    """비밀값이 없는 고정 오류 분류만 전달한다."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def fetch_json(path: str) -> tuple[int, object]:
    request = urllib.request.Request(
        STAGING_ORIGIN + path,
        headers={"Accept": "application/json", "User-Agent": "RunningGu-ApiPreflight/1"},
        method="GET",
    )
    try:
        try:
            response = urllib.request.build_opener(NoRedirect()).open(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status = response.code
            body = response.read(MAX_BODY_BYTES + 1)
            content_type = response.headers.get_content_type()
        if len(body) > MAX_BODY_BYTES:
            raise ProbeError("body_too_large")
        if content_type not in {"application/json", "application/problem+json"}:
            raise ProbeError("content_type")
        try:
            return status, json.loads(body)
        except (ValueError, UnicodeDecodeError):
            raise ProbeError("invalid_json") from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise ProbeError("transport") from None


def require(condition: bool) -> None:
    if not condition:
        raise ProbeError("response_contract")


def positive_int(value: object) -> bool:
    return type(value) is int and value > 0


def object_list(payload: object, key: str, *, nonempty: bool = True) -> list[dict]:
    require(isinstance(payload, dict))
    values = payload.get(key)
    require(isinstance(values, list))
    require(all(isinstance(value, dict) for value in values))
    if nonempty and not values:
        raise ProbeError("empty_fixture")
    return values


def validate_month(value: str) -> str:
    if not re.fullmatch(r"[0-9]{4}-(0[1-9]|1[0-2])", value):
        raise ValueError("기준 월은 YYYY-MM이어야 합니다.")
    date.fromisoformat(value + "-01")
    return value


def probe_public(year_month: str, transport=fetch_json) -> dict:
    validate_month(year_month)
    results: list[dict] = []
    context: dict = {}

    def check(case_id, path, expected_status, validator):
        record = {"caseId": case_id, "status": None, "passed": False}
        try:
            status, payload = transport(path)
            record["status"] = status
            if status != expected_status:
                raise ProbeError("unexpected_http")
            validator(payload, record)
            record["passed"] = True
        except ProbeError as error:
            record["error"] = str(error)
        except (KeyError, TypeError, ValueError, AttributeError):
            record["error"] = "response_contract"
        results.append(record)

    def contests(payload, record):
        items = object_list(payload, "items")
        require(all(positive_int(item.get("id")) and item.get("active") is True
                    and item.get("favorite") is False for item in items))
        context["contestId"] = items[0]["id"]
        record["count"] = len(items)

    check("contest_list", "/api/contests?size=20", 200, contests)

    def detail(payload, record):
        require(payload.get("id") == context["contestId"] and payload.get("active") is True)
        require(isinstance(payload.get("events"), list) and bool(payload["events"]))
        date.fromisoformat(payload["contestDate"])
        # 좌표 원문은 출력하지 않고 생성 후보에 필요한 유효성만 확인한다.
        require(type(payload.get("lat")) in (int, float) and type(payload.get("lng")) in (int, float))
        require(math.isfinite(payload["lat"]) and math.isfinite(payload["lng"]))
        require(-90 <= payload["lat"] <= 90 and -180 <= payload["lng"] <= 180)
        record["generationLocationPresent"] = True

    if "contestId" in context:
        check("contest_detail", f'/api/contests/{context["contestId"]}', 200, detail)
    else:
        results.append({"caseId": "contest_detail", "status": None, "passed": False,
                        "error": "dependency_failed"})

    def closing(payload, record):
        items = object_list(payload, "items")
        require(len(items) <= 4)
        require(all(positive_int(item.get("id")) and item.get("regStatus") == "OPEN"
                    and item.get("favorite") is False for item in items))
        record["count"] = len(items)

    check("contest_closing", "/api/contests/closing-soon?limit=4", 200, closing)

    def daily(payload, record):
        items = object_list(payload, "counts")
        require(all(positive_int(item.get("count")) for item in items))
        require(all(date.fromisoformat(item["date"]).strftime("%Y-%m") == year_month for item in items))
        record["count"] = len(items)

    year, month = year_month.split("-")
    check("contest_daily", f"/api/contests/daily-counts?year={year}&month={int(month)}", 200, daily)

    def regions(payload, record):
        items = object_list(payload, "items")
        require(all(positive_int(item.get("count")) and isinstance(item.get("region"), str)
                    and bool(item["region"]) for item in items))
        context["catalogCount"] = sum(item["count"] for item in items)
        record["count"] = len(items)

    check("course_regions", "/api/courses/regions", 200, regions)

    def courses(payload, record):
        items = object_list(payload, "content")
        require(all(item.get("dataSource") in {"API_GPX", "GPX_ONLY"} for item in items))
        require(payload["page"]["totalElements"] == context["catalogCount"])
        require(isinstance(payload.get("attributions"), list) and bool(payload["attributions"]))
        record["count"] = len(items)
        record["catalogCount"] = context["catalogCount"]

    check("course_list", "/api/courses?page=0&size=20", 200, courses)

    def anonymous(payload, record):
        require(isinstance(payload, dict) and payload.get("status") == 401)
        require(isinstance(payload.get("code"), str) and bool(payload["code"]))

    check("anonymous_me_denied", "/api/me", 401, anonymous)
    return {"scope": "public_readiness_only", "yearMonth": year_month,
            "passed": all(item["passed"] for item in results), "checks": results,
            "loadExecuted": False, "fullRequestSetFrozen": False}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--year-month", required=True, type=validate_month)
    parser.add_argument("--probe-public", required=True, action="store_true",
                        help="staging 고정 주소에 최대 7개 읽기 요청만 수행")
    args = parser.parse_args()
    result = probe_public(args.year_month)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
