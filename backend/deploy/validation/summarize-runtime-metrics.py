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


def values(line: str) -> dict[str, str]:
    return {match.group("key"): match.group("value") for match in KEY_VALUE.finditer(line)}


def summarize(lines: list[str]) -> dict[str, object]:
    headers: dict[str, str] = {}
    samples: list[dict[str, str]] = []
    systemd: dict[str, list[int]] = {}
    oom_killed = 0
    unhealthy_containers = 0

    for line in lines:
        if line.startswith("duration_seconds="):
            headers.update(values(line))
        elif line.startswith("sample sequence="):
            samples.append(values(line))
        elif line.startswith("systemd service="):
            item = values(line)
            service = item.get("service", "unknown")
            systemd.setdefault(service, []).append(int(item.get("NRestarts", "0")))
        elif line.startswith("container service="):
            item = values(line)
            if item.get("oom_killed") == "true":
                oom_killed += 1
            if item.get("present") != "true" or item.get("status") != "running":
                unhealthy_containers += 1

    duration = int(headers.get("duration_seconds", "0"))
    interval = int(headers.get("interval_seconds", "0"))
    expected_samples = math.ceil(duration / interval) if duration > 0 and interval > 0 else 0
    memory_percent = [float(item["mem_available_percent"]) for item in samples]
    swap_used = [int(item["swap_used_kib"]) for item in samples]
    pswpin = [int(item["pswpin"]) for item in samples]
    pswpout = [int(item["pswpout"]) for item in samples]
    restart_growth = {
        service: max(counts) - min(counts)
        for service, counts in systemd.items()
        if counts
    }

    minimum_memory = min(memory_percent) if memory_percent else None
    maximum_swap_growth = (
        max(value - swap_used[0] for value in swap_used) if swap_used else None
    )
    pswpin_growth = pswpin[-1] - pswpin[0] if pswpin else None
    pswpout_growth = pswpout[-1] - pswpout[0] if pswpout else None
    passed = (
        expected_samples > 0
        and len(samples) == expected_samples
        and minimum_memory is not None
        and minimum_memory >= 20.0
        and maximum_swap_growth == 0
        and pswpin_growth == 0
        and pswpout_growth == 0
        and oom_killed == 0
        and unhealthy_containers == 0
        and all(growth == 0 for growth in restart_growth.values())
    )
    return {
        "expectedSamples": expected_samples,
        "actualSamples": len(samples),
        "minimumMemAvailablePercent": minimum_memory,
        "maximumSwapGrowthKiB": maximum_swap_growth,
        "pswpinGrowth": pswpin_growth,
        "pswpoutGrowth": pswpout_growth,
        "systemdRestartGrowth": restart_growth,
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
