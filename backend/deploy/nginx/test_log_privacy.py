#!/usr/bin/env python3
"""nginx 설정이 요청 원문을 파일 로그에 넣지 않는지 정적으로 검증한다."""

import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parent
LOG_POLICY = (ROOT / "runninggu-log-privacy.conf").read_text(encoding="utf-8")
SERVER_FILES = [
    ROOT / "staging-api.bootstrap.conf",
    ROOT / "staging-api.conf",
    ROOT / "default-reject.conf",
]


class NginxLogPrivacyTest(unittest.TestCase):
    def test_access_log_format_excludes_request_controlled_text(self):
        forbidden = (
            r"\$request(?![A-Za-z0-9_])",
            r"\$request_uri(?![A-Za-z0-9_])",
            r"\$uri(?![A-Za-z0-9_])",
            r"\$args(?![A-Za-z0-9_])",
            r"\$query_string(?![A-Za-z0-9_])",
            r"\$host(?![A-Za-z0-9_])",
            r"\$http_",
        )
        log_format = re.search(
            r"log_format\s+runninggu_minimal\s+(.*?);", LOG_POLICY, re.DOTALL
        )
        self.assertIsNotNone(log_format)
        for pattern in forbidden:
            self.assertNotRegex(log_format.group(1), pattern)

    def test_request_method_is_reduced_to_allowlist(self):
        method_map = re.search(
            r"map\s+\$request_method\s+\$runninggu_log_method\s*\{(.*?)\}",
            LOG_POLICY,
            re.DOTALL,
        )
        self.assertIsNotNone(method_map)
        self.assertRegex(method_map.group(1), r"\bdefault\s+OTHER;")

    def test_every_server_uses_minimal_access_log_and_discards_request_error_log(self):
        for path in SERVER_FILES:
            text = path.read_text(encoding="utf-8")
            servers = re.findall(r"server\s*\{(.*?)\n\}", text, re.DOTALL)
            self.assertTrue(servers, path.name)
            for server in servers:
                self.assertRegex(
                    server,
                    r"access_log\s+/var/log/nginx/[^;]+\s+runninggu_minimal;",
                    path.name,
                )
                self.assertIn("error_log /dev/null crit;", server, path.name)

    def test_server_files_do_not_reference_raw_request_log_variables(self):
        forbidden = ("$request ", "$request_uri", "$args", "$query_string", "$http_")
        for path in SERVER_FILES:
            text = path.read_text(encoding="utf-8")
            log_directives = "\n".join(re.findall(r"(?:access|error)_log\s+[^;]+;", text))
            for value in forbidden:
                self.assertNotIn(value, log_directives, path.name)


if __name__ == "__main__":
    unittest.main()
