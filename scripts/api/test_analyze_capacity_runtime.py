import unittest
import analyze_capacity_runtime as analysis

class AnalysisTest(unittest.TestCase):
    def test_gc_start_line_not_double_counted_and_heap_cause_preserved(self):
        prefix="[2026-09-06T01:00:00Z][info][gc] "
        self.assertIsNone(analysis.gc_event(prefix+"GC(92) Pause Full (G1 Compaction Pause)","t","graphhopper"))
        event=analysis.gc_event(prefix+"GC(92) Pause Full (G1 Compaction Pause) 2040M->1059M(2048M) 32.152ms","t","graphhopper")
        self.assertEqual(32.152,event["pauseMs"])
        self.assertEqual([2040,1059,2048],event["heapMiB"])
        self.assertTrue(event["full"])
        self.assertEqual("Pause Full (G1 Compaction Pause)",event["kind"])
    def test_gc_young_and_cpu_activity_are_diagnostics(self):
        event=analysis.gc_event("GC(1) Pause Young (Normal) 50M->25M(512M) 1.500ms","t","backend")
        self.assertFalse(event["full"])
        keys=("cpu_user","cpu_nice","cpu_system","cpu_idle","cpu_iowait","cpu_irq","cpu_softirq","cpu_steal")
        a={"at":"a",**dict.fromkeys(keys,"0")}
        b={"at":"b",**dict(zip(keys,map(str,[30,0,10,50,10,0,0,0])))}
        self.assertEqual(40,analysis.cpu_deltas([{"sample":a},{"sample":b}])[0]["busyPercent"])

if __name__=="__main__": unittest.main()
