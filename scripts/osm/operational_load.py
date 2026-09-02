#!/usr/bin/env python3
"""GraphHopper 운영 사양 판정을 위한 고정 도착률 부하 도구.

한 작업(batch)은 실제 서버와 같은 순서로 한 지점·한 목표 거리에 대해 seed 0..15를
직렬 호출한다. batch는 wall clock 기준 고정 간격으로 시작하며 worker가 모두 사용 중이면
기다려서 부하를 낮추지 않고 missed start로 기록해 시험을 실패시킨다.
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


@dataclass(frozen=True)
class Cell:
    name: str
    latitude: float
    longitude: float
    target_km: int


@dataclass(frozen=True)
class RequestResult:
    seed: int
    elapsed_seconds: float
    status: int | None
    success: bool
    error_type: str | None


@dataclass(frozen=True)
class BatchResult:
    sequence: int
    scheduled_offset_seconds: float
    start_delay_seconds: float
    name: str
    target_km: int
    elapsed_seconds: float
    success: bool
    requests: tuple[RequestResult, ...]


def cells() -> tuple[Cell, ...]:
    return tuple(
        Cell(name, latitude, longitude, target_km)
        for name, latitude, longitude in METRO + LOCAL
        for target_km in FILTER_KMS
    )


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


def request_route(
    base_url: str,
    cell: Cell,
    seed: int,
    timeout_seconds: float,
) -> RequestResult:
    params = urllib.parse.urlencode(
        {
            "point": f"{cell.latitude},{cell.longitude}",
            "profile": "run",
            "algorithm": "round_trip",
            "round_trip.distance": int(cell.target_km * 1000 * DISTANCE_CORRECTION),
            "round_trip.seed": seed,
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
        success = status == 200 and bool(payload.get("paths"))
        return RequestResult(seed, elapsed, status, success, None if success else "InvalidResponse")
    except urllib.error.HTTPError as error:
        return RequestResult(
            seed,
            time.perf_counter() - started,
            error.code,
            False,
            type(error).__name__,
        )
    except (TimeoutError, urllib.error.URLError, json.JSONDecodeError) as error:
        return RequestResult(
            seed,
            time.perf_counter() - started,
            None,
            False,
            type(error).__name__,
        )


def run_batch(
    sequence: int,
    scheduled_offset_seconds: float,
    scheduled_at: float,
    cell: Cell,
    seeds: int,
    base_url: str,
    timeout_seconds: float,
    request: Callable[[str, Cell, int, float], RequestResult] = request_route,
) -> BatchResult:
    started = time.monotonic()
    elapsed_started = time.perf_counter()
    results: list[RequestResult] = []
    for seed in range(seeds):
        result = request(base_url, cell, seed, timeout_seconds)
        results.append(result)
        # 실제 GraphHopperOsmRouteGenerator와 같이 첫 실패 뒤 남은 seed를 호출하지 않는다.
        if not result.success:
            break
    elapsed = time.perf_counter() - elapsed_started
    return BatchResult(
        sequence=sequence,
        scheduled_offset_seconds=scheduled_offset_seconds,
        start_delay_seconds=max(0.0, started - scheduled_at),
        name=cell.name,
        target_km=cell.target_km,
        elapsed_seconds=elapsed,
        success=len(results) == seeds and all(result.success for result in results),
        requests=tuple(results),
    )


def summarize(
    results: list[BatchResult],
    scheduled: int,
    missed: int,
    timeout_seconds: float,
) -> dict[str, object]:
    request_results = [item for batch in results for item in batch.requests]
    request_times = [item.elapsed_seconds for item in request_results]
    batch_times = [batch.elapsed_seconds for batch in results]
    failed_batches = sum(not batch.success for batch in results)
    failed_requests = sum(not item.success for item in request_results)
    over_timeout = sum(item.elapsed_seconds > timeout_seconds for item in request_results)
    passed = (
        missed == 0
        and len(results) == scheduled
        and failed_batches == 0
        and failed_requests == 0
        and over_timeout == 0
    )
    return {
        "scheduledBatches": scheduled,
        "completedBatches": len(results),
        "missedBatchStarts": missed,
        "failedBatches": failed_batches,
        "directRequests": len(request_results),
        "failedDirectRequests": failed_requests,
        "requestsOverTimeout": over_timeout,
        "requestSeconds": distribution(request_times),
        "seedBatchSeconds": distribution(batch_times),
        "passed": passed,
    }


def write_json_line(target, value: object) -> None:
    target.write(json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n")
    target.flush()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="GraphHopper caps/all 입력을 고정 도착률로 반복하고 지연시간을 기록합니다."
    )
    parser.add_argument("--base-url", default="http://127.0.0.1:8989")
    parser.add_argument("--duration-seconds", type=int, required=True)
    parser.add_argument("--batches-per-minute", type=float, required=True)
    parser.add_argument("--concurrency", type=int, required=True)
    parser.add_argument("--seeds", type=int, default=16)
    parser.add_argument("--timeout-seconds", type=float, default=5.0)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.duration_seconds <= 0:
        parser.error("--duration-seconds는 1 이상이어야 합니다.")
    if args.batches_per_minute <= 0:
        parser.error("--batches-per-minute는 0보다 커야 합니다.")
    if args.concurrency <= 0:
        parser.error("--concurrency는 1 이상이어야 합니다.")
    if args.seeds <= 0:
        parser.error("--seeds는 1 이상이어야 합니다.")
    if args.timeout_seconds <= 0:
        parser.error("--timeout-seconds는 0보다 커야 합니다.")
    return args


def main() -> int:
    args = parse_args()
    if args.output.exists():
        print(f"출력 파일이 이미 있습니다: {args.output}", file=sys.stderr)
        return 2
    args.output.parent.mkdir(parents=True, exist_ok=True)

    try:
        with urllib.request.urlopen(f"{args.base_url.rstrip('/')}/health", timeout=5) as response:
            if response.status != 200:
                raise RuntimeError(f"health status={response.status}")
    except (OSError, RuntimeError) as error:
        print(f"GraphHopper health 확인 실패: {type(error).__name__}", file=sys.stderr)
        return 1

    interval = 60.0 / args.batches_per_minute
    workload = cells()
    started_wall = datetime.now(timezone.utc)
    started = time.monotonic()
    deadline = started + args.duration_seconds
    sequence = 0
    missed = 0
    results: list[BatchResult] = []
    active: set[concurrent.futures.Future[BatchResult]] = set()
    write_lock = threading.Lock()

    def collect(done: Iterable[concurrent.futures.Future[BatchResult]], output) -> None:
        nonlocal active
        for future in done:
            result = future.result()
            results.append(result)
            with write_lock:
                write_json_line(output, {"type": "batch", **asdict(result)})
        active.difference_update(done)

    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        write_json_line(
            output,
            {
                "type": "start",
                "schemaVersion": 1,
                "startedAt": started_wall.isoformat(),
                "baseUrl": args.base_url,
                "durationSeconds": args.duration_seconds,
                "batchesPerMinute": args.batches_per_minute,
                "concurrency": args.concurrency,
                "seeds": args.seeds,
                "timeoutSeconds": args.timeout_seconds,
                "cellCount": len(workload),
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

                cell = workload[sequence % len(workload)]
                if len(active) >= args.concurrency:
                    missed += 1
                    write_json_line(
                        output,
                        {
                            "type": "missed",
                            "sequence": sequence,
                            "scheduledOffsetSeconds": sequence * interval,
                            "name": cell.name,
                            "targetKm": cell.target_km,
                            "reason": "concurrency_saturated",
                        },
                    )
                else:
                    active.add(
                        executor.submit(
                            run_batch,
                            sequence,
                            sequence * interval,
                            scheduled_at,
                            cell,
                            args.seeds,
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
