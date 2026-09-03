#!/usr/bin/env python3

from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import threading
import unittest
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))

import operational_load as load


ARTIFACT_ID = "gh11-korea-test"
IMAGE_DIGEST = "sha256:" + "1" * 64


def request_set_payload() -> dict:
    cells = []
    for name, latitude, longitude, zone in (
        [(name, latitude, longitude, "수도권") for name, latitude, longitude in load.METRO]
        + [(name, latitude, longitude, "지방") for name, latitude, longitude in load.LOCAL]
    ):
        for target_km in load.FILTER_KMS:
            requests = [
                {
                    "seed": seed,
                    "status": 400,
                    "errorType": "NoValidPoint",
                    "eligible": False,
                }
                for seed in range(16)
            ]
            if name == "서울 여의도" and target_km == 5:
                requests[3] = {
                    "seed": 3,
                    "status": 200,
                    "errorType": None,
                    "eligible": True,
                }
            cells.append({
                "name": name,
                "zone": zone,
                "latitude": latitude,
                "longitude": longitude,
                "targetKm": target_km,
                "requests": requests,
            })
    return {
        "schemaVersion": 1,
        "artifactId": ARTIFACT_ID,
        "serverImageDigest": IMAGE_DIGEST,
        "profile": "run",
        "requestOptions": load.REQUEST_OPTIONS,
        "seedCount": 16,
        "cells": cells,
        "normalRequests": [
            {
                "name": "서울 여의도",
                "zone": "수도권",
                "latitude": 37.5246,
                "longitude": 126.9203,
                "targetKm": 5,
                "distanceM": 3900,
                "seed": 3,
            }
        ],
    }


class OperationalLoadTest(unittest.TestCase):
    def test_request_set_binds_artifact_image_and_fixed_case(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "baseline.json"
            path.write_text(json.dumps(request_set_payload()), encoding="utf-8")

            cases = load.load_request_set(path, ARTIFACT_ID, IMAGE_DIGEST)

        self.assertEqual(cases, (
            load.RequestCase("서울 여의도", 37.5246, 126.9203, 5, 3900, 3),
        ))

    def test_request_set_rejects_different_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "baseline.json"
            path.write_text(json.dumps(request_set_payload()), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "artifactId"):
                load.load_request_set(path, "different-artifact", IMAGE_DIGEST)

    def test_request_set_rejects_removed_eligible_cell(self) -> None:
        payload = request_set_payload()
        second_eligible_cell = payload["cells"][1]
        second_eligible_cell["requests"][7] = {
            "seed": 7,
            "status": 200,
            "errorType": None,
            "eligible": True,
        }
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "baseline.json"
            path.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "모든 합격 셀"):
                load.load_request_set(path, ARTIFACT_ID, IMAGE_DIGEST)

    def test_request_set_rejects_removed_caps_cell(self) -> None:
        payload = request_set_payload()
        payload["cells"].pop()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "baseline.json"
            path.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "모든 지점·거리 셀"):
                load.load_request_set(path, ARTIFACT_ID, IMAGE_DIGEST)

    def test_request_set_rejects_seed_that_did_not_pass_locally(self) -> None:
        payload = request_set_payload()
        payload["normalRequests"][0]["seed"] = 4
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "baseline.json"
            path.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "합격 요청"):
                load.load_request_set(path, ARTIFACT_ID, IMAGE_DIGEST)

    def test_percentile_uses_nearest_rank(self) -> None:
        values = [float(value) for value in range(1, 21)]

        self.assertEqual(load.percentile(values, 0.50), 10.0)
        self.assertEqual(load.percentile(values, 0.95), 19.0)
        self.assertIsNone(load.percentile([], 0.95))

    def test_run_request_keeps_fixed_seed_and_failure(self) -> None:
        case = load.RequestCase("서울 여의도", 37.5246, 126.9203, 5, 3900, 3)

        def request(_base_url, actual, _timeout):
            self.assertEqual(actual, case)
            return load.RequestResult(0.1, 400, False, "NoValidPoint")

        result = load.run_request(
            sequence=0,
            scheduled_offset_seconds=0.0,
            scheduled_at=0.0,
            case=case,
            base_url="http://127.0.0.1:8989",
            timeout_seconds=5.0,
            request=request,
        )

        self.assertFalse(result.success)
        self.assertEqual(result.seed, 3)
        self.assertEqual(result.error_type, "NoValidPoint")

    def test_summary_counts_no_valid_point_as_failed_request(self) -> None:
        result = load.ScheduledRequestResult(
            0, 0.0, 0.0, "서울 여의도", 5, 3, 0.1, 400, False, "NoValidPoint"
        )

        summary = load.summarize([result], scheduled=1, missed=0, timeout_seconds=5.0)

        self.assertEqual(summary["failedDirectRequests"], 1)
        self.assertEqual(summary["noValidPointResponses"], 1)
        self.assertFalse(summary["passed"])

    def test_summary_fails_on_missed_start_or_slow_request(self) -> None:
        result = load.ScheduledRequestResult(
            0, 0.0, 0.0, "서울 여의도", 5, 3, 5.1, 200, True, None
        )

        summary = load.summarize([result], scheduled=2, missed=1, timeout_seconds=5.0)

        self.assertEqual(summary["requestsOverTimeout"], 1)
        self.assertEqual(summary["missedRequestStarts"], 1)
        self.assertFalse(summary["passed"])

    def test_http_error_classifies_only_exact_no_valid_point_message(self) -> None:
        body = json.dumps({
            "message": "Could not find a valid point after 3 tries, for the point:test"
        }).encode()
        error = urllib.error.HTTPError("http://example", 400, "bad", {}, io.BytesIO(body))

        self.assertEqual(load._http_error_type(error), "NoValidPoint")

    def test_main_writes_passing_summary_against_local_server(self) -> None:
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                if self.path == "/health":
                    payload = b"{}"
                elif self.path.startswith("/route?"):
                    payload = b'{"paths":[{"distance":1000}]}'
                else:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, _format: str, *_args) -> None:
                return

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as temporary:
                request_set = Path(temporary) / "baseline.json"
                request_set.write_text(json.dumps(request_set_payload()), encoding="utf-8")
                output = Path(temporary) / "load.jsonl"
                with patch.object(
                    sys,
                    "argv",
                    [
                        "operational_load.py",
                        "--base-url", f"http://127.0.0.1:{server.server_port}",
                        "--request-set", str(request_set),
                        "--artifact-id", ARTIFACT_ID,
                        "--server-image-digest", IMAGE_DIGEST,
                        "--duration-seconds", "1",
                        "--requests-per-minute", "60",
                        "--concurrency", "1",
                        "--timeout-seconds", "5",
                        "--output", str(output),
                    ],
                ), contextlib.redirect_stdout(io.StringIO()):
                    exit_code = load.main()

                summary = json.loads(output.read_text(encoding="utf-8").splitlines()[-1])
                self.assertEqual(exit_code, 0)
                self.assertTrue(summary["passed"])
                self.assertEqual(summary["scheduledRequests"], 1)
                self.assertEqual(summary["failedDirectRequests"], 0)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
