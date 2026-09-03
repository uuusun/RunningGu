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
            "sample_end sequence=0",
            "sample sequence=1 mem_available_percent=25.000 swap_used_kib=0 pswpin=10 pswpout=20",
            "systemd service=runninggu-backend.service ActiveState=active SubState=running NRestarts=0",
            "systemd service=runninggu-graphhopper.service ActiveState=active SubState=running NRestarts=0",
            "container service=graphhopper present=true status=running oom_killed=false restart_count=0",
            "container service=postgres present=true status=running oom_killed=false restart_count=0",
            "sample_end sequence=1",
        ]

    def test_passes_complete_stable_samples(self) -> None:
        summary = summary_module.summarize(self.passing_lines())

        self.assertEqual(summary["expectedSamples"], 2)
        self.assertEqual(summary["minimumMemAvailablePercent"], 25.0)
        self.assertTrue(summary["passed"])

    def test_fails_memory_swap_restart_oom_and_unhealthy_container(self) -> None:
        lines = self.passing_lines()
        lines[8] = (
            "sample sequence=1 mem_available_percent=19.999 "
            "swap_used_kib=4 pswpin=11 pswpout=21"
        )
        lines[10] = (
            "systemd service=runninggu-graphhopper.service "
            "ActiveState=active SubState=running NRestarts=1"
        )
        lines[11] = (
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

    def test_fails_inactive_systemd_service(self) -> None:
        lines = self.passing_lines()
        lines[4] = (
            "systemd service=runninggu-graphhopper.service "
            "ActiveState=inactive SubState=dead NRestarts=0"
        )

        summary = summary_module.summarize(lines)

        self.assertEqual(summary["unhealthySystemdServiceSamples"], 1)
        self.assertFalse(summary["passed"])

    def test_fails_when_expected_systemd_service_is_missing(self) -> None:
        lines = [
            line for line in self.passing_lines()
            if "service=runninggu-graphhopper.service" not in line
        ]

        summary = summary_module.summarize(lines)

        self.assertEqual(summary["missingSystemdServiceSamples"], 2)
        self.assertEqual(
            summary["systemdObservationCounts"]["runninggu-graphhopper.service"],
            0,
        )
        self.assertFalse(summary["passed"])

    def test_fails_when_container_restart_count_increases(self) -> None:
        lines = self.passing_lines()
        lines[11] = (
            "container service=graphhopper present=true status=running "
            "oom_killed=false restart_count=1"
        )

        summary = summary_module.summarize(lines)

        self.assertEqual(summary["containerRestartGrowth"]["graphhopper"], 1)
        self.assertFalse(summary["passed"])

    def test_fails_when_expected_container_observation_is_missing(self) -> None:
        lines = [
            line for line in self.passing_lines()
            if "container service=postgres" not in line
        ]

        summary = summary_module.summarize(lines)

        self.assertEqual(summary["missingContainerSamples"], 2)
        self.assertEqual(summary["containerObservationCounts"]["postgres"], 0)
        self.assertFalse(summary["passed"])


if __name__ == "__main__":
    unittest.main()
