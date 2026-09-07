"""공공데이터 제외·일반 오류 계속 실행·보호 차단 뒤 부분 관측을 네트워크 없이 검증한다."""
import datetime as dt
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest import mock

import run_api_capacity as cap
import run_api_load as api
import start_capacity_interactive as launcher
from test_run_api_capacity import Client
from test_run_api_load import fixture_value, payload_for
from test_staging_capacity_control import control


def gate():
    return dict(runId='no-kto-test',acceptancePolicy=cap.NO_KTO_POLICY,
        guardProtectionVerified=True,metricsStarted=True,actualSendingVerified=True,
        expiryRecoveryReady=True,courseSyncDisabled=True,ktoAttempts=0,
        expiresAt=(dt.datetime.now(dt.timezone.utc)+dt.timedelta(minutes=45)).isoformat())


class NoKtoTest(unittest.TestCase):
    def test_selected_full_schedule_validated_and_excluded_never_sent(self):
        class Accounts(Client):
            def exchange(self,spec,access_token=None,before_send=None):
                result=super().exchange(spec,access_token,before_send)
                if spec.case_id=='me':
                    result.payload['id']=1 if spec.account_label=='A' else 2
                return result
        seen=[]
        def scheduled(client,fixture,sessions,schedule,ev):
            for item in schedule:
                seen.append(item)
                label=api.account_for_item(item) if item['caseId'] in {'me','favorite_list','favorite_add','favorite_delete'} else None
                semaphore=threading.BoundedSemaphore(1); semaphore.acquire()
                with ev.request_context(ev.rows[item['sequence']]):
                    result=api.execute_task(item,api.request_spec(item['caseId'],fixture,label),client,fixture,sessions,semaphore)
                ev.rows[item['sequence']].update(success=result.success,error=result.error)
                ev.results.append(result)
            ev.maintenance.extend([dict(operation='refresh',account=a,success=True) for a in ('A','B')])
        with tempfile.TemporaryDirectory() as directory:
            raw=Accounts()
            result=cap.execute(fixture_value(),'no-kto-test',Path(directory)/'load',gate(),
                secret_prompt=lambda _:'SECRET',client_factory=lambda:raw,scheduler=scheduled,exclude_kto=True)
            self.assertEqual(1855,result['counts']['dispatched'])
            self.assertEqual(1855,result['counts']['successful'])
            self.assertEqual([265,1590],[r['planned'] for r in result['phases']])
            self.assertEqual(245,result['excludedRequestCount'])
            self.assertFalse(set(raw.calls)&cap.KTO_CASES)
            original=api.arrivals.build_schedule()
            self.assertEqual([r for r in original if r['caseId'] not in cap.KTO_CASES],seen)
            self.assertTrue(result['subsetChecksPassed'])
            self.assertFalse(result['fullAppLoadPassed'])
            self.assertTrue(result['cleanupSucceeded'] and result['logoutSucceeded'])
            self.assertNotIn('SECRET',json.dumps(result))

    def test_http_500_continues_without_retry(self):
        class BrokenOnce:
            def __init__(self): self.calls=0
            def exchange(self,spec,access_token=None,before_send=None):
                before_send(); self.calls+=1
                return api.Exchange(500 if self.calls==1 else 200,
                    {} if self.calls==1 else payload_for(spec.case_id,fixture_value()),1,10)
        schedule=[dict(sequence=i+1,phase='measurement',plannedOffsetMs=i*30,caseId='contest_list') for i in range(3)]
        with tempfile.TemporaryDirectory() as directory:
            ev=cap.Evidence(Path(directory)/'load','no-kto-test',schedule,True); ev.stage='load'
            raw=BrokenOnce()
            cap.execute_schedule(cap.RecordingClient(raw,ev),fixture_value(),{},schedule,ev,refresh_at=None)
            self.assertEqual(3,raw.calls)
            self.assertEqual(1,sum(not r.success for r in ev.results))
            self.assertIsNone(ev.error)
            ev.events.close()

    def test_guard_block_skips_external_and_continues_internal(self):
        schedule=[dict(sequence=i+1,phase='measurement',plannedOffsetMs=i*30,caseId=c)
                  for i,c in enumerate(('near_osm','contest_list','geocode'))]
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); (root/'upstream-blocked.json').write_text('{}')
            ev=cap.Evidence(root/'load','no-kto-test',schedule,True)
            raw=Client()
            cap.execute_schedule(cap.RecordingClient(raw,ev),fixture_value(),{},schedule,ev,refresh_at=None)
            self.assertEqual(['contest_list'],raw.calls)
            self.assertEqual(2,sum(r['error']=='excluded_after_guard_block' for r in ev.rows.values()))
            self.assertIsNone(ev.error)
            ev.events.close()

    def test_missing_exclusion_gate_prevents_login(self):
        for mutation in ({'courseSyncDisabled':False},{'ktoAttempts':1},{'guardProtectionVerified':False}):
            with self.subTest(mutation=mutation),tempfile.TemporaryDirectory() as directory:
                client=Client()
                with self.assertRaises(api.LoadError):
                    cap.execute(fixture_value(),'no-kto-test',Path(directory)/'load',gate()|mutation,
                        client_factory=lambda:client,exclude_kto=True)
                self.assertEqual([],client.calls)

    def test_sync_flag_restored_for_both_original_states(self):
        for original_value in ('true','false'):
            with self.subTest(original_value=original_value),tempfile.TemporaryDirectory() as directory:
                root=Path(directory); env=root/'application.env'
                original='KEEP_PRIVATE=EXAMPLE\nCOURSE_SYNC_ENABLED='+original_value+'\n'+ '\n'.join(k+'='+v for k,v in control.KEYS.items())+'\n'
                env.write_text(original)
                with mock.patch.multiple(control,ENV=env,OUT=root,NO_KTO=True,RUN='no-kto-test'):
                    control.set_guard(True)
                    self.assertIn('COURSE_SYNC_ENABLED=false',env.read_text())
                    control.set_guard(False)
                    self.assertEqual(set(original.splitlines()),set(env.read_text().splitlines()))
                    control.set_guard(False)
                    self.assertEqual(set(original.splitlines()),set(env.read_text().splitlines()))

    def test_provider_failure_does_not_hide_bad_instrumentation(self):
        row=dict(guardLines=1,malformedLines=0,counterGaps=0,overLimit=0,ktoAttempts=0,
            invocationUnchanged=True,nRestartsUnchanged=True,requiredEndpointSeen=True,
            unsafeEvents=1,non2xxResults=1,passed=False)
        control.require_no_kto_instrumentation(row)
        for field in ('malformedLines','counterGaps','overLimit','ktoAttempts'):
            with self.subTest(field=field),self.assertRaises(AssertionError):
                control.require_no_kto_instrumentation(row|{field:1})

    def test_prestart_summary_uses_selected_scope(self):
        result=launcher.prestart_summary('no-kto-test','cancelled',True)
        self.assertEqual(1855,result['counts']['notExecuted'])
        self.assertEqual(245,result['excludedRequestCount'])
        self.assertEqual([265,1590],[r['planned'] for r in result['phases']])


if __name__=='__main__': unittest.main()
