"""소량 점검의 호출 범위·중단 증거·정리·민감정보 비기록 검증."""
import datetime as dt
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import run_api_load as api
import run_api_smoke as smoke
from test_run_api_load import fixture_value, payload_for


class FakeClient:
    def __init__(self, error_at=None, error=None, slow=False, existing=False):
        self.error_at, self.error, self.slow = error_at, error, slow
        self.favorites = {"A": set(), "B": set()}
        self.calls = []
        self.existing = existing
        self.login_count = 0

    def exchange(self, spec, access_token=None, before_send=None):
        self.calls.append(spec.case_id)
        if spec.case_id == self.error_at:
            raise self.error
        if spec.case_id == "auth_login":
            self.login_count += 1
            payload = {"user": {"id": self.login_count}, "accessToken": "TOKEN_SECRET", "refreshToken": "REFRESH_SECRET"}
        elif spec.case_id == "auth_logout":
            payload = None
        elif spec.case_id in {"favorite_add", "favorite_delete", "favorite_list"}:
            target = fixture_value()["inputs"]["favoriteContestId"]
            values = self.favorites[spec.account_label]
            if spec.case_id == "favorite_add":
                values.add(target)
            if spec.case_id == "favorite_delete":
                values.discard(target)
            payload = {"content": [{"id": value} for value in values | ({target} if self.existing else set())], "page": {}} if spec.case_id == "favorite_list" else None
        else:
            payload = payload_for(spec.case_id, fixture_value())
        return api.Exchange(spec.expected_status, payload, 4000.0 if self.slow and spec.case_id == "contest_list" else 12.0, 100)


class SmokeTest(unittest.TestCase):
    def gate(self):
        return {"runId": "smoke-test", "guardPreflightPassed": True, "metricsStarted": True,
                "actualSendingVerified": True,
                "expiresAt": (dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=10)).isoformat()}

    def execute(self, client, directory, gate=None):
        return smoke.execute(fixture_value(), "smoke-test", Path(directory) / "evidence", self.gate() if gate is None else gate,
                             secret_prompt=lambda _: "INPUT_SECRET", client_factory=lambda: client)

    def test_success_runs_each_case_once_without_load_and_excludes_secrets(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(api, "execute_schedule", side_effect=AssertionError("full_load_forbidden")):
            client = FakeClient()
            result = self.execute(client, directory)
            self.assertEqual("completed", result["status"])
            self.assertEqual(14, len(result["completedCases"]))
            self.assertEqual(1, client.calls.count("near_osm"))
            self.assertEqual(1, client.calls.count("itinerary_generate"))
            self.assertEqual(2, client.calls.count("auth_logout"))
            self.assertTrue(result["accountIsolationPassed"])
            self.assertTrue(result["cleanupSucceeded"])
            self.assertEqual({"A": set(), "B": set()}, client.favorites)
            text = (Path(directory) / "evidence/api-smoke-summary.json").read_text(encoding="utf-8")
            for secret in ("INPUT_SECRET", "TOKEN_SECRET", "REFRESH_SECRET", "35.385905"):
                self.assertNotIn(secret, text)
            self.assertFalse(result["scheduledLoadExecuted"])

    def test_interrupt_persists_prior_cases_and_interrupted_request_then_logs_out(self):
        with tempfile.TemporaryDirectory() as directory:
            client = FakeClient("near_osm", KeyboardInterrupt())
            result = self.execute(client, directory)
            stored = json.loads((Path(directory) / "evidence/api-smoke-summary.json").read_text(encoding="utf-8"))
            self.assertEqual("interrupted", stored["status"])
            self.assertEqual(7, len(result["completedCases"]))
            self.assertTrue(any(row["state"] == "interrupted" for row in stored["requests"]))
            self.assertEqual(2, client.calls.count("auth_logout"))
            self.assertEqual(0, client.calls.count("favorite_add"))

    def test_provider_error_is_not_retried_and_raw_exception_is_not_saved(self):
        with tempfile.TemporaryDirectory() as directory:
            client = FakeClient("festival", RuntimeError("SECRET_PROVIDER_ERROR"))
            result = self.execute(client, directory)
            self.assertEqual("needs_attention", result["status"])
            self.assertEqual(1, client.calls.count("festival"))
            self.assertNotIn("poi", client.calls)
            self.assertNotIn("SECRET_PROVIDER_ERROR", json.dumps(result))

    def test_slow_success_is_observation_and_does_not_abort_remaining_cases(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.execute(FakeClient(slow=True), directory)
            self.assertEqual("completed", result["status"])
            self.assertEqual("latency_target_exceeded", result["observations"][0]["kind"])
            self.assertEqual(14, len(result["completedCases"]))

    def test_existing_reserved_target_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            client = FakeClient(existing=True)
            result = self.execute(client, directory)
            self.assertEqual("reserved_target_not_empty", result["error"])
            self.assertNotIn("favorite_delete", client.calls)
            self.assertNotIn("favorite_add", client.calls)

    def test_invalid_or_expired_gate_sends_nothing(self):
        for mutation in ({"runId": "other"}, {"metricsStarted": False}, {"actualSendingVerified": False}, {"expiresAt": "2000-01-01T00:00:00+00:00"}):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                client = FakeClient()
                gate = self.gate() | mutation
                with self.assertRaises(api.LoadError):
                    self.execute(client, directory, gate)
                self.assertEqual([], client.calls)

    def test_write_failure_cleans_reserved_targets_and_logs_out(self):
        with tempfile.TemporaryDirectory() as directory:
            client = FakeClient("favorite_add", RuntimeError("SECRET_WRITE_FAILURE"))
            result = self.execute(client, directory)
            self.assertEqual("needs_attention", result["status"])
            self.assertTrue(result["cleanupAttempted"])
            self.assertTrue(result["cleanupSucceeded"])
            self.assertTrue(result["logoutSucceeded"])
            self.assertEqual(4, client.calls.count("favorite_delete"))
            self.assertEqual(2, client.calls.count("auth_logout"))
            self.assertNotIn("SECRET_WRITE_FAILURE", json.dumps(result))

    def test_transient_windows_file_lock_retries_save_only(self):
        original = Path.replace
        calls = 0
        def locked_once(path, target):
            nonlocal calls
            calls += 1
            if calls == 2:
                error = PermissionError("mock sharing lock")
                error.winerror = 32
                raise error
            return original(path, target)
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(Path, "replace", locked_once):
            client = FakeClient()
            result = self.execute(client, directory)
            self.assertEqual("completed", result["status"])
            self.assertEqual(1, client.calls.count("itinerary_generate"))
            self.assertEqual(2, client.calls.count("auth_login"))


if __name__ == "__main__":
    unittest.main()
