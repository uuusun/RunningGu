"""245건 선택·미인증 실행·오류/중단 통계 보존을 실제 내용 검증 경로로 확인한다."""
import datetime as dt
import json
from pathlib import Path
import tempfile
import threading
import unittest

import run_api_capacity as cap
import run_api_kto_supplement as sub
import run_api_load as api
from test_run_api_load import fixture_value, payload_for

def gate():
    return {"runId": "kto-supplement-test", "acceptancePolicy": cap.POLICY,
            "executionProfile": sub.PROFILE, "guardPreflightPassed": True, "metricsStarted": True,
            "actualSendingVerified": True, "expiryRecoveryReady": True,
            "expiresAt": (dt.datetime.now(dt.timezone.utc)+dt.timedelta(minutes=45)).isoformat()}

class Client:
    def __init__(self, broken_status=None):
        self.calls = []
        self.broken_status = broken_status
    def exchange(self, spec, access_token=None, before_send=None):
        assert access_token is None
        assert spec.case_id in sub.CASES
        if before_send: before_send()
        self.calls.append(spec.case_id)
        if len(self.calls) == 4 and self.broken_status is not None:
            return api.Exchange(self.broken_status, {}, 1, 2)
        return api.Exchange(200, payload_for(spec.case_id, fixture_value()), 1, 10)

def instant(client, fixture, sessions, schedule, ev):
    ev.load_started_at = cap.now()
    for item in schedule:
        if ev.check_stop(): break
        semaphore = threading.BoundedSemaphore(1); semaphore.acquire()
        with ev.request_context(ev.rows[item["sequence"]]):
            result = api.execute_task(item, api.request_spec(item["caseId"], fixture),
                client, fixture, sessions, semaphore)
        ev.rows[item["sequence"]].update(success=result.success, error=result.error)
        ev.results.append(result)
    ev.load_finished_at = cap.now()

class SupplementTest(unittest.TestCase):
    def test_original_slots_245_and_public_validation(self):
        original = api.arrivals.build_schedule()
        expected = [r for r in original if r["caseId"] in sub.CASES]
        self.assertEqual(expected, sub.selected_schedule())
        raw = Client()
        with tempfile.TemporaryDirectory() as d:
            result = sub.execute(fixture_value(), gate()["runId"], Path(d)/"load", gate(),
                client_factory=lambda: raw, scheduler=instant)
            self.assertEqual(245, result["counts"]["successful"])
            self.assertEqual([35, 210], [r["planned"] for r in result["phases"]])
            self.assertEqual(3, result["auxiliaryCounts"]["successful"])
            self.assertEqual(248, len(raw.calls))
            self.assertTrue(result["subsetChecksPassed"])
            self.assertFalse(result["fullAppLoadPassed"])
            self.assertIsNone(result["cleanupSucceeded"])
            self.assertFalse(result["credentialsCollected"])

    def test_gate_failure_never_sends(self):
        raw = Client(); denied = gate() | {"actualSendingVerified": False}
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(api.LoadError):
                sub.execute(fixture_value(), denied["runId"], Path(d)/"load", denied, client_factory=lambda: raw)
            self.assertEqual([], raw.calls)

    def test_content_failure_preserved_and_next_requests_continue(self):
        raw = Client(broken_status=200)
        with tempfile.TemporaryDirectory() as d:
            result = sub.execute(fixture_value(), gate()["runId"], Path(d)/"load", gate(),
                client_factory=lambda: raw, scheduler=instant)
            self.assertEqual(245, result["counts"]["dispatched"])
            self.assertEqual(1, result["counts"]["failed"])
            self.assertEqual(244, result["counts"]["successful"])
            self.assertFalse(result["passed"])
            saved = json.loads((Path(d)/"load/partial-summary.json").read_text())
            self.assertEqual(result["counts"], saved["counts"])
            self.assertTrue((Path(d)/"load-finished.json").exists())

    def test_http_500_protects_and_preserves_unexecuted_without_retry(self):
        raw = Client(broken_status=500)
        with tempfile.TemporaryDirectory() as d:
            result = sub.execute(fixture_value(), gate()["runId"], Path(d)/"load", gate(),
                client_factory=lambda: raw, scheduler=instant)
            self.assertEqual(4, len(raw.calls))
            self.assertEqual(1, result["counts"]["failed"])
            self.assertEqual(244, result["counts"]["notExecuted"])
            self.assertEqual("http_500_guard_check_required", result["error"])
            self.assertFalse(result["passed"])

    def test_preflight_failure_has_zero_load_and_evidence(self):
        class FailedPreflight(Client):
            def exchange(self, spec, access_token=None, before_send=None):
                if before_send: before_send()
                self.calls.append(spec.case_id)
                return api.Exchange(500, {}, 1, 2)
        raw = FailedPreflight()
        with tempfile.TemporaryDirectory() as d:
            result = sub.execute(fixture_value(), gate()["runId"], Path(d)/"load", gate(),
                client_factory=lambda: raw, scheduler=instant)
            self.assertEqual(0, result["counts"]["dispatched"])
            self.assertEqual(245, result["counts"]["notExecuted"])
            self.assertEqual(1, result["auxiliaryCounts"]["failed"])
            self.assertTrue((Path(d)/"load/api-load-summary.json").exists())

if __name__ == "__main__":
    unittest.main()
