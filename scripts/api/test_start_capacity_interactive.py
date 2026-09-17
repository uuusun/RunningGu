"""로그인 전 취소의 미실행 통계와 gate 사유를 네트워크 없이 검증한다."""
import io
import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

import run_api_capacity as capacity
import start_capacity_interactive as launcher


class InteractiveGateTest(unittest.TestCase):
    def test_interactive_launcher_is_rejected_after_staging_retirement(self):
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch("sys.argv", ["start_capacity_interactive.py", "--run-id", "retired", "--output", directory]), \
                mock.patch("sys.stdout", new_callable=io.StringIO) as stdout:
            self.assertEqual(2, launcher.main())
        self.assertEqual("staging_retired", json.loads(stdout.getvalue())["error"])

    def test_cancel_does_not_report_elapsed_timeout(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'cancel').touch()
            with self.assertRaises(capacity.api.LoadError) as caught:
                launcher.wait_file(root/'gate',time.monotonic()+60,root/'cancel')
            self.assertEqual('control_gate_cancelled',caught.exception.code)

    def test_before_login_cancel_preserves_all_unexecuted(self):
        result=launcher.prestart_summary('gate-test','control_gate_cancelled')
        self.assertEqual(2100,result['counts']['notExecuted'])
        self.assertEqual(0,result['counts']['dispatched'])
        self.assertEqual(0,result['auxiliaryCounts']['completed'])
        self.assertEqual([300,1800],[p['notExecuted'] for p in result['phases']])
        self.assertIsNone(result['maxDispatchDelayMs'])
        self.assertEqual(capacity.SCHEDULE_HASH,result['scheduleSha256'])


if __name__=='__main__':
    unittest.main()
