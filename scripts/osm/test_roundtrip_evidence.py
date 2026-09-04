#!/usr/bin/env python3

from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))

import roundtrip


class Response:
    def __init__(self, status_code: int, payload: dict):
        self.status_code = status_code
        self._payload = payload

    def json(self) -> dict:
        return self._payload


class RoundTripEvidenceTest(unittest.TestCase):
    def test_no_valid_point_is_recorded_as_failed_direct_request(self) -> None:
        response = Response(400, {
            "message": "Could not find a valid point after 3 tries, for the point:test"
        })

        with patch.object(roundtrip.requests, "get", return_value=response):
            observation = roundtrip.route_observation(35.1631, 129.1636, 7.8, 2, "run")

        self.assertEqual(observation["status"], 400)
        self.assertEqual(observation["errorType"], "NoValidPoint")
        self.assertIsNone(observation["candidate"])

    def test_compare_fails_when_local_success_becomes_no_valid_point(self) -> None:
        baseline = {
            "schemaVersion": 1,
            "artifactId": "gh11-korea-test",
            "serverImageDigest": "sha256:" + "1" * 64,
            "profile": "run",
            "seedCount": 16,
            "cells": [{
                "name": "부산 해운대",
                "targetKm": 10,
                "requests": [{
                    "seed": 2,
                    "status": 200,
                    "errorType": None,
                    "eligible": True,
                }],
            }],
        }
        current = json.loads(json.dumps(baseline))
        current["cells"][0]["requests"][0].update({
            "status": 400,
            "errorType": "NoValidPoint",
            "eligible": False,
        })

        errors = roundtrip.compare_evidence(baseline, current)

        self.assertTrue(any("로컬 성공 직접 요청" in error for error in errors))
        self.assertTrue(any("품질 상한 통과 경로가 0건" in error for error in errors))

    def test_caps_evidence_contains_status_and_one_fixed_eligible_request_per_cell(self) -> None:
        def observation(_lat, _lng, requested_km, seed, _profile):
            target_km = requested_km / roundtrip.DISTANCE_CORRECTION
            return {
                "seed": seed,
                "status": 200,
                "elapsedSeconds": 0.1,
                "errorType": None,
                "candidate": {
                    "km": target_km,
                    "good": 100.0,
                    "road": 0.0,
                    "stair": 0.0,
                    "alley": 0.0,
                    "qual": 100.0,
                    "turns": 1,
                    "steps": 1,
                    "gain": 0.0,
                    "seed": seed,
                },
            }

        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "baseline.json"
            with patch.object(roundtrip, "route_observation", side_effect=observation), \
                    contextlib.redirect_stdout(io.StringIO()):
                roundtrip.caps_stats(
                    seeds=1,
                    zone="local",
                    evidence_path=output,
                    artifact_id="gh11-korea-test",
                    server_image_digest="sha256:" + "1" * 64,
                )
            evidence = json.loads(output.read_text(encoding="utf-8"))

        self.assertEqual(evidence["summary"]["cellCount"], 30)
        self.assertEqual(evidence["summary"]["eligibleCellCount"], 30)
        self.assertEqual(evidence["cells"][0]["requests"][0]["status"], 200)
        self.assertTrue(evidence["cells"][0]["requests"][0]["eligible"])
        self.assertEqual(evidence["normalRequests"][0]["distanceM"], 3900)
        self.assertEqual(evidence["normalRequests"][0]["seed"], 0)


if __name__ == "__main__":
    unittest.main()
