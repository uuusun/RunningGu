"""요청 수·시간·의존성은 승인 문서의 상수와 독립 비교한다."""

from collections import Counter
import unittest

import plan_api_arrivals as planner


class ArrivalPlanTest(unittest.TestCase):
    def setUp(self):
        self.schedule = planner.build_schedule()

    def test_document_counts_each_minute(self):
        expected = {
            "contest_list": 12, "contest_detail": 8, "contest_closing": 5, "contest_daily": 5,
            "course_list": 6, "course_regions": 3, "near_curated": 3, "near_osm": 3,
            "festival": 2, "poi": 2, "geocode": 2, "itinerary_generate": 3,
            "me": 2, "favorite_list": 2, "favorite_add": 1, "favorite_delete": 1,
        }
        for minute in range(35):
            self.assertEqual(expected, Counter(row["caseId"] for row in self.schedule[minute * 60:(minute + 1) * 60]))

    def test_phase_counts(self):
        self.assertEqual(Counter(warmup=300, measurement=1800), Counter(row["phase"] for row in self.schedule))

    def test_fixed_one_second_spacing_without_completion_feedback(self):
        self.assertEqual(list(range(0, 2100000, 1000)), [row["plannedOffsetMs"] for row in self.schedule])
        self.assertEqual(list(range(1, 2101)), [row["sequence"] for row in self.schedule])

    def test_phase_boundary_and_indices(self):
        self.assertEqual(("warmup", 4, 299000), tuple(self.schedule[299][key] for key in ("phase", "phaseMinute", "plannedOffsetMs")))
        self.assertEqual(("measurement", 0, 300000), tuple(self.schedule[300][key] for key in ("phase", "phaseMinute", "plannedOffsetMs")))
        self.assertEqual(("measurement", 29, 2099000), tuple(self.schedule[-1][key] for key in ("phase", "phaseMinute", "plannedOffsetMs")))

    def test_each_delete_depends_on_earlier_same_minute_addition(self):
        deletions = [row for row in self.schedule if row["caseId"] == "favorite_delete"]
        self.assertEqual(35, len(deletions))
        for deletion in deletions:
            addition = self.schedule[deletion["dependsOnSequence"] - 1]
            self.assertEqual("favorite_add", addition["caseId"])
            self.assertLess(addition["sequence"], deletion["sequence"])
            self.assertEqual((addition["phase"], addition["phaseMinute"]), (deletion["phase"], deletion["phaseMinute"]))
            self.assertEqual(10_000, addition["plannedOffsetMs"] % 60_000)
            self.assertEqual(50_000, deletion["plannedOffsetMs"] % 60_000)
            self.assertGreaterEqual(deletion["plannedOffsetMs"] - addition["plannedOffsetMs"], 40_000)
        self.assertTrue(all("dependsOnSequence" not in row for row in self.schedule if row["caseId"] != "favorite_delete"))

    def test_deterministic_independent_calls(self):
        self.assertEqual(self.schedule, planner.build_schedule())
        self.assertEqual(planner.schedule_sha256(self.schedule), planner.schedule_sha256(planner.build_schedule()))
        self.assertEqual(
            "88bd5580cc00ca57796b6ba98cbe4e54b727e02924f773561e7b4a638abd097d",
            planner.schedule_sha256(self.schedule),
        )

    def test_hash_changes_for_time_count_or_case_change(self):
        original = planner.schedule_sha256(self.schedule)
        for field, value in (("plannedOffsetMs", 1), ("caseId", "different")):
            mutated = [dict(row) for row in self.schedule]
            mutated[0][field] = value
            self.assertNotEqual(original, planner.schedule_sha256(mutated))
        self.assertNotEqual(original, planner.schedule_sha256(self.schedule[:-1]))

    def test_summary_cannot_claim_readiness_or_load_success(self):
        summary = planner.plan_summary(self.schedule)
        self.assertFalse(summary["loadExecuted"])
        self.assertFalse(summary["fullRequestSetFrozen"])
        self.assertFalse(summary["readyForLoad"])
        self.assertEqual(4, summary["maxInFlight"])
        self.assertEqual(1800, sum(summary["countsByPhase"]["measurement"].values()))
        self.assertEqual(300, sum(summary["countsByPhase"]["warmup"].values()))
        self.assertEqual(4, len(summary["blockingRequirements"]))


if __name__ == "__main__":
    unittest.main()
