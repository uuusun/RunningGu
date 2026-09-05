"""의도적 오류·빈 페이지와 정상 부하를 섞거나 개인정보를 출력하지 않는지 검사한다."""

import copy
import json
import unittest
from urllib.parse import parse_qs, urlsplit

import probe_readonly_boundaries as probe
from prepare_api_load import ProbeError


def transport(path):
    for _, expected_path, status, code in probe.ERROR_CASES:
        if path == expected_path:
            return status, {"status": status, "code": code}
    url = urlsplit(path)
    query = parse_qs(url.query)
    if url.path == "/api/contests":
        start = 3 if "cursor" in query else 1
        return 200, {"items": [{"id": n, "contestDate": "2026-09-10", "active": True,
                                "favorite": False} for n in range(start, start + 2)],
                     "hasNext": start == 1, "nextCursor": "opaque+cursor/=" if start == 1 else None}
    if url.path == "/api/courses/regions":
        return 200, {"items": [{"region": "부산", "count": 2}]}
    if url.path == "/api/courses":
        empty = query["page"] == ["1"]
        return 200, {"content": [] if empty else [
            {"courseId": "C1", "sido": "부산", "distanceKm": 1.0, "dataSource": "API_GPX"},
            {"courseId": "C2", "sido": "부산", "distanceKm": 1.0, "dataSource": "GPX_ONLY"}],
            "page": {"number": int(query["page"][0]), "size": 50, "totalElements": 2, "hasNext": False},
            "attributions": [] if empty else ["두루누비 걷기길(한국관광공사)"]}
    raise AssertionError("허용되지 않은 경로")


class BoundaryProbeTests(unittest.TestCase):
    def test_fourteen_cases_are_deterministic_without_load_or_marketing(self):
        first = probe.probe_boundaries(transport)
        self.assertEqual(first, probe.probe_boundaries(transport))
        self.assertTrue(first["passed"])
        self.assertEqual(len(first["checks"]), 14)
        self.assertFalse(first["loadExecuted"])
        self.assertFalse(first["authenticatedMeVerified"])
        self.assertFalse(first["marketingRetested"])

    def test_only_allowed_get_paths_and_opaque_cursor(self):
        calls = []
        probe.probe_boundaries(lambda path: (calls.append(path), transport(path))[1])
        self.assertEqual(len(calls), 14)
        self.assertTrue(all(urlsplit(path).path in {
            "/api/me", "/api/me/favorites", "/api/me/courses", "/api/itineraries",
            "/api/contests", "/api/contests/closing-soon", "/api/courses", "/api/courses/regions"
        } for path in calls))
        next_query = parse_qs(urlsplit(calls[10]).query)
        self.assertEqual(next_query["cursor"], ["opaque+cursor/="])

    def changed(self, target, change):
        def changed_transport(path):
            status, body = copy.deepcopy(transport(path))
            return change(status, body) if target(path) else (status, body)
        return probe.probe_boundaries(changed_transport)

    def test_unauthorized_200_is_failure_and_body_is_not_reported(self):
        result = self.changed(lambda p: p == "/api/me",
                              lambda s, b: (200, {"email": "private-sentinel"}))
        self.assertFalse(result["passed"])
        self.assertNotIn("private-sentinel", json.dumps(result))

    def test_expected_status_with_wrong_error_code_fails(self):
        result = self.changed(lambda p: p == "/api/me",
                              lambda s, b: (s, {"status": 401, "code": "WRONG"}))
        self.assertFalse(result["passed"])

    def test_invalid_paging_accepted_is_failure(self):
        result = self.changed(lambda p: p == "/api/courses?size=0", lambda s, b: (200, b))
        self.assertFalse(result["passed"])

    def test_overlapping_cursor_page_fails(self):
        def overlap(status, body):
            body["items"][0]["id"] = 1
            return status, body
        result = self.changed(lambda p: "cursor=" in p, overlap)
        self.assertFalse(result["passed"])

    def test_region_order_violation_fails(self):
        def unordered(status, body):
            body["content"].reverse()
            return status, body
        result = self.changed(lambda p: "region=" in p and "page=0" in p, unordered)
        self.assertFalse(result["passed"])

    def test_empty_page_must_not_have_attribution(self):
        def attribution(status, body):
            body["attributions"] = ["unexpected"]
            return status, body
        result = self.changed(lambda p: "page=1" in p, attribution)
        self.assertFalse(result["passed"])

    def test_failed_dependencies_are_not_silently_removed(self):
        result = self.changed(lambda p: p == "/api/courses/regions", lambda s, b: (500, {}))
        self.assertEqual(len(result["checks"]), 14)
        self.assertEqual(sum(r.get("error") == "dependency_failed" for r in result["checks"]), 2)
        self.assertFalse(result["passed"])

    def test_transport_and_malformed_response_fail_without_raw_output(self):
        def unavailable(path):
            raise ProbeError("transport")
        self.assertFalse(probe.probe_boundaries(unavailable)["passed"])
        result = self.changed(lambda p: p == "/api/contests?size=2", lambda s, b: (s, ["private-sentinel"]))
        self.assertFalse(result["passed"])
        self.assertNotIn("private-sentinel", json.dumps(result))


if __name__ == "__main__":
    unittest.main()
