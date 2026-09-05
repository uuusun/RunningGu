#!/usr/bin/env python3
"""승인된 앱 부하의 오프라인 도착 시간표만 만든다. 네트워크·인증·쓰기는 실행하지 않는다.

기준: docs/deploy/api-load-test-plan.md §4. 요청 입력·외부 예산 확정이나 부하 합격의 대체물이 아니다.
"""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json


WARMUP_MINUTES = 5
MEASUREMENT_MINUTES = 30
MAX_IN_FLIGHT = 4
FAVORITE_ADD_SLOT = 10
FAVORITE_DELETE_SLOT = 50
# 같은 가중치의 순서는 고정한다. 즐겨찾기 추가는 삭제보다 앞에 예약된다.
REQUESTS_PER_MINUTE = (
    ("contest_list", 12),
    ("contest_detail", 8),
    ("contest_closing", 5),
    ("contest_daily", 5),
    ("course_list", 6),
    ("course_regions", 3),
    ("near_curated", 3),
    ("near_osm", 3),
    ("festival", 2),
    ("poi", 2),
    ("geocode", 2),
    ("itinerary_generate", 3),
    ("me", 2),
    ("favorite_list", 2),
    ("favorite_add", 1),
    ("favorite_delete", 1),
)


def minute_order() -> tuple[str, ...]:
    """고정 가중치와 현재 배정량의 차이가 큰 항목부터 한 슬롯씩 배정한다."""
    total = sum(count for _, count in REQUESTS_PER_MINUTE)
    if total != 60:
        raise ValueError("승인된 분당 요청 수는 60이어야 합니다.")
    assigned: Counter[str] = Counter()
    order = []
    for slot in range(total):
        remaining = [(name, count) for name, count in REQUESTS_PER_MINUTE
                     if assigned[name] < count]
        name, _ = max(remaining, key=lambda row: (slot + 1) * row[1] - assigned[row[0]] * total)
        order.append(name)
        assigned[name] += 1
    # 쓰기 최대 합격시간(3초)보다 넉넉히 떨어뜨려 정상 add 전에 delete가 도착하지 않게 한다.
    for case_id, target_slot in (
        ("favorite_add", FAVORITE_ADD_SLOT),
        ("favorite_delete", FAVORITE_DELETE_SLOT),
    ):
        current_slot = order.index(case_id)
        order[current_slot], order[target_slot] = order[target_slot], order[current_slot]
    if order.index("favorite_add") >= order.index("favorite_delete"):
        raise ValueError("즐겨찾기 추가는 같은 분의 삭제보다 먼저여야 합니다.")
    return tuple(order)


def build_schedule() -> list[dict]:
    order = minute_order()
    schedule = []
    sequence = 0
    for phase, minutes in (("warmup", WARMUP_MINUTES), ("measurement", MEASUREMENT_MINUTES)):
        for minute in range(minutes):
            additions = {}
            for case_id in order:
                sequence += 1
                item = {
                    "sequence": sequence,
                    "phase": phase,
                    "phaseMinute": minute,
                    "plannedOffsetMs": (sequence - 1) * 1000,
                    "caseId": case_id,
                }
                if case_id == "favorite_add":
                    additions[minute] = sequence
                if case_id == "favorite_delete":
                    item["dependsOnSequence"] = additions[minute]
                schedule.append(item)
    return schedule


def schedule_sha256(schedule: list[dict]) -> str:
    # 비밀값 없는 내부 도착 시간표의 동일성만 나타낸다. HTTP 입력 세트 hash가 아니다.
    encoded = json.dumps(schedule, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()
    return hashlib.sha256(encoded).hexdigest()


def plan_summary(schedule: list[dict]) -> dict:
    by_phase = {
        phase: dict(sorted(Counter(row["caseId"] for row in schedule if row["phase"] == phase).items()))
        for phase in ("warmup", "measurement")
    }
    return {
        "mode": "offline_arrival_plan",
        "loadExecuted": False,
        "fullRequestSetFrozen": False,
        "readyForLoad": False,
        "warmupMinutes": WARMUP_MINUTES,
        "measurementMinutes": MEASUREMENT_MINUTES,
        "requestsPerMinute": 60,
        "maxInFlight": MAX_IN_FLIGHT,
        "scheduledRequests": len(schedule),
        "countsByPhase": by_phase,
        "scheduleSha256": schedule_sha256(schedule),
        "blockingRequirements": [
            "guard_pr_review_deployment_and_staging_activation",
            "private_authentication_and_account_isolation_execution",
            "approved_http_inputs_and_expected_results",
            "runner_pr_review_and_resource_monitoring",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--include-schedule", action="store_true", help="비밀값 없는 전체 도착 시간표도 출력")
    args = parser.parse_args()
    schedule = build_schedule()
    output = plan_summary(schedule)
    if args.include_schedule:
        output["schedule"] = schedule
    print(json.dumps(output, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
