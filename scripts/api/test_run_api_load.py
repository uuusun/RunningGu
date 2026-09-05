from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path
from unittest import mock

import plan_api_arrivals as arrivals
import run_api_load as runner


FIXTURE_PATH = Path(__file__).parent / "fixtures" / "staging-api-load-v1.approved.json"


def fixture_value() -> dict:
    return json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))


def payload_for(case_id: str, fixture: dict, user_id: int = 1) -> object | None:
    contest = fixture["inputs"]["contest"]
    if case_id == "contest_list":
        return {"items": [{"id": contest["id"], "active": True}]}
    if case_id == "contest_detail":
        return {
            "id": contest["id"],
            "contestDate": contest["date"],
            "active": True,
            "events": [contest["event"]],
            "lat": 35.0,
            "lng": 126.0,
        }
    if case_id == "contest_closing":
        return {"items": [{"id": contest["id"], "regStatus": "OPEN"}]}
    if case_id == "contest_daily":
        return {"counts": [{"date": fixture["inputs"]["yearMonth"] + "-11", "count": 1}]}
    if case_id == "course_list":
        return {"content": [{"dataSource": "API_GPX"}], "page": {}}
    if case_id == "course_regions":
        return {"items": [{"region": "부산", "count": 1}]}
    if case_id == "near_curated":
        return {"items": [{"kind": "ROUTE", "dataSource": "GPX_ONLY"}], "degradedSources": []}
    if case_id == "near_osm":
        return {"items": [{"kind": "ROUTE", "dataSource": "OSM_GENERATED"}], "degradedSources": []}
    if case_id == "festival":
        return {"items": [{"contentId": "fixture"}]}
    if case_id == "poi":
        return {"source": "LIVE", "items": [{"provider": "KTO", "name": "fixture"}]}
    if case_id == "geocode":
        return {"name": "fixture", "address": "fixture", "lat": 35.0, "lng": 129.0}
    if case_id == "itinerary_generate":
        itinerary = fixture["inputs"]["itinerary"]
        return {
            "contestId": itinerary["contestId"],
            "event": itinerary["event"],
            "startDate": itinerary["startDate"],
            "endDate": itinerary["endDate"],
            "days": [
                {"blocks": []},
                {"blocks": [{"blockType": "RACE"}]},
                {"blocks": []},
            ],
        }
    if case_id == "me":
        return {"id": user_id}
    if case_id == "favorite_list":
        return {"content": [], "page": {}}
    if case_id in {"favorite_add", "favorite_delete"}:
        return None
    raise AssertionError(case_id)


class FixtureContractTest(unittest.TestCase):
    def test_approved_fixture_has_independent_golden_hash_and_candidate_fails_closed(self):
        fixture, digest = runner.load_fixture(FIXTURE_PATH)

        self.assertEqual("APPROVED", fixture["approvalStatus"])
        self.assertEqual(
            "6811484a70d406e555c9bdce8273744ef5be597795a73186c9ed9ea42a818ef1",
            digest,
        )
        runner.validate_fixture(fixture, require_approved=True)

        candidate = copy.deepcopy(fixture)
        candidate["approvalStatus"] = "CANDIDATE"
        with self.assertRaisesRegex(runner.LoadError, "request_set_not_approved"):
            runner.validate_fixture(candidate, require_approved=True)

    def test_origin_extra_field_and_cross_field_mutation_fail(self):
        for mutate in (
            lambda value: value.__setitem__("origin", "https://example.test"),
            lambda value: value.__setitem__("unexpected", True),
            lambda value: value["inputs"]["itinerary"].__setitem__("contestId", 999),
            lambda value: value["inputs"]["nearOsm"].__setitem__("targetKm", 5.1),
        ):
            with self.subTest(mutate=mutate):
                value = fixture_value()
                mutate(value)
                with self.assertRaises((runner.LoadError, ValueError)):
                    runner.validate_fixture(value)

    def test_dry_run_never_claims_execution_and_reports_approved_readiness(self):
        fixture = runner.validate_fixture(fixture_value())
        summary = runner.dry_run_summary(fixture, runner.canonical_sha256(fixture))
        rendered = json.dumps(summary, ensure_ascii=False)

        self.assertFalse(summary["loadExecuted"])
        self.assertTrue(summary["readyForLoad"])
        self.assertEqual(2_100, summary["plannedRequests"])
        self.assertEqual(runner.MAX_DISPATCH_DELAY_MS, summary["dispatchDelayLimitMs"])
        self.assertNotIn("해운대", rendered)
        self.assertNotIn("35.385905", rendered)


class RequestContractTest(unittest.TestCase):
    def setUp(self):
        self.fixture = runner.validate_fixture(fixture_value())
        self.sessions = {
            "A": runner.AccountSession("A", 1, "ACCESS_A", "REFRESH_A"),
            "B": runner.AccountSession("B", 2, "ACCESS_B", "REFRESH_B"),
        }

    def test_case_list_matches_the_approved_arrival_plan_exactly(self):
        self.assertEqual(
            {name for name, _ in arrivals.REQUESTS_PER_MINUTE},
            set(runner.CASE_GROUPS),
        )

    def test_requests_use_only_relative_paths_and_fixed_methods(self):
        methods = {}
        for case_id in runner.CASE_GROUPS:
            label = "A" if case_id in {"me", "favorite_list", "favorite_add", "favorite_delete"} else None
            spec = runner.request_spec(case_id, self.fixture, label)
            self.assertTrue(spec.path.startswith("/api/"))
            self.assertNotIn("://", spec.path)
            self.assertEqual(runner.CASE_GROUPS[case_id], spec.group)
            methods[case_id] = spec.method
        self.assertEqual("PUT", methods["favorite_add"])
        self.assertEqual("DELETE", methods["favorite_delete"])
        self.assertEqual("POST", methods["itinerary_generate"])
        self.assertTrue(runner.request_spec("geocode", self.fixture).path.endswith("%EC%9E%A5"))

    def test_all_sixteen_success_shapes_are_checked(self):
        for case_id in runner.CASE_GROUPS:
            with self.subTest(case_id=case_id):
                label = "A" if case_id in {"me", "favorite_list", "favorite_add", "favorite_delete"} else None
                spec = runner.request_spec(case_id, self.fixture, label)
                account = self.sessions.get(label)
                exchange = runner.Exchange(
                    spec.expected_status,
                    payload_for(case_id, self.fixture, 1),
                    12.0,
                    100,
                )
                runner.validate_exchange(spec, exchange, self.fixture, account)

    def test_wrong_source_degraded_and_other_user_fail(self):
        cases = (
            (
                "near_curated",
                {"items": [{"kind": "ROUTE", "dataSource": "OSM_GENERATED"}], "degradedSources": []},
                None,
            ),
            (
                "near_osm",
                {"items": [{"kind": "ROUTE", "dataSource": "OSM_GENERATED"}], "degradedSources": ["KAKAO"]},
                None,
            ),
            ("me", {"id": 2}, self.sessions["A"]),
        )
        for case_id, payload, account in cases:
            with self.subTest(case_id=case_id):
                label = "A" if case_id == "me" else None
                spec = runner.request_spec(case_id, self.fixture, label)
                with self.assertRaises(runner.LoadError):
                    runner.validate_exchange(
                        spec,
                        runner.Exchange(200, payload, 1.0, 1),
                        self.fixture,
                        account,
                    )

    def test_account_choice_is_stable_and_add_delete_share_an_account(self):
        schedule = arrivals.build_schedule()
        for deletion in (row for row in schedule if row["caseId"] == "favorite_delete"):
            addition = schedule[deletion["dependsOnSequence"] - 1]
            self.assertEqual(runner.account_for_item(addition), runner.account_for_item(deletion))


class SecretAndResultTest(unittest.TestCase):
    class FakeClock:
        def __init__(self, sleep_overrun_seconds: float):
            self.now = 0.0
            self.sleep_overrun_seconds = sleep_overrun_seconds

        def monotonic(self) -> float:
            return self.now

        def sleep(self, seconds: float) -> None:
            self.now += seconds + self.sleep_overrun_seconds

    class LoginClient:
        def __init__(self):
            self.calls = []

        def exchange(self, spec, access_token=None):
            self.calls.append(spec)
            if spec.case_id == "auth_logout":
                return runner.Exchange(204, None, 1.0, 0)
            index = len([call for call in self.calls if call.case_id == "auth_login"])
            return runner.Exchange(
                200,
                {
                    "accessToken": f"ACCESS_{index}",
                    "refreshToken": f"REFRESH_{index}",
                    "user": {"id": index},
                },
                1.0,
                10,
            )

    def test_login_secrets_are_not_part_of_session_repr(self):
        client = self.LoginClient()
        values = iter(("first@example.test", "SECRET_ONE", "second@example.test", "SECRET_TWO"))
        sessions = runner.login_sessions(client, lambda _: next(values))
        rendered = repr(sessions)

        self.assertEqual({"A", "B"}, set(sessions))
        self.assertNotIn("SECRET", rendered)
        self.assertNotIn("ACCESS", rendered)
        self.assertNotIn("REFRESH", rendered)
        self.assertNotIn("example.test", rendered)

    def test_same_account_login_is_rejected_and_both_sessions_are_logged_out(self):
        class SameAccountClient(self.LoginClient):
            def exchange(self, spec, access_token=None):
                if spec.case_id == "auth_logout":
                    self.calls.append(spec)
                    return runner.Exchange(204, None, 1.0, 0)
                self.calls.append(spec)
                return runner.Exchange(
                    200,
                    {
                        "accessToken": "ACCESS",
                        "refreshToken": "REFRESH",
                        "user": {"id": 1},
                    },
                    1.0,
                    10,
                )

        client = SameAccountClient()
        values = iter(("first@example.test", "SECRET_ONE", "second@example.test", "SECRET_TWO"))

        with self.assertRaisesRegex(runner.LoadError, "account_isolation"):
            runner.login_sessions(client, lambda _: next(values))

        self.assertEqual(
            2,
            sum(call.case_id == "auth_logout" for call in client.calls),
        )

    def test_failed_http_validation_keeps_latency_in_metrics_without_body(self):
        fixture = runner.validate_fixture(fixture_value())
        sessions = {"A": runner.AccountSession("A", 1, "TOKEN", "REFRESH")}

        class Client:
            def exchange(self, spec, access_token=None, before_send=None):
                if before_send is not None:
                    before_send()
                return runner.Exchange(500, {"detail": "NEVER_RENDER_BODY"}, 321.5, 77)

        semaphore = __import__("threading").BoundedSemaphore(1)
        semaphore.acquire()
        item = {"sequence": 1, "phase": "measurement", "caseId": "contest_list"}
        result = runner.execute_task(
            item,
            runner.request_spec("contest_list", fixture),
            Client(),
            fixture,
            sessions,
            semaphore,
        )
        rendered = repr(result)

        self.assertFalse(result.success)
        self.assertEqual(321.5, result.duration_ms)
        self.assertEqual("unexpected_http", result.error)
        self.assertNotIn("NEVER_RENDER_BODY", rendered)

    def test_summary_counts_failures_and_applies_all_group_limits(self):
        schedule = arrivals.build_schedule()
        results = [
            runner.TaskResult(
                item["sequence"],
                item["phase"],
                item["caseId"],
                runner.CASE_GROUPS[item["caseId"]],
                True,
                10.0,
                1,
                None,
            )
            for item in schedule
        ]
        summary = runner.summarize_run(
            "run-1",
            "a" * 64,
            schedule,
            results,
            [
                {"operation": "refresh_before_load", "account": "A", "success": True},
                {"operation": "refresh_before_load", "account": "B", "success": True},
                {"operation": "refresh", "account": "A", "success": True},
                {"operation": "refresh", "account": "B", "success": True},
            ],
            len(schedule),
            [
                {"caseId": case_id, "durationMs": 1.0}
                for case_id in runner.CASE_GROUPS
                if case_id not in {"favorite_add", "favorite_delete"}
            ],
            True,
            True,
            1.0,
            2.0,
        )

        self.assertTrue(summary["passed"])
        self.assertEqual(1_800, summary["measurementSuccessful"])
        self.assertEqual(
            [
                {"phase": "warmup", "planned": 300, "completed": 300, "successful": 300, "failed": 0},
                {"phase": "measurement", "planned": 1_800, "completed": 1_800, "successful": 1_800, "failed": 0},
            ],
            summary["phases"],
        )
        self.assertTrue(all(group["passed"] for group in summary["groups"]))
        self.assertTrue(all(scenario["passed"] for scenario in summary["heavyScenarios"]))
        self.assertEqual(runner.MAX_DISPATCH_DELAY_MS, summary["dispatchDelayLimitMs"])
        self.assertEqual(0.0, summary["maxDispatchDelayMs"])
        self.assertEqual(0, summary["lateDispatches"])

    def test_schedule_does_not_catch_up_after_large_clock_delay(self):
        fixture = runner.validate_fixture(fixture_value())
        clock = self.FakeClock(120.0)
        client = mock.Mock()
        schedule = [{
            "sequence": 1,
            "phase": "measurement",
            "phaseMinute": 0,
            "plannedOffsetMs": 0,
            "caseId": "contest_list",
        }]

        results, maintenance, dispatched = runner.execute_schedule(
            client,
            fixture,
            {},
            schedule,
            monotonic=clock.monotonic,
            sleep=clock.sleep,
            refresh_at_seconds=None,
        )

        self.assertEqual([], maintenance)
        self.assertEqual(0, dispatched)
        self.assertEqual(1, len(results))
        self.assertEqual("missed_start", results[0].error)
        self.assertEqual(120_000.0, results[0].dispatch_delay_ms)
        self.assertFalse(results[0].dispatched)
        client.exchange.assert_not_called()

        full_schedule = arrivals.build_schedule()
        full_results = [
            runner.TaskResult(
                item["sequence"],
                item["phase"],
                item["caseId"],
                runner.CASE_GROUPS[item["caseId"]],
                True,
                10.0,
                1,
                None,
            )
            for item in full_schedule
        ]
        full_results[0] = runner.TaskResult(
            full_schedule[0]["sequence"],
            full_schedule[0]["phase"],
            full_schedule[0]["caseId"],
            runner.CASE_GROUPS[full_schedule[0]["caseId"]],
            False,
            None,
            0,
            "missed_start",
            120_000.0,
            False,
        )
        preflight = [
            {"caseId": case_id, "durationMs": 1.0}
            for case_id in runner.CASE_GROUPS
            if case_id not in {"favorite_add", "favorite_delete"}
        ]
        maintenance = [
            {"operation": "refresh_before_load", "account": "A", "success": True},
            {"operation": "refresh_before_load", "account": "B", "success": True},
            {"operation": "refresh", "account": "A", "success": True},
            {"operation": "refresh", "account": "B", "success": True},
        ]
        summary = runner.summarize_run(
            "run-1",
            "a" * 64,
            full_schedule,
            full_results,
            maintenance,
            len(full_schedule) - 1,
            preflight,
            True,
            True,
            1.0,
            2.0,
        )

        self.assertFalse(summary["passed"])
        self.assertEqual(1, summary["missedStarts"])
        self.assertEqual(1, summary["lateDispatches"])
        self.assertEqual(120_000.0, summary["maxDispatchDelayMs"])

    def test_dispatch_delay_boundary_allows_500ms_and_rejects_above_it(self):
        fixture = runner.validate_fixture(fixture_value())
        schedule = [{
            "sequence": 1,
            "phase": "measurement",
            "phaseMinute": 0,
            "plannedOffsetMs": 0,
            "caseId": "contest_list",
        }]

        class SuccessClient:
            def __init__(self):
                self.calls = 0

            def exchange(self, spec, access_token=None, before_send=None):
                if before_send is not None:
                    before_send()
                self.calls += 1
                return runner.Exchange(
                    spec.expected_status,
                    payload_for(spec.case_id, fixture),
                    1.0,
                    1,
                )

        allowed_clock = self.FakeClock(runner.MAX_DISPATCH_DELAY_MS / 1000)
        allowed_client = SuccessClient()
        allowed, _, allowed_dispatched = runner.execute_schedule(
            allowed_client,
            fixture,
            {},
            schedule,
            monotonic=allowed_clock.monotonic,
            sleep=allowed_clock.sleep,
            refresh_at_seconds=None,
        )

        self.assertEqual(1, allowed_dispatched)
        self.assertEqual(1, allowed_client.calls)
        self.assertTrue(allowed[0].success)
        self.assertEqual(runner.MAX_DISPATCH_DELAY_MS, allowed[0].dispatch_delay_ms)

        rejected_clock = self.FakeClock((runner.MAX_DISPATCH_DELAY_MS + 1) / 1000)
        client = mock.Mock()
        rejected, _, rejected_dispatched = runner.execute_schedule(
            client,
            fixture,
            {},
            schedule,
            monotonic=rejected_clock.monotonic,
            sleep=rejected_clock.sleep,
            refresh_at_seconds=None,
        )

        self.assertEqual(0, rejected_dispatched)
        self.assertEqual("missed_start", rejected[0].error)
        self.assertEqual(runner.MAX_DISPATCH_DELAY_MS + 1, rejected[0].dispatch_delay_ms)
        client.exchange.assert_not_called()

    def test_worker_delay_after_submission_is_rejected_before_http(self):
        fixture = runner.validate_fixture(fixture_value())
        schedule = [{
            "sequence": 1,
            "phase": "measurement",
            "phaseMinute": 0,
            "plannedOffsetMs": 0,
            "caseId": "contest_list",
        }]

        class WorkerDelayClock:
            def __init__(self):
                self.now = 0.0
                self.calls = 0

            def monotonic(self):
                self.calls += 1
                if self.calls == 4:
                    self.now += (runner.MAX_DISPATCH_DELAY_MS + 1) / 1000
                return self.now

            def sleep(self, seconds):
                self.now += seconds

        clock = WorkerDelayClock()
        client = runner.HttpClient()
        opener = mock.Mock()
        client.opener = mock.Mock(return_value=opener)
        results, _, dispatched = runner.execute_schedule(
            client,
            fixture,
            {},
            schedule,
            monotonic=clock.monotonic,
            sleep=clock.sleep,
            refresh_at_seconds=None,
        )

        self.assertEqual(0, dispatched)
        self.assertEqual("missed_start", results[0].error)
        self.assertEqual(runner.MAX_DISPATCH_DELAY_MS + 1, results[0].dispatch_delay_ms)
        self.assertFalse(results[0].dispatched)
        client.opener.assert_called_once_with()
        opener.open.assert_not_called()
        self.assertFalse(client.network_started)

    def test_one_slow_heavy_scenario_cannot_hide_in_combined_group(self):
        schedule = arrivals.build_schedule()
        results = []
        slow_seen = 0
        for item in schedule:
            duration = 10.0
            if item["phase"] == "measurement" and item["caseId"] == "near_curated":
                slow_seen += 1
                if slow_seen <= 5:
                    duration = 6_000.0
            results.append(runner.TaskResult(
                item["sequence"],
                item["phase"],
                item["caseId"],
                runner.CASE_GROUPS[item["caseId"]],
                True,
                duration,
                1,
                None,
            ))
        maintenance = [
            {"operation": "refresh_before_load", "account": "A", "success": True},
            {"operation": "refresh_before_load", "account": "B", "success": True},
            {"operation": "refresh", "account": "A", "success": True},
            {"operation": "refresh", "account": "B", "success": True},
        ]
        preflight = [
            {"caseId": case_id, "durationMs": 1.0}
            for case_id in runner.CASE_GROUPS
            if case_id not in {"favorite_add", "favorite_delete"}
        ]

        summary = runner.summarize_run(
            "run-1",
            "a" * 64,
            schedule,
            results,
            maintenance,
            len(schedule),
            preflight,
            True,
            True,
            1.0,
            2.0,
        )

        combined = next(group for group in summary["groups"] if group["group"] == "heavy")
        curated = next(row for row in summary["heavyScenarios"] if row["caseId"] == "near_curated")
        self.assertTrue(combined["passed"])
        self.assertFalse(curated["passed"])
        self.assertFalse(summary["passed"])

    def test_summary_rejects_missing_or_duplicate_preflight_and_refresh(self):
        schedule = arrivals.build_schedule()
        results = [
            runner.TaskResult(
                item["sequence"],
                item["phase"],
                item["caseId"],
                runner.CASE_GROUPS[item["caseId"]],
                True,
                10.0,
                1,
                None,
            )
            for item in schedule
        ]
        preflight = [
            {"caseId": case_id, "durationMs": 1.0}
            for case_id in runner.CASE_GROUPS
            if case_id not in {"favorite_add", "favorite_delete"}
        ]
        maintenance = [
            {"operation": "refresh_before_load", "account": "A", "success": True},
            {"operation": "refresh_before_load", "account": "B", "success": True},
            {"operation": "refresh", "account": "A", "success": True},
            {"operation": "refresh", "account": "B", "success": True},
        ]

        for changed_preflight, changed_maintenance in (
            (preflight[:-1], maintenance),
            (preflight[:-1] + [preflight[0]], maintenance),
            (preflight, maintenance[:-1]),
            (preflight, maintenance[:-1] + [maintenance[0]]),
        ):
            with self.subTest(
                preflight=len(changed_preflight),
                maintenance=changed_maintenance,
            ):
                summary = runner.summarize_run(
                    "run-1",
                    "a" * 64,
                    schedule,
                    results,
                    changed_maintenance,
                    len(schedule),
                    changed_preflight,
                    True,
                    True,
                    1.0,
                    2.0,
                )
                self.assertFalse(summary["passed"])


if __name__ == "__main__":
    unittest.main()
