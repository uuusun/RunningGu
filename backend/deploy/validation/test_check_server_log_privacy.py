#!/usr/bin/env python3

import gzip
import importlib.util
import pathlib
import tempfile
import unittest


MODULE_PATH = pathlib.Path(__file__).with_name("check-server-log-privacy.py")
SPEC = importlib.util.spec_from_file_location("server_log_privacy", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ServerLogPrivacyCheckTest(unittest.TestCase):
    def test_sensitive_patterns_are_detected_without_matching_safe_diagnostics(self):
        self.assertIsNotNone(MODULE.EMAIL_PATTERN.search("email=user@example.test"))
        self.assertIsNotNone(
            MODULE.COORDINATE_PATTERN.search("GET /api/pois?lat=37.5665&lng=126.9780")
        )
        self.assertIsNotNone(MODULE.SECRET_PATTERN.search("password=private-value"))
        self.assertIsNotNone(MODULE.SECRET_PATTERN.search("Authorization: Bearer secret"))

        safe = (
            "code=INTERNAL_SERVER_ERROR "
            "exceptionType=java.lang.IllegalStateException traceId=abc123"
        )
        self.assertIsNone(MODULE.EMAIL_PATTERN.search(safe))
        self.assertIsNone(MODULE.COORDINATE_PATTERN.search(safe))
        self.assertIsNone(MODULE.SECRET_PATTERN.search(safe))

    def test_current_and_compressed_nginx_logs_are_both_read(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "access.log").write_text("current-line\n", encoding="utf-8")
            with gzip.open(root / "access.log.2.gz", "wt", encoding="utf-8") as target:
                target.write("rotated-line\n")

            file_count, lines = MODULE.read_nginx_logs(root)

        self.assertEqual(file_count, 2)
        self.assertEqual(lines, ["current-line", "rotated-line"])

    def test_log_path_accepts_only_a_filename_below_log_directory(self):
        root = pathlib.Path("/var/log/nginx")

        self.assertEqual(
            MODULE.log_path(root, "runninggu-production.access.log"),
            root / "runninggu-production.access.log",
        )
        with self.assertRaises(ValueError):
            MODULE.log_path(root, "../outside.log")
        with self.assertRaises(ValueError):
            MODULE.log_path(root, "/tmp/outside.log")


if __name__ == "__main__":
    unittest.main()
