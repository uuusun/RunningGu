#!/usr/bin/env python3
"""GraphHopper 운영 사양 판정을 위한 정상 직접 요청 고정 도착률 부하 도구.

로컬 caps/all 회귀에서 HTTP 200과 품질 상한 통과를 확인해 고정한 요청만 읽는다.
요청은 wall clock 기준 고정 간격으로 시작하며 worker가 모두 사용 중이면 기다려서
부하를 낮추지 않고 missed start로 기록해 시험을 실패시킨다.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Iterable

from roundtrip_cases import DISTANCE_CORRECTION, FILTER_KMS, LOCAL, METRO


NO_VALID_POINT_PREFIX = "Could not find a valid point after "
REQUEST_OPTIONS = {
    "algorithm": "round_trip",
    "pointsEncoded": False,
    "elevation": True,
    "instructions": True,
    "details": ["road_class"],
}


@dataclass(frozen=True)
class RequestCase:
    name: str
    latitude: float
    longitude: float
    target_km: int
    distance_m: int
    seed: int


@dataclass(frozen=True)
class RequestResult:
    elapsed_seconds: float
    status: int | None
    success: bool
    error_type: str | None


@dataclass(frozen=True)
class ScheduledRequestResult:
    sequence: int
    scheduled_offset_seconds: float
    start_delay_seconds: float
    name: str
    target_km: int
    seed: int
    elapsed_seconds: float
    status: int | None
    success: bool
    error_type: str | None


def _required_string(value: object, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field}가 비어 있습니다.")
    return value


def _finite_number(value: object, field: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{field}가 숫자가 아닙니다.")
    result = float(value)
    if not math.isfinite(result):
        raise ValueError(f"{field}가 유한수가 아닙니다.")
    return result


def load_request_set(
    path: Path,
    expected_artifact_id: str,
    expected_server_image_digest: str,
) -> tuple[RequestCase, ...]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("요청 세트 JSON을 읽을 수 없습니다.") from error
    if not isinstance(payload, dict) or payload.get("schemaVersion") != 1:
        raise ValueError("요청 세트 schemaVersion은 1이어야 합니다.")
    if payload.get("artifactId") != expected_artifact_id:
        raise ValueError("요청 세트 artifactId가 활성 artifact와 다릅니다.")
    if payload.get("serverImageDigest") != expected_server_image_digest:
        raise ValueError("요청 세트 serverImageDigest가 실행 image와 다릅니다.")
    if payload.get("profile") != "run" or payload.get("seedCount") != 16:
        raise ValueError("요청 세트는 run profile과 seed 16개 기준선이어야 합니다.")
    if payload.get("requestOptions") != REQUEST_OPTIONS:
        raise ValueError("요청 세트의 GraphHopper 요청 옵션이 계약과 다릅니다.")

    raw_cells = payload.get("cells")
    if not isinstance(raw_cells, list) or not raw_cells:
        raise ValueError("회귀 기준선 cells가 없습니다.")

    expected_cells = {
        (name, target_km): (zone, latitude, longitude)
        for name, latitude, longitude, zone in (
            [(name, latitude, longitude, "수도권") for name, latitude, longitude in METRO]
            + [(name, latitude, longitude, "지방") for name, latitude, longitude in LOCAL]
        )
        for target_km in FILTER_KMS
    }
    seen_cells: set[tuple[str, int]] = set()
    eligible_cells: dict[tuple[str, float, float, int], set[int]] = {}
    for index, raw_cell in enumerate(raw_cells):
        if not isinstance(raw_cell, dict):
            raise ValueError(f"cells[{index}]가 객체가 아닙니다.")
        name = _required_string(raw_cell.get("name"), f"cells[{index}].name")
        latitude = _finite_number(raw_cell.get("latitude"), f"cells[{index}].latitude")
        longitude = _finite_number(raw_cell.get("longitude"), f"cells[{index}].longitude")
        target_km = raw_cell.get("targetKm")
        requests = raw_cell.get("requests")
        if isinstance(target_km, bool) or not isinstance(target_km, int) or target_km <= 0:
            raise ValueError(f"cells[{index}].targetKm가 잘못됐습니다.")
        if not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
            raise ValueError(f"cells[{index}] 좌표 범위가 잘못됐습니다.")
        if not isinstance(requests, list):
            raise ValueError(f"cells[{index}].requests가 배열이 아닙니다.")

        fixture_key = (name, target_km)
        if fixture_key in seen_cells:
            raise ValueError(f"cells[{index}]가 앞 셀과 중복됩니다.")
        seen_cells.add(fixture_key)
        expected_fixture = expected_cells.get(fixture_key)
        if expected_fixture != (raw_cell.get("zone"), latitude, longitude):
            raise ValueError(f"cells[{index}]가 caps/all 고정 fixture와 다릅니다.")

        requests_by_seed: dict[int, dict] = {}
        for request_index, request in enumerate(requests):
            if not isinstance(request, dict):
                raise ValueError(
                    f"cells[{index}].requests[{request_index}]가 객체가 아닙니다."
                )
            seed = request.get("seed")
            if isinstance(seed, bool) or not isinstance(seed, int) or not 0 <= seed < 16:
                raise ValueError(
                    f"cells[{index}].requests[{request_index}].seed가 0..15가 아닙니다."
                )
            if seed in requests_by_seed:
                raise ValueError(f"cells[{index}]의 seed {seed}가 중복됩니다.")
            requests_by_seed[seed] = request
        if requests_by_seed.keys() != set(range(16)):
            raise ValueError(f"cells[{index}]에 seed 0..15 결과가 모두 있어야 합니다.")

        eligible_seeds = {
            request.get("seed")
            for request in requests_by_seed.values()
            if request.get("status") == 200
            and request.get("errorType") is None
            and request.get("eligible") is True
        }
        if eligible_seeds:
            key = (name, latitude, longitude, target_km)
            if key in eligible_cells:
                raise ValueError(f"cells[{index}]가 앞 합격 셀과 중복됩니다.")
            eligible_cells[key] = eligible_seeds
    if seen_cells != expected_cells.keys():
        raise ValueError("caps/all 고정 fixture의 모든 지점·거리 셀이 있어야 합니다.")

    raw_requests = payload.get("normalRequests")
    if not isinstance(raw_requests, list) or not raw_requests:
        raise ValueError("정상 직접 요청이 없습니다.")

    cases: list[RequestCase] = []
    identities: set[tuple[float, float, int, int]] = set()
    selected_cells: set[tuple[str, float, float, int]] = set()
    for index, raw in enumerate(raw_requests):
        if not isinstance(raw, dict):
            raise ValueError(f"normalRequests[{index}]가 객체가 아닙니다.")
        name = _required_string(raw.get("name"), f"normalRequests[{index}].name")
        latitude = _finite_number(raw.get("latitude"), f"normalRequests[{index}].latitude")
        longitude = _finite_number(raw.get("longitude"), f"normalRequests[{index}].longitude")
        target_km = raw.get("targetKm")
        distance_m = raw.get("distanceM")
        seed = raw.get("seed")
        if isinstance(target_km, bool) or not isinstance(target_km, int) or target_km <= 0:
            raise ValueError(f"normalRequests[{index}].targetKm가 잘못됐습니다.")
        if isinstance(distance_m, bool) or not isinstance(distance_m, int) or distance_m <= 0:
            raise ValueError(f"normalRequests[{index}].distanceM가 잘못됐습니다.")
        if isinstance(seed, bool) or not isinstance(seed, int) or not 0 <= seed < 16:
            raise ValueError(f"normalRequests[{index}].seed가 0..15가 아닙니다.")
        if not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
            raise ValueError(f"normalRequests[{index}] 좌표 범위가 잘못됐습니다.")
        expected_distance_m = int(target_km * 1_000 * DISTANCE_CORRECTION)
        if distance_m != expected_distance_m:
            raise ValueError(
                f"normalRequests[{index}].distanceM가 계약 산식과 다릅니다."
            )
        cell_key = (name, latitude, longitude, target_km)
        eligible_seeds = eligible_cells.get(cell_key)
        if eligible_seeds is None:
            raise ValueError(f"normalRequests[{index}]가 합격 셀에 속하지 않습니다.")
        if seed not in eligible_seeds:
            raise ValueError(f"normalRequests[{index}].seed가 합격 요청이 아닙니다.")
        if cell_key in selected_cells:
            raise ValueError(f"normalRequests[{index}]가 같은 합격 셀을 중복 선택했습니다.")
        selected_cells.add(cell_key)
        identity = (latitude, longitude, distance_m, seed)
        if identity in identities:
            raise ValueError(f"normalRequests[{index}]가 앞 요청과 중복됩니다.")
        identities.add(identity)
        cases.append(RequestCase(
            name=name,
            latitude=latitude,
            longitude=longitude,
            target_km=target_km,
            distance_m=distance_m,
            seed=seed,
        ))
    if selected_cells != eligible_cells.keys():
        raise ValueError("모든 합격 셀에서 정상 직접 요청을 정확히 하나씩 선택해야 합니다.")
    return tuple(cases)


def percentile(values: Iterable[float], quantile: float) -> float | None:
    ordered = sorted(values)
    if not ordered:
        return None
    index = max(0, math.ceil(quantile * len(ordered)) - 1)
    return ordered[index]


def distribution(values: Iterable[float]) -> dict[str, float | None]:
    materialized = list(values)
    return {
        "p50": percentile(materialized, 0.50),
        "p95": percentile(materialized, 0.95),
        "max": max(materialized) if materialized else None,
    }


def _http_error_type(error: urllib.error.HTTPError) -> str:
    try:
        payload = json.load(error)
    except (json.JSONDecodeError, OSError, ValueError):
        payload = None
    message = payload.get("message") if isinstance(payload, dict) else None
    if error.code == 400 and isinstance(message, str) and message.startswith(NO_VALID_POINT_PREFIX):
        return "NoValidPoint"
    return f"Http{error.code}"


def request_route(
    base_url: str,
    case: RequestCase,
    timeout_seconds: float,
) -> RequestResult:
    params = urllib.parse.urlencode(
        {
            "point": f"{case.latitude},{case.longitude}",
            "profile": "run",
            "algorithm": "round_trip",
            "round_trip.distance": case.distance_m,
            "round_trip.seed": case.seed,
            "points_encoded": "false",
            "elevation": "true",
            "instructions": "true",
            "details": "road_class",
        }
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(
            f"{base_url.rstrip('/')}/route?{params}",
            timeout=timeout_seconds,
        ) as response:
            status = response.status
            payload = json.load(response)
        elapsed = time.perf_counter() - started
        paths = payload.get("paths") if isinstance(payload, dict) else None
        success = status == 200 and isinstance(paths, list) and bool(paths)
        return RequestResult(elapsed, status, success, None if success else "InvalidResponse")
    except urllib.error.HTTPError as error:
        return RequestResult(
            time.perf_counter() - started,
            error.code,
            False,
            _http_error_type(error),
        )
    except (TimeoutError, urllib.error.URLError, json.JSONDecodeError) as error:
        return RequestResult(
            time.perf_counter() - started,
            None,
            False,
            type(error).__name__,
        )


def run_request(
    sequence: int,
    scheduled_offset_seconds: float,
    scheduled_at: float,
    case: RequestCase,
    base_url: str,
    timeout_seconds: float,
    request: Callable[[str, RequestCase, float], RequestResult] = request_route,
) -> ScheduledRequestResult:
    started = time.monotonic()
    result = request(base_url, case, timeout_seconds)
    return ScheduledRequestResult(
        sequence=sequence,
        scheduled_offset_seconds=scheduled_offset_seconds,
        start_delay_seconds=max(0.0, started - scheduled_at),
        name=case.name,
        target_km=case.target_km,
        seed=case.seed,
        elapsed_seconds=result.elapsed_seconds,
        status=result.status,
        success=result.success,
        error_type=result.error_type,
    )


def summarize(
    results: list[ScheduledRequestResult],
    scheduled: int,
    missed: int,
    timeout_seconds: float,
) -> dict[str, object]:
    request_times = [item.elapsed_seconds for item in results]
    failed_requests = sum(not item.success for item in results)
    no_valid_point = sum(item.error_type == "NoValidPoint" for item in results)
    over_timeout = sum(item.elapsed_seconds > timeout_seconds for item in results)
    passed = (
        missed == 0
        and len(results) == scheduled
        and failed_requests == 0
        and over_timeout == 0
    )
    return {
        "scheduledRequests": scheduled,
        "completedRequests": len(results),
        "missedRequestStarts": missed,
        "failedDirectRequests": failed_requests,
        "noValidPointResponses": no_valid_point,
        "requestsOverTimeout": over_timeout,
        "requestSeconds": distribution(request_times),
        "passed": passed,
    }


def write_json_line(target, value: object) -> None:
    target.write(json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n")
    target.flush()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="로컬에서 고정한 GraphHopper 정상 직접 요청을 고정 도착률로 반복합니다."
    )
    parser.add_argument("--base-url", default="http://127.0.0.1:8989")
    parser.add_argument("--request-set", type=Path, required=True)
    parser.add_argument("--artifact-id", required=True)
    parser.add_argument("--server-image-digest", required=True)
    parser.add_argument("--duration-seconds", type=int, required=True)
    parser.add_argument("--requests-per-minute", type=float, required=True)
    parser.add_argument("--concurrency", type=int, required=True)
    parser.add_argument("--timeout-seconds", type=float, default=5.0)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.duration_seconds <= 0:
        parser.error("--duration-seconds는 1 이상이어야 합니다.")
    if args.requests_per_minute <= 0:
        parser.error("--requests-per-minute는 0보다 커야 합니다.")
    if args.concurrency <= 0:
        parser.error("--concurrency는 1 이상이어야 합니다.")
    if args.timeout_seconds <= 0:
        parser.error("--timeout-seconds는 0보다 커야 합니다.")
    return args


def main() -> int:
    args = parse_args()
    if args.output.exists():
        print(f"출력 파일이 이미 있습니다: {args.output}", file=sys.stderr)
        return 2
    try:
        workload = load_request_set(
            args.request_set,
            args.artifact_id,
            args.server_image_digest,
        )
    except ValueError as error:
        print(f"정상 직접 요청 세트 검증 실패: {error}", file=sys.stderr)
        return 2
    args.output.parent.mkdir(parents=True, exist_ok=True)

    try:
        with urllib.request.urlopen(f"{args.base_url.rstrip('/')}/health", timeout=5) as response:
            if response.status != 200:
                raise RuntimeError(f"health status={response.status}")
    except (OSError, RuntimeError) as error:
        print(f"GraphHopper health 확인 실패: {type(error).__name__}", file=sys.stderr)
        return 1

    interval = 60.0 / args.requests_per_minute
    started_wall = datetime.now(timezone.utc)
    started = time.monotonic()
    deadline = started + args.duration_seconds
    sequence = 0
    missed = 0
    results: list[ScheduledRequestResult] = []
    active: set[concurrent.futures.Future[ScheduledRequestResult]] = set()
    write_lock = threading.Lock()

    def collect(done: Iterable[concurrent.futures.Future[ScheduledRequestResult]], output) -> None:
        nonlocal active
        for future in done:
            result = future.result()
            results.append(result)
            with write_lock:
                write_json_line(output, {"type": "request", **asdict(result)})
        active.difference_update(done)

    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        write_json_line(
            output,
            {
                "type": "start",
                "schemaVersion": 2,
                "startedAt": started_wall.isoformat(),
                "baseUrl": args.base_url,
                "requestSet": str(args.request_set),
                "artifactId": args.artifact_id,
                "serverImageDigest": args.server_image_digest,
                "durationSeconds": args.duration_seconds,
                "requestsPerMinute": args.requests_per_minute,
                "concurrency": args.concurrency,
                "timeoutSeconds": args.timeout_seconds,
                "requestCaseCount": len(workload),
            },
        )
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
            while True:
                scheduled_at = started + sequence * interval
                if scheduled_at >= deadline:
                    break
                remaining = scheduled_at - time.monotonic()
                if remaining > 0:
                    time.sleep(remaining)
                done = {future for future in active if future.done()}
                collect(done, output)

                case = workload[sequence % len(workload)]
                if len(active) >= args.concurrency:
                    missed += 1
                    write_json_line(
                        output,
                        {
                            "type": "missed",
                            "sequence": sequence,
                            "scheduledOffsetSeconds": sequence * interval,
                            "name": case.name,
                            "targetKm": case.target_km,
                            "seed": case.seed,
                            "reason": "concurrency_saturated",
                        },
                    )
                else:
                    active.add(
                        executor.submit(
                            run_request,
                            sequence,
                            sequence * interval,
                            scheduled_at,
                            case,
                            args.base_url,
                            args.timeout_seconds,
                        )
                    )
                sequence += 1

            for future in concurrent.futures.as_completed(active):
                collect((future,), output)

        summary = summarize(results, sequence, missed, args.timeout_seconds)
        summary.update(
            {
                "type": "summary",
                "endedAt": datetime.now(timezone.utc).isoformat(),
                "actualElapsedSeconds": time.monotonic() - started,
            }
        )
        write_json_line(output, summary)

    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
