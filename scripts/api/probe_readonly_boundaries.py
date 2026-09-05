#!/usr/bin/env python3
"""DB/catalog 공개 조회와 미인증 차단만 검사한다. API 명세 §0·3·5·6·7.

마케팅 설정·토큰·쓰기·외부 프록시·GraphHopper 호출·반복 부하는 포함하지 않는다.
"""

from __future__ import annotations

import argparse
import json
import math
from datetime import date
from urllib.parse import urlencode

from prepare_api_load import ProbeError, fetch_json, object_list, positive_int, require


ERROR_CASES = (
    ("anonymous_me", "/api/me", 401, "UNAUTHORIZED"),
    ("anonymous_favorites", "/api/me/favorites", 401, "UNAUTHORIZED"),
    ("anonymous_saved_courses", "/api/me/courses", 401, "UNAUTHORIZED"),
    ("anonymous_itineraries", "/api/itineraries", 401, "UNAUTHORIZED"),
    ("course_negative_page", "/api/courses?page=-1", 400, "VALIDATION_FAILED"),
    ("course_zero_size", "/api/courses?size=0", 400, "VALIDATION_FAILED"),
    ("course_over_max_size", "/api/courses?size=51", 400, "VALIDATION_FAILED"),
    ("closing_zero_limit", "/api/contests/closing-soon?limit=0", 400, "VALIDATION_FAILED"),
    ("closing_over_max_limit", "/api/contests/closing-soon?limit=5", 400, "VALIDATION_FAILED"),
)


def probe_boundaries(transport=fetch_json) -> dict:
    results = []
    context = {}

    def check(case_id, path, status_expected, validator):
        record = {"caseId": case_id, "status": None, "passed": False}
        try:
            status, payload = transport(path)
            record["status"] = status
            if status != status_expected:
                raise ProbeError("unexpected_http")
            validator(payload, record)
            record["passed"] = True
        except ProbeError as error:
            record["error"] = str(error)
        except (KeyError, TypeError, ValueError, AttributeError):
            record["error"] = "response_contract"
        results.append(record)

    def skipped(case_id):
        results.append({"caseId": case_id, "status": None, "passed": False,
                        "error": "dependency_failed"})

    for case_id, path, expected_status, expected_code in ERROR_CASES:
        def error_body(payload, record, status=expected_status, code=expected_code):
            require(isinstance(payload, dict) and payload.get("status") == status
                    and payload.get("code") == code)
        check(case_id, path, expected_status, error_body)

    def contest_page(payload, record):
        items = object_list(payload, "items")
        require(len(items) <= 2)
        require(all(positive_int(item.get("id")) and item.get("active") is True
                    and item.get("favorite") is False for item in items))
        keys = [(date.fromisoformat(item["contestDate"]), item["id"]) for item in items]
        require(keys == sorted(keys) and len(set(keys)) == len(keys))
        require(type(payload.get("hasNext")) is bool)
        if payload["hasNext"]:
            require(isinstance(payload.get("nextCursor"), str)
                    and 0 < len(payload["nextCursor"]) <= 4096)
        else:
            require(payload.get("nextCursor") is None)
        record["count"] = len(items)
        return keys

    def first_contests(payload, record):
        keys = contest_page(payload, record)
        if not payload["hasNext"]:
            raise ProbeError("empty_fixture")
        context["contest_cursor"] = payload["nextCursor"]
        context["contest_last_key"] = keys[-1]

    check("contest_cursor_first", "/api/contests?size=2", 200, first_contests)

    def next_contests(payload, record):
        keys = contest_page(payload, record)
        require(keys[0] > context["contest_last_key"])

    if "contest_cursor" in context:
        query = urlencode({"size": 2, "cursor": context["contest_cursor"]})
        check("contest_cursor_next", "/api/contests?" + query, 200, next_contests)
    else:
        skipped("contest_cursor_next")

    def regions(payload, record):
        items = object_list(payload, "items")
        require(all(positive_int(item.get("count")) and isinstance(item.get("region"), str)
                    and 0 < len(item["region"]) <= 20 for item in items))
        keys = [(-item["count"], item["region"]) for item in items]
        require(keys == sorted(keys) and len({item["region"] for item in items}) == len(items))
        context["region"] = items[0]["region"]
        context["region_count"] = items[0]["count"]
        record["count"] = len(items)

    check("course_regions_order", "/api/courses/regions", 200, regions)

    def regional_page(payload, record):
        items = object_list(payload, "content")
        require(len(items) == min(context["region_count"], 50))
        require(all(item.get("sido") == context["region"]
                    and item.get("dataSource") in {"API_GPX", "GPX_ONLY"}
                    and isinstance(item.get("courseId"), str) and bool(item["courseId"])
                    and type(item.get("distanceKm")) in (int, float)
                    and math.isfinite(item["distanceKm"]) and item["distanceKm"] > 0
                    for item in items))
        keys = [(item["distanceKm"], item["courseId"]) for item in items]
        require(keys == sorted(keys) and len({item["courseId"] for item in items}) == len(items))
        page = payload["page"]
        require(page["number"] == 0 and page["size"] == 50
                and page["totalElements"] == context["region_count"]
                and page["hasNext"] is (context["region_count"] > 50))
        require(isinstance(payload.get("attributions"), list) and bool(payload["attributions"]))
        record["count"] = len(items)

    def empty_page(payload, record):
        require(isinstance(payload, dict) and payload.get("content") == [])
        page = payload["page"]
        require(page["number"] == context["beyond_page"] and page["size"] == 50
                and page["totalElements"] == context["region_count"]
                and page["hasNext"] is False and payload.get("attributions") == [])
        record["count"] = 0

    if "region" in context:
        query = urlencode({"region": " " + context["region"] + " ", "page": 0, "size": 50})
        check("course_region_trim_order", "/api/courses?" + query, 200, regional_page)
        context["beyond_page"] = (context["region_count"] + 49) // 50
        query = urlencode({"region": context["region"], "page": context["beyond_page"], "size": 50})
        check("course_beyond_last_page", "/api/courses?" + query, 200, empty_page)
    else:
        skipped("course_region_trim_order")
        skipped("course_beyond_last_page")

    return {"scope": "readonly_contract_boundaries", "checks": results,
            "passed": all(row["passed"] for row in results), "loadExecuted": False,
            "authenticatedMeVerified": False, "marketingRetested": False,
            "fullRequestSetFrozen": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe-staging", action="store_true", required=True,
                        help="staging 고정 호스트에 최대 14개 GET만 실행")
    parser.parse_args()
    result = probe_boundaries()
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
