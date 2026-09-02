#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("summarize-runtime-metrics.py")
SPEC = importlib.util.spec_from_file_location("summarize_runtime_metrics", MODULE_PATH)
assert SPEC and SPEC.loader
summary_module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(summary_module)


class RuntimeMetricsSummaryTest(unittest.TestCase):
    @staticmethod
    def passing_lines() -> list[str]:
        return [
            "schema_version=1",
            "duration_seconds=10 interval_seconds=5",
            "sample sequence=0 mem_available_percent=30.000 swap_used_kib=0 pswpin=10 pswpout=20",
            "systemd service=runninggu-backend.service ActiveState=active SubState=running NRestarts=0",
            "systemd service=runninggu-graphhopper.service ActiveState=active SubState=running NRestarts=0",
            "container service=graphhopper present=true status=running oom_killed=false restart_count=0",
            "container service=postgres present=true status=running oom_killed=false restart_count=0",
            "sample sequence=1 mem_available_percent=25.000 swap_used_kib=0 pswpin=10 pswpout=20",
            "systemd service=runninggu-backend.service ActiveState=active SubState=running NRestarts=0",
            "systemd service=runninggu-graphhopper.service ActiveState=active SubState=running NRestarts=0",
            "container service=graphhopper present=true status=running oom_killed=false restart_count=0",
            "container service=postgres present=true status=running oom_killed=false restart_count=0",
        ]

    def test_passes_complete_stable_samples(self) -> None:
        summary = summary_module.summarize(self.passing_lines())

        self.assertEqual(summary["expectedSamples"], 2)
        self.assertEqual(summary["minimumMemAvailablePercent"], 25.0)
        self.assertTrue(summary["passed"])

    def test_fails_memory_swap_restart_oom_and_unhealthy_container(self) -> None:
        lines = self.passing_lines()
        lines[7] = (
            "sample sequence=1 mem_available_percent=19.999 "
            "swap_used_kib=4 pswpin=11 pswpout=21"
        )
        lines[9] = (
            "systemd service=runninggu-graphhopper.service "
            "ActiveState=active SubState=running NRestarts=1"
        )
        lines[10] = (
            "container service=graphhopper present=true status=exited "
            "oom_killed=true restart_count=1"
        )

        summary = summary_module.summarize(lines)

        self.assertEqual(summary["minimumMemAvailablePercent"], 19.999)
        self.assertEqual(summary["maximumSwapGrowthKiB"], 4)
        self.assertEqual(summary["pswpinGrowth"], 1)
        self.assertEqual(summary["systemdRestartGrowth"]["runninggu-graphhopper.service"], 1)
        self.assertEqual(summary["oomKilledSamples"], 1)
        self.assertEqual(summary["unhealthyContainerSamples"], 1)
        self.assertFalse(summary["passed"])


if __name__ == "__main__":
    unittest.main()
