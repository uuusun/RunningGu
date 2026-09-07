"""가드 제어기의 설정 보존·실패 시 해제를 공급자 호출 없이 검증한다."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest import mock

if sys.platform=="win32":
    sys.modules.setdefault("fcntl",types.SimpleNamespace(LOCK_EX=2,flock=lambda *args:None))
spec=importlib.util.spec_from_file_location("capacity_control",Path(__file__).with_name("staging_capacity_control.py"))
control=importlib.util.module_from_spec(spec)
spec.loader.exec_module(control)


class ControlTest(unittest.TestCase):
    def test_guard_off_alone_does_not_skip_sync_restoration(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'guard-disabled.json').write_text('{}')
            (root/'course-sync-original.json').write_text('{"value":"true"}')
            with mock.patch.multiple(control,OUT=root,STATE=root,NO_KTO=True,RUN='no-kto-test'), \
                 mock.patch.object(control,'process_matches',side_effect=lambda values:values==control.KEYS), \
                 mock.patch.object(control,'finish_locked') as finish:
                control.finish()
            finish.assert_called_once()

    def test_startup_guard_trip_refused_before_sync_completion(self):
        result=dict(unsafeEvents=0,non2xxResults=0,malformedLines=0,counterGaps=0,overLimit=0,
                    invocationUnchanged=True,nRestartsUnchanged=True,passed=False,requiredEndpointSeen=False)
        control.require_guard_safe(result)
        for field in ('unsafeEvents','non2xxResults','malformedLines','counterGaps','overLimit'):
            with self.subTest(field=field), self.assertRaises(AssertionError):
                control.require_guard_safe({**result,field:1})

    def test_guard_roundtrip_preserves_other_settings(self):
        with tempfile.TemporaryDirectory() as directory:
            env=Path(directory)/"application.env"
            original="KEEP_PRIVATE=EXAMPLE\n"+ "\n".join(k+"="+v for k,v in control.KEYS.items())+"\n"
            env.write_text(original)
            with mock.patch.object(control,"ENV",env),mock.patch.object(control,"RUN","test-v3"):
                for _ in range(2):
                    control.set_guard(True)
                    self.assertIn("KEEP_PRIVATE=EXAMPLE",env.read_text())
                    control.set_guard(False)
                    self.assertEqual(original,env.read_text())

    def test_duplicate_keys_refuse_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            env=Path(directory)/"application.env"
            text="UPSTREAM_LOAD_GUARD_ENABLED=false\nUPSTREAM_LOAD_GUARD_ENABLED=false\n"
            env.write_text(text)
            with mock.patch.object(control,"ENV",env),self.assertRaises(AssertionError):
                control.set_guard(True)
            self.assertEqual(text,env.read_text())

    def test_activation_prepares_expiry_before_enabling_and_cleans_on_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); calls=[]
            def run(*args,**kw):
                calls.append(args)
                if args[0]=="git": return "673a2f796052f4553113d5cd25608fb3821222ac"
                return ""
            def set_guard(value):
                self.assertTrue(any(c[0]=="systemd-run" and "--on-active=45m" in c for c in calls))
                raise RuntimeError("mock_activation_failure")
            with mock.patch.multiple(control,OUT=root/"run",STATE=root/"state",RUN="test-v3"), \
                 mock.patch.object(control,"run",side_effect=run), \
                 mock.patch.object(control,"process_matches",return_value=True), \
                 mock.patch.object(control,"set_guard",side_effect=set_guard), \
                 mock.patch.object(control,"finish") as finish, self.assertRaises(RuntimeError):
                control.activate()
            finish.assert_called_once()

    def test_explicit_v3_collector_path_has_no_old_resource_stop(self):
        source=Path(control.__file__).read_text(encoding="utf-8")
        self.assertNotIn("capacity-v2-20260905",source)
        self.assertNotIn("mem_available_below_20_percent",source)
        self.assertNotIn("FullGcCompleted",source)
        self.assertIn("duration-seconds','2400",source)
        self.assertIn("finally:\n        stopping.set()",source)


if __name__=="__main__": unittest.main()
