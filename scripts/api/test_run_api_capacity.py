"""전체 부하의 부분 결과·중단·오류 계속 관측·정리 경로를 공급자 호출 없이 검증한다."""
import datetime as dt
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest import mock

import run_api_load as api
import run_api_capacity as cap
from test_run_api_load import fixture_value, payload_for
from test_run_api_smoke import FakeClient


class Client(FakeClient):
    def exchange(self, spec, access_token=None, before_send=None):
        if before_send:
            before_send()
        if spec.case_id=="auth_refresh":
            self.calls.append(spec.case_id)
            return api.Exchange(200,{"accessToken":"TOKEN_SECRET","refreshToken":"REFRESH_SECRET"},1,10)
        return super().exchange(spec,access_token)


class CapacityTest(unittest.TestCase):
    def gate(self):
        return {"runId":"capacity-test","acceptancePolicy":cap.POLICY,
            "guardPreflightPassed":True,"metricsStarted":True,"actualSendingVerified":True,
            "expiryRecoveryReady":True,
            "expiresAt":(dt.datetime.now(dt.timezone.utc)+dt.timedelta(minutes=45)).isoformat()}

    def run_fake(self,directory,client=None,scheduler=None,finish=None,schedule=None):
        return cap.execute(fixture_value(),"capacity-test",Path(directory)/"evidence",self.gate(),
            secret_prompt=lambda _:"INPUT_SECRET",client_factory=lambda: client or Client(),
            scheduler=scheduler or (lambda *args:None),finish_hook=finish,
            schedule=schedule)

    def test_full_2100_results_saved_and_auxiliary_separated(self):
        def scheduled(client,fixture,sessions,schedule,ev):
            for item in schedule:
                label=api.account_for_item(item) if item["caseId"] in {"me","favorite_list","favorite_add","favorite_delete"} else None
                row=ev.rows[item["sequence"]]
                spec=api.request_spec(item["caseId"],fixture,label)
                with ev.request_context(row):
                    response=client.exchange(spec)
                # FakeClient의 me는 A ID만 제공하므로 실제 validator를 따로 아래 시험에서 검증한다.
                result=api.TaskResult(item["sequence"],item["phase"],item["caseId"],row["group"],True,response.duration_ms,100,None)
                ev.results.append(result)
            ev.maintenance.extend([{"operation":"refresh","account":a,"success":True} for a in ("A","B")])
        with tempfile.TemporaryDirectory() as directory:
            result=self.run_fake(directory,scheduler=scheduled)
            self.assertEqual({"planned":2100,"dispatched":2100,"completed":2100,"successful":2100,
                "failed":0,"notExecuted":0,"inFlight":0},result["counts"])
            self.assertEqual(1800,result["phases"][1]["successful"])
            self.assertTrue(result["cleanupSucceeded"] and result["logoutSucceeded"])
            self.assertTrue(result["passed"])
            text=(Path(directory)/"evidence/request-events.jsonl").read_text(encoding="utf-8")
            self.assertEqual(2100,sum(r.get("phase") in ("warmup","measurement") and r["event"]=="dispatch"
                for r in map(json.loads,text.splitlines())))
            for secret in ("INPUT_SECRET","TOKEN_SECRET","REFRESH_SECRET","example.test","35.385905"):
                self.assertNotIn(secret,text+json.dumps(result))
            self.assertGreater(result["auxiliaryCounts"]["completed"],0)

    def test_operator_interrupt_preserves_partial_and_runs_finally(self):
        finish=mock.Mock()
        def interrupted(client,fixture,sessions,schedule,ev):
            item=schedule[0]
            row=ev.rows[item["sequence"]]
            with ev.request_context(row):
                response=client.exchange(api.request_spec(item["caseId"],fixture))
            ev.results.append(api.TaskResult(item["sequence"],item["phase"],item["caseId"],
                row["group"],True,response.duration_ms,100,None))
            raise KeyboardInterrupt()
        with tempfile.TemporaryDirectory() as directory:
            result=self.run_fake(directory,scheduler=interrupted,finish=finish)
            self.assertEqual("operator_interrupted",result["error"])
            self.assertEqual(1,result["counts"]["completed"])
            self.assertEqual(2099,result["counts"]["notExecuted"])
            self.assertTrue(result["cleanupSucceeded"] and result["logoutSucceeded"])
            finish.assert_called_once()
            self.assertTrue((Path(directory)/"load-finished.json").exists())
            self.assertEqual(result,json.loads((Path(directory)/"evidence/api-load-summary.json").read_text(encoding="utf-8")))

    def test_preflight_exception_and_reserved_data_are_preserved(self):
        for client,error in [(Client("near_osm",RuntimeError("PRIVATE_MESSAGE")),"internal_runner"),
                             (Client(existing=True),"reserved_target_not_empty")]:
            with self.subTest(error=error),tempfile.TemporaryDirectory() as directory:
                result=self.run_fake(directory,client=client)
                self.assertEqual(error,result["error"])
                self.assertEqual(0,result["counts"]["dispatched"])
                self.assertEqual(2,client.calls.count("auth_logout"))
                if client.existing:
                    self.assertNotIn("favorite_delete",client.calls)
                self.assertNotIn("PRIVATE_MESSAGE",json.dumps(result))

    def test_guard_finish_exception_still_cleans_and_logs_out(self):
        with tempfile.TemporaryDirectory() as directory:
            result=self.run_fake(directory,finish=mock.Mock(side_effect=RuntimeError("PRIVATE")))
            self.assertEqual("guard_finish_failed",result["error"])
            self.assertTrue(result["cleanupSucceeded"] and result["logoutSucceeded"])

    def test_schedule_continues_after_http_error_without_retry(self):
        class BrokenOnce:
            def __init__(self): self.calls=0
            def exchange(self,spec,access_token=None,before_send=None):
                before_send()
                self.calls+=1
                return api.Exchange(503 if self.calls==1 else 200,
                    {} if self.calls==1 else payload_for(spec.case_id,fixture_value()),1,10)
        schedule=[{"sequence":i+1,"phase":"measurement","plannedOffsetMs":i*30,
                   "caseId":"contest_list"} for i in range(3)]
        with tempfile.TemporaryDirectory() as directory:
            ev=cap.Evidence(Path(directory)/"evidence","capacity-test",schedule)
            raw=BrokenOnce()
            # 실제 스케줄/스레드 경로를 짧은 모의 시간표로 실행한다.
            cap.execute_schedule(cap.RecordingClient(raw,ev),fixture_value(),{},schedule,ev,refresh_at=None)
            self.assertEqual(3,raw.calls)
            self.assertEqual(1,sum(not r.success for r in ev.results))
            self.assertEqual("unexpected_http",ev.results[0].error)
            self.assertEqual(3,cap.counts(list(ev.rows.values()))["completed"])
            ev.events.close()

    def test_missing_gate_or_old_policy_never_logs_in(self):
        for mutation in ({"acceptancePolicy":"capacity-v2"},{"actualSendingVerified":False},
                         {"expiryRecoveryReady":False},{"expiresAt":"2000-01-01T00:00:00+00:00"}):
            with tempfile.TemporaryDirectory() as directory:
                client=Client()
                with self.assertRaises(api.LoadError):
                    cap.execute(fixture_value(),"capacity-test",Path(directory)/"evidence",self.gate()|mutation,
                        secret_prompt=lambda _:"INPUT_SECRET",client_factory=lambda:client)
                self.assertEqual([],client.calls)

    def test_actual_entrypoint_routes_to_persistent_full_runner(self):
        with mock.patch.object(cap,"main",return_value=73) as main:
            self.assertEqual(73,api.main())
            main.assert_called_once()


if __name__=="__main__":
    unittest.main()
