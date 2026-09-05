"""사전 점검이 빈 결과·잘못된 인증·오류 응답을 성공으로 숨기지 않는지 검증한다."""

import copy
import io
import json
import unittest
import urllib.error
from email.message import Message
from unittest.mock import patch

import prepare_api_load as prep


def replies():
    return {
        "/api/contests?size=20": (200, {"items": [{"id": 1, "active": True, "favorite": False}]}),
        "/api/contests/1": (200, {"id": 1, "active": True, "events": ["K10"],
                                "contestDate": "2026-09-05", "lat": 37.5, "lng": 127.0}),
        "/api/contests/closing-soon?limit=4":
            (200, {"items": [{"id": 1, "regStatus": "OPEN", "favorite": False}]}),
        "/api/contests/daily-counts?year=2026&month=9":
            (200, {"counts": [{"date": "2026-09-05", "count": 1}]}),
        "/api/courses/regions": (200, {"items": [{"region": "부산", "count": 1}]}),
        "/api/courses?page=0&size=20": (200, {"content": [{"dataSource": "API_GPX"}],
            "page": {"totalElements": 1}, "attributions": ["두루누비 걷기길(한국관광공사)"]}),
        "/api/me": (401, {"status": 401, "code": "UNAUTHORIZED"}),
    }


class ProbeTests(unittest.TestCase):
    def run_probe(self, data=None):
        data = replies() if data is None else data
        return prep.probe_public("2026-09", lambda path: copy.deepcopy(data[path]))

    def test_normal_seven_read_checks_are_deterministic(self):
        first = self.run_probe()
        self.assertEqual(first, self.run_probe())
        self.assertEqual(len(first["checks"]), 7)
        self.assertTrue(first["passed"])
        self.assertFalse(first["loadExecuted"])
        self.assertFalse(first["fullRequestSetFrozen"])

    def test_uses_only_seven_allowed_get_paths(self):
        calls = []
        data = replies()
        prep.probe_public("2026-09", lambda path: (calls.append(path), data[path])[1])
        self.assertEqual(calls, list(data))
        self.assertFalse(any("/auth/" in path or "/near" in path for path in calls))

    def test_no_response_body_or_location_is_copied_to_report(self):
        data = replies()
        for _, body in data.values():
            body["accessToken"] = "private-sentinel"
        result = json.dumps(self.run_probe(data))
        self.assertNotIn("private-sentinel", result)
        self.assertNotIn('"lat"', result)
        self.assertNotIn('"lng"', result)

    def test_empty_contests_fail_and_detail_is_not_requested(self):
        data = replies()
        data["/api/contests?size=20"] = (200, {"items": []})
        result = self.run_probe(data)
        self.assertFalse(result["passed"])
        self.assertEqual(result["checks"][0]["error"], "empty_fixture")
        self.assertEqual(result["checks"][1]["error"], "dependency_failed")

    def test_empty_closing_results_are_not_load_fixture_success(self):
        data = replies()
        data["/api/contests/closing-soon?limit=4"] = (200, {"items": []})
        self.assertFalse(self.run_probe(data)["passed"])

    def test_catalog_count_disagreement_fails(self):
        data = replies()
        data["/api/courses/regions"][1]["items"][0]["count"] = 2
        self.assertFalse(self.run_probe(data)["passed"])

    def test_anonymous_200_is_failure_not_readiness_success(self):
        data = replies()
        data["/api/me"] = (200, {"status": 200, "email": "private-sentinel"})
        result = self.run_probe(data)
        self.assertFalse(result["passed"])
        self.assertEqual(result["checks"][-1]["error"], "unexpected_http")
        self.assertNotIn("private-sentinel", json.dumps(result))

    def test_bad_month_boolean_ids_and_nan_location_fail(self):
        with self.assertRaises(ValueError):
            prep.validate_month("2026-13")
        for value in (True, -1, "1"):
            data = replies()
            data["/api/contests?size=20"][1]["items"][0]["id"] = value
            self.assertFalse(self.run_probe(data)["passed"])
        data = replies()
        data["/api/contests/1"][1]["lat"] = float("nan")
        self.assertFalse(self.run_probe(data)["passed"])

    def test_malformed_payload_does_not_print_exception_details(self):
        data = replies()
        data["/api/contests/1"] = (200, ["private-sentinel"])
        result = self.run_probe(data)
        self.assertFalse(result["passed"])
        self.assertNotIn("private-sentinel", json.dumps(result))


class Response(io.BytesIO):
    code = 200

    def __init__(self, body=b"{}", content_type="application/json"):
        super().__init__(body)
        self.headers = Message()
        self.headers["Content-Type"] = content_type


class TransportTests(unittest.TestCase):
    @patch.object(prep.urllib.request, "build_opener")
    def test_get_only_staging_tls_and_bounded_timeout(self, opener):
        opener.return_value.open.return_value = Response()
        self.assertEqual(prep.fetch_json("/api/contests"), (200, {}))
        request = opener.return_value.open.call_args.args[0]
        self.assertEqual(request.full_url, prep.STAGING_ORIGIN + "/api/contests")
        self.assertEqual(request.get_method(), "GET")
        self.assertIsNone(request.get_header("Authorization"))
        self.assertEqual(opener.return_value.open.call_args.kwargs["timeout"], 10)

    @patch.object(prep.urllib.request, "build_opener")
    def test_http_error_body_is_parsed_without_exception_output(self, opener):
        headers = Message()
        headers["Content-Type"] = "application/problem+json"
        opener.return_value.open.side_effect = urllib.error.HTTPError(
            "https://private-sentinel", 401, "private-sentinel", headers,
            io.BytesIO(b'{"status":401}'))
        self.assertEqual(prep.fetch_json("/api/me"), (401, {"status": 401}))

    @patch.object(prep.urllib.request, "build_opener")
    def test_body_cap_html_and_invalid_json_are_rejected(self, opener):
        for response, expected in (
            (Response(b"x" * (prep.MAX_BODY_BYTES + 1)), "body_too_large"),
            (Response(b"private-sentinel", "text/html"), "content_type"),
            (Response(b"private-sentinel"), "invalid_json"),
        ):
            opener.return_value.open.return_value = response
            with self.assertRaisesRegex(prep.ProbeError, "^" + expected + "$"):
                prep.fetch_json("/api/me")

    @patch.object(prep.urllib.request, "build_opener")
    def test_network_exception_message_is_not_exposed(self, opener):
        opener.return_value.open.side_effect = urllib.error.URLError("private-sentinel")
        with self.assertRaisesRegex(prep.ProbeError, "^transport$"):
            prep.fetch_json("/api/me")

    def test_redirects_are_not_followed(self):
        self.assertIsNone(prep.NoRedirect().redirect_request(None, None, 302, None, None,
                                                            "https://elsewhere.invalid"))


if __name__ == "__main__":
    unittest.main()
