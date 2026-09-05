#!/usr/bin/env python3
"""staging upstream load guard journal을 비밀 없는 합격 요약으로 변환한다."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections.abc import Iterable


RUN_ID = re.compile(r"[A-Za-z0-9._-]{1,64}")
GUARD_MESSAGE = re.compile(
    r"(?:^|\s)runId=(?P<runId>[^\s]+) "
    r"provider=(?P<provider>[^\s]+) "
    r"endpoint=(?P<endpoint>[^\s]+) "
    r"event=(?P<event>[^\s]+) "
    r"class=(?P<class>[^\s]+) "
    r"elapsedMs=(?P<elapsedMs>[^\s]+) "
    r"endpointCount=(?P<endpointCount>[^\s]+) "
    r"endpointLimit=(?P<endpointLimit>[^\s]+) "
    r"providerCount=(?P<providerCount>[^\s]+) "
    r"providerLimit=(?P<providerLimit>[^\s]+)\s*$"
)
ENDPOINT_LIMITS = {
    "KAKAO_CATEGORY": 2_000,
    "KAKAO_KEYWORD": 2_000,
    "KAKAO_ACCESS_TOKEN_INFO": 2_000,
    "KAKAO_USER_ME": 2_000,
    "KTO_SEARCH_FESTIVAL": 100,
    "KTO_KOR_LOCATION": 100,
    "KTO_WELLNESS_LOCATION": 100,
    "KTO_DURUNUBI_COURSE": 100,
}
PROVIDER_LIMITS = {"KAKAO": "5000", "KTO": "NONE"}
EVENTS = {"COMPLETE", "TRIP", "BLOCK"}
CLASSES = {
    "HTTP_2XX",
    "HTTP_3XX",
    "HTTP_4XX",
    "HTTP_429",
    "HTTP_5XX",
    "HTTP_OTHER",
    "TIMEOUT",
    "IO_FAILURE",
    "KTO_RESULT_CODE",
    "UNKNOWN_ENDPOINT",
    "BUDGET_EXHAUSTED",
    "GLOBAL_TRIPPED",
}
REQUIRED_FIELDS = {
    "runId",
    "provider",
    "endpoint",
    "event",
    "class",
    "elapsedMs",
    "endpointCount",
    "endpointLimit",
    "providerCount",
    "providerLimit",
}


def parse_fields(line: str) -> dict[str, str] | None:
    match = GUARD_MESSAGE.search(line)
    return match.groupdict() if match else None


def non_negative_int(value: str | None) -> int | None:
    if value is None or not value.isascii() or not value.isdigit():
        return None
    return int(value)


def summarize(
    lines: Iterable[str],
    expected_run_id: str,
    required_endpoint: str | None = None,
) -> dict[str, object]:
    endpoint_attempts = {endpoint: set() for endpoint in ENDPOINT_LIMITS}
    provider_attempts = {provider: set() for provider in PROVIDER_LIMITS}
    guard_lines = 0
    malformed_lines = 0
    unsafe_events = 0
    non_2xx_results = 0
    expected_run_token = re.compile(
        rf"(?:^|\s)runId={re.escape(expected_run_id)}(?:\s|$)"
    )

    for line in lines:
        values = parse_fields(line)
        if values is None:
            if expected_run_token.search(line):
                guard_lines += 1
                malformed_lines += 1
            continue
        if values["runId"] != expected_run_id:
            continue

        guard_lines += 1
        if set(values) != REQUIRED_FIELDS:
            malformed_lines += 1
            continue

        provider = values["provider"]
        endpoint = values["endpoint"]
        event = values["event"]
        result_class = values["class"]
        elapsed_ms = non_negative_int(values["elapsedMs"])
        endpoint_count = non_negative_int(values["endpointCount"])
        provider_count = non_negative_int(values["providerCount"])

        valid_provider = (
            provider in PROVIDER_LIMITS
            and values["providerLimit"] == PROVIDER_LIMITS[provider]
        )
        valid_endpoint = endpoint in ENDPOINT_LIMITS
        if valid_endpoint:
            valid_endpoint = (
                values["endpointLimit"] == str(ENDPOINT_LIMITS[endpoint])
                and ENDPOINT_LIMITS[endpoint] > 0
            )
        elif endpoint == "UNKNOWN":
            valid_endpoint = values["endpointLimit"] == "UNKNOWN"

        if (
            not valid_provider
            or not valid_endpoint
            or event not in EVENTS
            or result_class not in CLASSES
            or elapsed_ms is None
            or endpoint_count is None
            or provider_count is None
            or (endpoint == "UNKNOWN" and endpoint_count != 0)
        ):
            malformed_lines += 1
            continue

        if event in {"TRIP", "BLOCK"}:
            unsafe_events += 1
        if result_class != "HTTP_2XX":
            non_2xx_results += 1
        if endpoint_count > 0 and endpoint in endpoint_attempts:
            endpoint_attempts[endpoint].add(endpoint_count)
        if provider_count > 0:
            provider_attempts[provider].add(provider_count)

    endpoints: list[dict[str, object]] = []
    providers: list[dict[str, object]] = []
    counter_gaps = 0
    over_limit = 0

    for endpoint, limit in ENDPOINT_LIMITS.items():
        attempts = endpoint_attempts[endpoint]
        if not attempts:
            continue
        final_count = max(attempts)
        if attempts != set(range(1, final_count + 1)):
            counter_gaps += 1
        if final_count > limit:
            over_limit += 1
        endpoints.append(
            {
                "endpoint": endpoint,
                "attempts": len(attempts),
                "finalCount": final_count,
                "limit": limit,
            }
        )

    for provider, limit in PROVIDER_LIMITS.items():
        attempts = provider_attempts[provider]
        if not attempts:
            continue
        final_count = max(attempts)
        if attempts != set(range(1, final_count + 1)):
            counter_gaps += 1
        if provider == "KAKAO" and final_count > int(limit):
            over_limit += 1
        providers.append(
            {
                "provider": provider,
                "attempts": len(attempts),
                "finalCount": final_count,
                "limit": limit,
            }
        )

    required_endpoint_seen = (
        required_endpoint is None
        or bool(endpoint_attempts.get(required_endpoint, set()))
    )
    passed = (
        guard_lines > 0
        and malformed_lines == 0
        and unsafe_events == 0
        and non_2xx_results == 0
        and counter_gaps == 0
        and over_limit == 0
        and required_endpoint_seen
    )
    return {
        "runId": expected_run_id,
        "guardLines": guard_lines,
        "malformedLines": malformed_lines,
        "unsafeEvents": unsafe_events,
        "non2xxResults": non_2xx_results,
        "counterGaps": counter_gaps,
        "overLimit": over_limit,
        "requiredEndpoint": required_endpoint,
        "requiredEndpointSeen": required_endpoint_seen,
        "endpoints": endpoints,
        "providers": providers,
        "passed": passed,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="staging upstream load guard journal 합격 요약"
    )
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--require-endpoint", choices=tuple(ENDPOINT_LIMITS))
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not RUN_ID.fullmatch(args.run_id):
        print("run ID 형식이 잘못됐습니다.", file=sys.stderr)
        return 2
    summary = summarize(sys.stdin, args.run_id, args.require_endpoint)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
