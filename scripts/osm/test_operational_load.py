#!/usr/bin/env python3

from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))

import operational_load as load


class OperationalLoadTest(unittest.TestCase):
    def test_caps_all_cells_are_fixed(self) -> None:
        cells = load.cells()

        self.assertEqual(len(cells), 90)
        self.assertEqual(cells[0], load.Cell("서울 여의도", 37.5246, 126.9203, 5))
        self.assertEqual(cells[-1], load.Cell("강릉 교동", 37.7700, 128.8900, 21))

    def test_percentile_uses_nearest_rank(self) -> None:
        values = [float(value) for value in range(1, 21)]

        self.assertEqual(load.percentile(values, 0.50), 10.0)
        self.assertEqual(load.percentile(values, 0.95), 19.0)
        self.assertIsNone(load.percentile([], 0.95))

    def test_seed_batch_stops_after_first_failure(self) -> None:
        calls: list[int] = []

        def request(_base_url, _cell, seed, _timeout):
            calls.append(seed)
            return load.RequestResult(seed, 0.1, 200 if seed == 0 else 500, seed == 0, None)

        result = load.run_batch(
            sequence=0,
            scheduled_offset_seconds=0.0,
            scheduled_at=0.0,
            cell=load.cells()[0],
            seeds=16,
            base_url="http://127.0.0.1:8989",
            timeout_seconds=5.0,
            request=request,
        )

        self.assertEqual(calls, [0, 1])
        self.assertFalse(result.success)

    def test_summary_fails_on_missed_start_or_slow_request(self) -> None:
        request = load.RequestResult(0, 5.1, 200, True, None)
        batch = load.BatchResult(0, 0.0, 0.0, "서울 여의도", 5, 5.1, True, (request,))

        summary = load.summarize([batch], scheduled=2, missed=1, timeout_seconds=5.0)

        self.assertEqual(summary["requestsOverTimeout"], 1)
        self.assertEqual(summary["missedBatchStarts"], 1)
        self.assertFalse(summary["passed"])

    def test_summary_passes_only_complete_successful_run(self) -> None:
        requests = tuple(
            load.RequestResult(seed, 0.01 + seed / 1000, 200, True, None)
            for seed in range(16)
        )
        batch = load.BatchResult(0, 0.0, 0.0, "서울 여의도", 5, 0.3, True, requests)

        summary = load.summarize([batch], scheduled=1, missed=0, timeout_seconds=5.0)

        self.assertEqual(summary["directRequests"], 16)
        self.assertTrue(summary["passed"])

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
                output = Path(temporary) / "load.jsonl"
                with patch.object(
                    sys,
                    "argv",
                    [
                        "operational_load.py",
                        "--base-url", f"http://127.0.0.1:{server.server_port}",
                        "--duration-seconds", "1",
                        "--batches-per-minute", "60",
                        "--concurrency", "1",
                        "--seeds", "1",
                        "--timeout-seconds", "5",
                        "--output", str(output),
                    ],
                ), contextlib.redirect_stdout(io.StringIO()):
                    exit_code = load.main()

                summary = json.loads(output.read_text(encoding="utf-8").splitlines()[-1])
                self.assertEqual(exit_code, 0)
                self.assertTrue(summary["passed"])
                self.assertEqual(summary["scheduledBatches"], 1)
                self.assertEqual(summary["directRequests"], 1)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
