"""로그인 전 취소의 미실행 통계와 gate 사유를 네트워크 없이 검증한다."""
import tempfile
import time
import unittest
from pathlib import Path

import run_api_capacity as capacity
import start_capacity_interactive as launcher


class InteractiveGateTest(unittest.TestCase):
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
