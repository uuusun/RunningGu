#!/usr/bin/env python3
"""실서버에 가짜 표식 요청을 보내고 nginx 파일·journal의 노출 건수만 출력한다."""

import argparse
import datetime
import gzip
import json
import os
import pathlib
import re
import socket
import ssl
import subprocess
import time
import urllib.parse
import uuid


EMAIL_PATTERN = re.compile(
    r"(?i)(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@"
    r"[A-Za-z0-9.-]+\.[A-Za-z]{2,}(?![A-Za-z0-9.-])"
)
COORDINATE_PATTERN = re.compile(
    r"(?i)(?:^|[?&,\s\"])"
    r"(?:lat|latitude|lng|lon|longitude|point|x|y)="
    r"-?\d{1,3}\.\d+"
)
SECRET_PATTERN = re.compile(
    r"(?i)(?:authorization\s*[:=]\s*bearer\s+\S+|"
    r"(?:password|access[_-]?token|refresh[_-]?token|reset[_-]?token|"
    r"verification[_-]?code)\s*[:=]\s*[^\s,}\]]+|"
    r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)"
)


def request(address, port, host, method, target, headers=None, body=b"", tls=False):
    raw_socket = socket.create_connection((address, port), timeout=5)
    connection = raw_socket
    if tls:
        context = ssl.create_default_context()
        connection = context.wrap_socket(raw_socket, server_hostname=host)
    request_headers = {
        "Host": host,
        "Connection": "close",
        "Content-Length": str(len(body)),
        **(headers or {}),
    }
    head = f"{method} {target} HTTP/1.1\r\n" + "".join(
        f"{name}: {value}\r\n" for name, value in request_headers.items()
    )
    connection.sendall(head.encode("ascii") + b"\r\n" + body)
    response = bytearray()
    try:
        while len(response) < 65_536:
            chunk = connection.recv(8192)
            if not chunk:
                break
            response.extend(chunk)
    except (ConnectionResetError, ssl.SSLError):
        pass
    finally:
        connection.close()
    if not response:
        return 0
    first_line = bytes(response).split(b"\r\n", 1)[0]
    parts = first_line.split()
    return int(parts[1]) if len(parts) >= 2 and parts[1].isdigit() else -1


def read_journal(since, units):
    command = ["journalctl", "--since", since, "--output=cat", "--no-pager"]
    for unit in units:
        command.extend(("-u", unit))
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise RuntimeError("journal 조회에 실패했습니다")
    return result.stdout.splitlines()


def file_size(path):
    return path.stat().st_size if path.exists() else 0


def log_path(log_dir, filename):
    """로그 파일 인자는 로그 디렉터리 바로 아래의 파일명만 허용한다."""
    candidate = pathlib.Path(filename)
    if candidate.name != filename or candidate.is_absolute():
        raise ValueError("로그 파일은 경로가 아닌 파일명이어야 합니다")
    return log_dir / candidate


def read_nginx_logs(log_dir):
    lines = []
    files = 0
    for path in sorted(log_dir.glob("*.log*")):
        if not path.is_file():
            continue
        files += 1
        if path.suffix == ".gz":
            with gzip.open(path, "rt", encoding="utf-8", errors="replace") as source:
                lines.extend(source.read().splitlines())
        else:
            lines.extend(path.read_text(errors="replace").splitlines())
    return files, lines


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True)
    parser.add_argument("--address", default="127.0.0.1")
    parser.add_argument("--http-port", type=int, default=80)
    parser.add_argument("--https-port", type=int, default=443)
    parser.add_argument("--nginx-log-dir", default="/var/log/nginx")
    parser.add_argument(
        "--named-access-log", default="runninggu-staging.access.log"
    )
    parser.add_argument(
        "--rejected-access-log", default="runninggu-rejected.access.log"
    )
    parser.add_argument(
        "--legacy-error-log", default="runninggu-staging.error.log"
    )
    parser.add_argument("--backend-unit", default="runninggu-backend.service")
    parser.add_argument("--nginx-unit", default="nginx.service")
    parser.add_argument("--audit-since", default="14 days ago")
    args = parser.parse_args()

    if os.name != "posix" or os.geteuid() != 0:
        parser.error("Linux 서버에서 root 권한으로 실행해야 합니다")
    if args.address not in ("127.0.0.1", "::1"):
        parser.error("서버 내부 loopback 주소만 허용합니다")

    active = subprocess.run(
        ["systemctl", "is-active", "--quiet", args.backend_unit], check=False
    )
    if active.returncode != 0:
        parser.error("백엔드 service가 active가 아닙니다")

    log_dir = pathlib.Path(args.nginx_log_dir)
    try:
        named_access = log_path(log_dir, args.named_access_log)
        rejected_access = log_path(log_dir, args.rejected_access_log)
        legacy_error = log_path(log_dir, args.legacy_error_log)
    except ValueError as exception:
        parser.error(str(exception))
    before = {
        "named": file_size(named_access),
        "rejected": file_size(rejected_access),
        "legacyError": file_size(legacy_error),
    }

    marker = "runninggu-log-privacy-" + uuid.uuid4().hex
    private_email = marker + "@example.invalid"
    private_latitude = "37.5665123"
    private_longitude = "126.9780987"
    private_token = marker + "-token"
    user_agent = marker + "/" + private_latitude + "/" + private_longitude
    started_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
    encoded_email = urllib.parse.quote(private_email, safe="")

    statuses = {}
    statuses["httpRedirect"] = request(
        args.address,
        args.http_port,
        args.host,
        "GET",
        "/api/auth/email/exists?email=" + encoded_email,
        {"User-Agent": user_agent},
    )
    statuses["emailExists"] = request(
        args.address,
        args.https_port,
        args.host,
        "GET",
        "/api/auth/email/exists?email=" + encoded_email,
        {"User-Agent": user_agent},
        tls=True,
    )
    statuses["coordinateValidation"] = request(
        args.address,
        args.https_port,
        args.host,
        "GET",
        "/api/pois?category=FOOD&lat=" + private_latitude
        + "&lng=" + private_longitude + "&radius=0&size=1",
        {"User-Agent": user_agent},
        tls=True,
    )
    login_body = json.dumps(
        {"email": private_email, "password": private_token}, separators=(",", ":")
    ).encode("utf-8")
    statuses["loginFailure"] = request(
        args.address,
        args.https_port,
        args.host,
        "POST",
        "/api/auth/login",
        {"Content-Type": "application/json", "User-Agent": user_agent},
        login_body,
        tls=True,
    )
    statuses["unknownHost"] = request(
        args.address,
        args.http_port,
        "privacy-test.invalid",
        "GET",
        "/?email=" + encoded_email + "&lat=" + private_latitude,
        {"User-Agent": user_agent},
    )
    statuses["oversizedHeader"] = request(
        args.address,
        args.https_port,
        args.host,
        "GET",
        "/",
        {"X-Privacy-Test": marker * 700, "User-Agent": user_agent},
        tls=True,
    )

    expected = {
        "httpRedirect": {301},
        "emailExists": {200},
        "coordinateValidation": {400},
        "loginFailure": {401, 429},
        "unknownHost": {0},
        "oversizedHeader": {400, 431},
    }
    status_ok = all(statuses[name] in allowed for name, allowed in expected.items())

    time.sleep(1)
    after = {
        "named": file_size(named_access),
        "rejected": file_size(rejected_access),
        "legacyError": file_size(legacy_error),
    }
    access_growth = {
        "named": after["named"] > before["named"],
        "rejected": after["rejected"] > before["rejected"],
    }

    nginx_file_count, nginx_lines = read_nginx_logs(log_dir)
    journal_lines = read_journal(started_at, [args.backend_unit, args.nginx_unit])
    audit_journal_lines = read_journal(
        args.audit_since, [args.backend_unit, args.nginx_unit]
    )
    inspected = nginx_lines + journal_lines
    forbidden = (
        marker,
        private_email,
        private_latitude,
        private_longitude,
        private_token,
        encoded_email,
    )
    forbidden_matches = sum(
        1 for line in inspected if any(value in line for value in forbidden)
    )
    audited = nginx_lines + audit_journal_lines
    pattern_matches = {
        "email": sum(bool(EMAIL_PATTERN.search(line)) for line in audited),
        "coordinate": sum(bool(COORDINATE_PATTERN.search(line)) for line in audited),
        "secret": sum(bool(SECRET_PATTERN.search(line)) for line in audited),
    }
    legacy_error_unchanged = after["legacyError"] == before["legacyError"]
    passed = (
        status_ok
        and all(access_growth.values())
        and legacy_error_unchanged
        and forbidden_matches == 0
        and all(count == 0 for count in pattern_matches.values())
    )
    print(json.dumps({
        "statuses": statuses,
        "statusChecksPassed": status_ok,
        "accessLogGrowth": access_growth,
        "legacyRequestErrorLogUnchanged": legacy_error_unchanged,
        "nginxLogFilesInspected": nginx_file_count,
        "nginxLogLinesInspected": len(nginx_lines),
        "testJournalLinesInspected": len(journal_lines),
        "auditJournalLinesInspected": len(audit_journal_lines),
        "forbiddenMatches": forbidden_matches,
        "historicalPatternMatches": pattern_matches,
        "passed": passed,
    }, ensure_ascii=False))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
