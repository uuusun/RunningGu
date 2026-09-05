#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("summarize-upstream-load-guard.py")
SPEC = importlib.util.spec_from_file_location("summarize_upstream_load_guard", MODULE_PATH)
assert SPEC and SPEC.loader
summary_module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(summary_module)


def guard_line(
    *,
    endpoint: str,
    endpoint_count: int,
    provider: str,
    provider_count: int,
    event: str = "COMPLETE",
    result_class: str = "HTTP_2XX",
) -> str:
    endpoint_limit = 2_000 if provider == "KAKAO" else 100
    provider_limit = 5_000 if provider == "KAKAO" else "NONE"
    return (
        "Sep 05 backend[1]: runId=load-20260905 "
        f"provider={provider} endpoint={endpoint} event={event} "
        f"class={result_class} elapsedMs=12 endpointCount={endpoint_count} "
        f"endpointLimit={endpoint_limit} providerCount={provider_count} "
        f"providerLimit={provider_limit}"
    )


class UpstreamLoadGuardSummaryTest(unittest.TestCase):
    def test_passes_complete_2xx_counters_and_is_deterministic(self) -> None:
        lines = [
            guard_line(
                endpoint="KTO_SEARCH_FESTIVAL",
                endpoint_count=1,
                provider="KTO",
                provider_count=1,
            ),
            guard_line(
                endpoint="KAKAO_KEYWORD",
                endpoint_count=2,
                provider="KAKAO",
                provider_count=2,
            ),
            guard_line(
                endpoint="KAKAO_KEYWORD",
                endpoint_count=1,
                provider="KAKAO",
                provider_count=1,
            ),
        ]

        first = summary_module.summarize(
            lines, "load-20260905", "KTO_SEARCH_FESTIVAL"
        )
        second = summary_module.summarize(
            lines, "load-20260905", "KTO_SEARCH_FESTIVAL"
        )

        self.assertTrue(first["passed"])
        self.assertEqual(first, second)
        self.assertEqual(first["counterGaps"], 0)
        self.assertEqual(first["overLimit"], 0)

    def test_zero_log_or_missing_preflight_endpoint_fails(self) -> None:
        empty = summary_module.summarize([], "load-20260905")
        missing = summary_module.summarize(
            [
                guard_line(
                    endpoint="KTO_DURUNUBI_COURSE",
                    endpoint_count=1,
                    provider="KTO",
                    provider_count=1,
                )
            ],
            "load-20260905",
            "KTO_SEARCH_FESTIVAL",
        )

        self.assertFalse(empty["passed"])
        self.assertFalse(missing["passed"])
        self.assertFalse(missing["requiredEndpointSeen"])

    def test_trip_block_and_non_2xx_each_fail(self) -> None:
        for event, result_class in (
            ("TRIP", "HTTP_429"),
            ("BLOCK", "GLOBAL_TRIPPED"),
            ("COMPLETE", "HTTP_4XX"),
        ):
            with self.subTest(event=event, result_class=result_class):
                summary = summary_module.summarize(
                    [
                        guard_line(
                            endpoint="KAKAO_CATEGORY",
                            endpoint_count=1,
                            provider="KAKAO",
                            provider_count=1,
                            event=event,
                            result_class=result_class,
                        )
                    ],
                    "load-20260905",
                )
                self.assertFalse(summary["passed"])

    def test_counter_gap_and_limit_mismatch_fail(self) -> None:
        gap = summary_module.summarize(
            [
                guard_line(
                    endpoint="KAKAO_KEYWORD",
                    endpoint_count=2,
                    provider="KAKAO",
                    provider_count=2,
                )
            ],
            "load-20260905",
        )
        wrong_limit_line = guard_line(
            endpoint="KTO_SEARCH_FESTIVAL",
            endpoint_count=1,
            provider="KTO",
            provider_count=1,
        ).replace("endpointLimit=100", "endpointLimit=101")
        wrong_limit = summary_module.summarize(
            [wrong_limit_line], "load-20260905"
        )

        self.assertFalse(gap["passed"])
        self.assertGreater(gap["counterGaps"], 0)
        self.assertFalse(wrong_limit["passed"])
        self.assertEqual(wrong_limit["malformedLines"], 1)

    def test_extra_or_duplicate_field_is_malformed_and_untrusted_text_is_not_rendered(self) -> None:
        line = guard_line(
            endpoint="KTO_SEARCH_FESTIVAL",
            endpoint_count=1,
            provider="KTO",
            provider_count=1,
        )
        for suffix in (
            " query=NEVER_RENDER_SECRET",
            " endpointCount=1",
            " arbitrary-text",
        ):
            with self.subTest(suffix=suffix):
                summary = summary_module.summarize(
                    [line + suffix], "load-20260905"
                )
                rendered = json.dumps(summary, ensure_ascii=False)

                self.assertFalse(summary["passed"])
                self.assertEqual(summary["malformedLines"], 1)
                self.assertNotIn("NEVER_RENDER_SECRET", rendered)

    def test_logger_prefix_is_allowed_but_field_order_is_fixed(self) -> None:
        line = guard_line(
            endpoint="KTO_SEARCH_FESTIVAL",
            endpoint_count=1,
            provider="KTO",
            provider_count=1,
        )
        valid = summary_module.summarize([line], "load-20260905")
        reordered = summary_module.summarize(
            [line.replace(
                "provider=KTO endpoint=KTO_SEARCH_FESTIVAL",
                "endpoint=KTO_SEARCH_FESTIVAL provider=KTO",
            )],
            "load-20260905",
        )

        self.assertTrue(valid["passed"])
        self.assertFalse(reordered["passed"])
        self.assertEqual(reordered["malformedLines"], 1)

    def test_exact_eight_endpoint_contract_is_fixed(self) -> None:
        self.assertEqual(
            list(summary_module.ENDPOINT_LIMITS),
            [
                "KAKAO_CATEGORY",
                "KAKAO_KEYWORD",
                "KAKAO_ACCESS_TOKEN_INFO",
                "KAKAO_USER_ME",
                "KTO_SEARCH_FESTIVAL",
                "KTO_KOR_LOCATION",
                "KTO_WELLNESS_LOCATION",
                "KTO_DURUNUBI_COURSE",
            ],
        )


if __name__ == "__main__":
    unittest.main()
