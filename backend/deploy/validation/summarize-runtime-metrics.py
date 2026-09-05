#!/usr/bin/env python3
"""collect-runtime-metrics.sh 결과에서 기계 판정 가능한 §9.2 항목을 요약한다."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path


KEY_VALUE = re.compile(r"(?P<key>[a-zA-Z_]+)=(?P<value>[^ ]+)")
EXPECTED_SYSTEMD_SERVICES = (
    "runninggu-backend.service",
    "runninggu-graphhopper.service",
)
EXPECTED_CONTAINERS = ("graphhopper", "postgres")


def values(line: str) -> dict[str, str]:
    return {match.group("key"): match.group("value") for match in KEY_VALUE.finditer(line)}


def summarize(lines: list[str]) -> dict[str, object]:
    headers: dict[str, str] = {}
    sample_groups: list[dict[str, object]] = []
    current_group: dict[str, object] | None = None
    malformed_observations = 0

    for line in lines:
        if line.startswith("duration_seconds="):
            headers.update(values(line))
        elif line.startswith("sample sequence="):
            if current_group is not None:
                malformed_observations += 1
            current_group = {
                "sample": values(line),
                "systemd": {},
                "containers": {},
            }
        elif line.startswith("systemd service="):
            item = values(line)
            service = item.get("service")
            if current_group is None or not service:
                malformed_observations += 1
                continue
            systemd = current_group["systemd"]
            assert isinstance(systemd, dict)
            if service in systemd:
                malformed_observations += 1
            else:
                systemd[service] = item
        elif line.startswith("container service="):
            item = values(line)
            service = item.get("service")
            if current_group is None or not service:
                malformed_observations += 1
                continue
            containers = current_group["containers"]
            assert isinstance(containers, dict)
            if service in containers:
                malformed_observations += 1
            else:
                containers[service] = item
        elif line.startswith("sample_end sequence="):
            item = values(line)
            if current_group is None:
                malformed_observations += 1
                continue
            sample = current_group["sample"]
            assert isinstance(sample, dict)
            if item.get("sequence") != sample.get("sequence"):
                malformed_observations += 1
            sample_groups.append(current_group)
            current_group = None

    if current_group is not None:
        malformed_observations += 1

    duration = int(headers.get("duration_seconds", "0"))
    interval = int(headers.get("interval_seconds", "0"))
    expected_samples = math.ceil(duration / interval) if duration > 0 and interval > 0 else 0
    memory_percent: list[float] = []
    swap_used: list[int] = []
    pswpin: list[int] = []
    pswpout: list[int] = []
    invalid_sample_values = 0
    systemd_observations: dict[str, list[dict[str, str]]] = {
        service: [] for service in EXPECTED_SYSTEMD_SERVICES
    }
    container_observations: dict[str, list[dict[str, str]]] = {
        service: [] for service in EXPECTED_CONTAINERS
    }

    for group in sample_groups:
        sample = group["sample"]
        systemd = group["systemd"]
        containers = group["containers"]
        assert isinstance(sample, dict)
        assert isinstance(systemd, dict)
        assert isinstance(containers, dict)
        try:
            memory_percent.append(float(sample["mem_available_percent"]))
            swap_used.append(int(sample["swap_used_kib"]))
            pswpin.append(int(sample["pswpin"]))
            pswpout.append(int(sample["pswpout"]))
        except (KeyError, TypeError, ValueError):
            invalid_sample_values += 1
        for service in EXPECTED_SYSTEMD_SERVICES:
            observation = systemd.get(service)
            if isinstance(observation, dict):
                systemd_observations[service].append(observation)
        for service in EXPECTED_CONTAINERS:
            observation = containers.get(service)
            if isinstance(observation, dict):
                container_observations[service].append(observation)

    def restart_growth(observations: dict[str, list[dict[str, str]]], key: str) -> dict:
        result = {}
        for service, items in observations.items():
            try:
                counts = [int(item[key]) for item in items]
            except (KeyError, TypeError, ValueError):
                result[service] = None
                continue
            result[service] = max(counts) - min(counts) if counts else None
        return result

    systemd_restart_growth = restart_growth(systemd_observations, "NRestarts")
    container_restart_growth = restart_growth(container_observations, "restart_count")
    systemd_observation_counts = {
        service: len(items) for service, items in systemd_observations.items()
    }
    container_observation_counts = {
        service: len(items) for service, items in container_observations.items()
    }
    missing_systemd_service_samples = sum(
        expected_samples - count
        for count in systemd_observation_counts.values()
        if count < expected_samples
    )
    missing_container_samples = sum(
        expected_samples - count
        for count in container_observation_counts.values()
        if count < expected_samples
    )
    unhealthy_systemd_service_samples = sum(
        item.get("ActiveState") != "active" or item.get("SubState") != "running"
        for items in systemd_observations.values()
        for item in items
    )
    oom_killed = sum(
        item.get("oom_killed") == "true"
        for items in container_observations.values()
        for item in items
    )
    unhealthy_containers = sum(
        item.get("present") != "true" or item.get("status") != "running"
        for items in container_observations.values()
        for item in items
    )

    minimum_memory = min(memory_percent) if memory_percent else None
    maximum_swap_growth = (
        max(value - swap_used[0] for value in swap_used) if swap_used else None
    )
    pswpin_growth = pswpin[-1] - pswpin[0] if pswpin else None
    pswpout_growth = pswpout[-1] - pswpout[0] if pswpout else None
    passed = (
        expected_samples > 0
        and len(sample_groups) == expected_samples
        and malformed_observations == 0
        and invalid_sample_values == 0
        and minimum_memory is not None
        and minimum_memory >= 20.0
        and maximum_swap_growth == 0
        and pswpin_growth == 0
        and pswpout_growth == 0
        and all(count == expected_samples for count in systemd_observation_counts.values())
        and all(count == expected_samples for count in container_observation_counts.values())
        and unhealthy_systemd_service_samples == 0
        and oom_killed == 0
        and unhealthy_containers == 0
        and all(growth == 0 for growth in systemd_restart_growth.values())
        and all(growth == 0 for growth in container_restart_growth.values())
    )
    return {
        "expectedSamples": expected_samples,
        "actualSamples": len(sample_groups),
        "malformedObservations": malformed_observations,
        "invalidSampleValues": invalid_sample_values,
        "minimumMemAvailablePercent": minimum_memory,
        "maximumSwapGrowthKiB": maximum_swap_growth,
        "pswpinGrowth": pswpin_growth,
        "pswpoutGrowth": pswpout_growth,
        "systemdObservationCounts": systemd_observation_counts,
        "missingSystemdServiceSamples": missing_systemd_service_samples,
        "unhealthySystemdServiceSamples": unhealthy_systemd_service_samples,
        "systemdRestartGrowth": systemd_restart_growth,
        "containerObservationCounts": container_observation_counts,
        "missingContainerSamples": missing_container_samples,
        "containerRestartGrowth": container_restart_growth,
        "oomKilledSamples": oom_killed,
        "unhealthyContainerSamples": unhealthy_containers,
        "passed": passed,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="런닝구 EC2 runtime metrics 합격 항목 요약")
    parser.add_argument("--metrics", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    summary = summarize(args.metrics.read_text(encoding="utf-8").splitlines())
    rendered = json.dumps(summary, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        if args.output.exists():
            print(f"출력 파일이 이미 있습니다: {args.output}", file=sys.stderr)
            return 2
        args.output.write_text(rendered, encoding="utf-8", newline="\n")
    print(rendered, end="")
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
