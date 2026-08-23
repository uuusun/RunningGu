#!/usr/bin/env python3
"""두루누비 서버 번들 v1 생산 계약 회귀 테스트."""

import copy
import json
import unittest
from types import SimpleNamespace

from build_courses import build_payload, validate_payload
from courses.model import Course
from courses.sources.durunubi import _parse_gpx


def course(course_id: str, data_source: str = "GPX_ONLY") -> Course:
    return Course(
        id=course_id,
        source="durunubi",
        name=f"코스 {course_id}",
        sido="부산",
        sigun="부산 중구",
        dist_km=3.2,
        gain_m=12,
        level=2,
        level_label="보통",
        cycle="비순환형",
        summary="설명",
        data_source=data_source,
        points=[[35.1, 129.0, 10.0, 0.0], [35.2, 129.1, 12.0, 2.0]],
    )


SOURCE = SimpleNamespace(
    key="durunubi",
    attribution="두루누비 걷기길(한국관광공사)",
    license="공공데이터포털 이용약관 — 출처표시",
    derivable=True,
)


class CourseBundleTest(unittest.TestCase):

    def test_v1_field_names_and_stable_order(self):
        payload = build_payload(
            [course("T002", "API_GPX"), course("T001")],
            [SOURCE],
            minimum_course_count=2,
        )

        self.assertEqual(payload["schemaVersion"], 1)
        self.assertEqual([item["courseId"] for item in payload["courses"]], ["T001", "T002"])
        self.assertEqual(payload["courses"][0]["courseName"], "코스 T001")
        self.assertEqual(payload["courses"][0]["distanceKm"], 3.2)
        self.assertEqual(payload["courses"][0]["difficulty"], "NORMAL")
        self.assertNotIn("id", payload["courses"][0])
        self.assertNotIn("levelLabel", payload["courses"][0])

    def test_same_input_produces_same_utf8_bytes(self):
        courses = [course("T002", "API_GPX"), course("T001")]
        first = build_payload(courses, [SOURCE], minimum_course_count=2)
        second = build_payload(list(reversed(courses)), [SOURCE], minimum_course_count=2)

        encode = lambda value: json.dumps(
            value, ensure_ascii=False, separators=(",", ":")
        ).encode("utf-8")
        self.assertEqual(encode(first), encode(second))

    def test_duplicate_course_id_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "courseId가 중복"):
            build_payload([course("T001"), course("T001")], [SOURCE], minimum_course_count=2)

    def test_gpx_only_must_remain(self):
        payload = build_payload([course("T001")], [SOURCE], minimum_course_count=1)
        payload["courses"][0]["dataSource"] = "API_GPX"

        with self.assertRaisesRegex(ValueError, "GPX_ONLY가 0건"):
            validate_payload(payload, minimum_course_count=1)

    def test_invalid_or_decreasing_point_is_rejected(self):
        payload = build_payload([course("T001")], [SOURCE], minimum_course_count=1)
        invalid = copy.deepcopy(payload)
        invalid["courses"][0]["points"][1][3] = -1.0

        with self.assertRaisesRegex(ValueError, "누적 상승고도"):
            validate_payload(invalid, minimum_course_count=1)

    def test_unknown_region_is_rejected(self):
        payload = build_payload([course("T001")], [SOURCE], minimum_course_count=1)
        payload["courses"][0]["sido"] = "부울경"

        with self.assertRaisesRegex(ValueError, "17개 시도"):
            validate_payload(payload, minimum_course_count=1)


class DurunubiGpxRegressionTest(unittest.TestCase):

    def test_multiple_tracks_are_not_concatenated(self):
        xml = """
        <gpx>
          <trk><trkseg>
            <trkpt lat="35.000" lon="129.000"><ele>1</ele></trkpt>
            <trkpt lat="35.001" lon="129.001"><ele>2</ele></trkpt>
          </trkseg></trk>
          <trk><trkseg>
            <trkpt lat="36.000" lon="128.000"><ele>3</ele></trkpt>
            <trkpt lat="36.001" lon="128.001"><ele>4</ele></trkpt>
            <trkpt lat="36.002" lon="128.002"><ele>5</ele></trkpt>
          </trkseg></trk>
        </gpx>
        """

        points = _parse_gpx(xml)

        self.assertEqual(len(points), 3)
        self.assertEqual(points[0], (36.0, 128.0, 3.0))

    def test_jump_inside_track_is_split(self):
        xml = """
        <gpx><trk><trkseg>
          <trkpt lat="35.000" lon="129.000"><ele>1</ele></trkpt>
          <trkpt lat="35.001" lon="129.001"><ele>2</ele></trkpt>
          <trkpt lat="36.000" lon="128.000"><ele>3</ele></trkpt>
          <trkpt lat="36.001" lon="128.001"><ele>4</ele></trkpt>
        </trkseg></trk></gpx>
        """

        points = _parse_gpx(xml, declared_km=0.2)

        self.assertEqual(len(points), 2)
        self.assertLess(abs(points[1][0] - points[0][0]), 0.01)

    def test_declared_distance_selects_track_but_does_not_replace_measurement(self):
        xml = """
        <gpx>
          <trk><trkseg>
            <trkpt lat="35.0" lon="129.0"><ele>1</ele></trkpt>
            <trkpt lat="35.001" lon="129.0"><ele>2</ele></trkpt>
          </trkseg></trk>
          <trk><trkseg>
            <trkpt lat="35.0" lon="129.0"><ele>3</ele></trkpt>
            <trkpt lat="35.001" lon="129.0"><ele>4</ele></trkpt>
            <trkpt lat="35.002" lon="129.0"><ele>5</ele></trkpt>
          </trkseg></trk>
        </gpx>
        """

        points = _parse_gpx(xml, declared_km=0.11)

        self.assertEqual(points[-1][0], 35.001)


if __name__ == "__main__":
    unittest.main()
