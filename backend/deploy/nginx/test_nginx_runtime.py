#!/usr/bin/env python3
"""Linux nginx로 배포 설정의 문법과 요청 로그 개인정보 제외를 격리 검증한다."""

import json
import pathlib
import re
import shutil
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.parse
import uuid


ROOT = pathlib.Path(__file__).resolve().parent
ACCESS_LINE = re.compile(
    r"^\S+ - \[[^]]+\] method=(?:GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS|OTHER) "
    r"status=\d{3} bytes=\d+ request_time=\d+(?:\.\d+)? "
    r"upstream_status=(?:\d{3}|-) upstream_response_time=(?:\d+(?:\.\d+)?|-)$"
)


def free_port():
    with socket.socket() as candidate:
        candidate.bind(("127.0.0.1", 0))
        return candidate.getsockname()[1]


def request(port, host, method, target, headers=None, body=b"", tls=False):
    connection = socket.create_connection(("127.0.0.1", port), timeout=5)
    if tls:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        connection = context.wrap_socket(connection, server_hostname=host)
    request_headers = {
        "Host": host,
        "Connection": "close",
        "Content-Length": str(len(body)),
        **(headers or {}),
    }
    head = f"{method} {target} HTTP/1.1\r\n" + "".join(
        f"{name}: {value}\r\n" for name, value in request_headers.items()
    )
    try:
        connection.sendall(head.encode("ascii") + b"\r\n" + body)
        response = bytearray()
        while len(response) < 65_536:
            chunk = connection.recv(8192)
            if not chunk:
                break
            response.extend(chunk)
    except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
        response = bytearray()
    finally:
        connection.close()
    if not response:
        return 0
    first_line = bytes(response).split(b"\r\n", 1)[0]
    parts = first_line.split()
    return int(parts[1]) if len(parts) >= 2 and parts[1].isdigit() else -1


def rewrite_server(source, replacements):
    text = source.read_text(encoding="utf-8")
    for before, after in replacements:
        if before not in text:
            raise AssertionError(f"치환할 nginx 설정이 없습니다: {source.name}")
        text = text.replace(before, after)
    return text


def main():
    nginx = shutil.which("nginx")
    openssl = shutil.which("openssl")
    if not nginx or not openssl:
        raise SystemExit("Linux nginx와 openssl이 필요합니다")

    with tempfile.TemporaryDirectory(prefix="runninggu-nginx-log-privacy-") as directory:
        work = pathlib.Path(directory)
        http_port = free_port()
        https_port = free_port()
        bootstrap_port = free_port()
        cert = work / "cert.pem"
        key = work / "key.pem"
        named_access = work / "named.access.log"
        rejected_access = work / "rejected.access.log"
        lifecycle = work / "lifecycle.log"
        ssl_params = work / "runninggu-ssl-params.conf"
        ssl_params.write_text(
            (ROOT / "runninggu-ssl-params.conf").read_text(encoding="utf-8"),
            encoding="utf-8",
        )
        subprocess.run(
            [
                openssl,
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-nodes",
                "-days",
                "1",
                "-subj",
                "/CN=staging-api.runninggu.store",
                "-keyout",
                str(key),
                "-out",
                str(cert),
            ],
            check=True,
            capture_output=True,
        )

        common = [
            ("listen 80;", f"listen 127.0.0.1:{http_port};"),
            ("listen 443 ssl;", f"listen 127.0.0.1:{https_port} ssl;"),
            ("/etc/letsencrypt/live/staging-api.runninggu.store/fullchain.pem", str(cert)),
            ("/etc/letsencrypt/live/staging-api.runninggu.store/privkey.pem", str(key)),
            ("/etc/nginx/snippets/runninggu-ssl-params.conf", str(ssl_params)),
            ("/var/log/nginx/runninggu-staging.access.log", str(named_access)),
            ("proxy_pass http://127.0.0.1:8080;", "proxy_pass http://127.0.0.1:9;"),
        ]
        staging = rewrite_server(ROOT / "staging-api.conf", common)
        default = rewrite_server(
            ROOT / "default-reject.conf",
            [
                ("listen 80 default_server;", f"listen 127.0.0.1:{http_port} default_server;"),
                (
                    "listen 443 ssl default_server;",
                    f"listen 127.0.0.1:{https_port} ssl default_server;",
                ),
                ("/etc/letsencrypt/live/staging-api.runninggu.store/fullchain.pem", str(cert)),
                ("/etc/letsencrypt/live/staging-api.runninggu.store/privkey.pem", str(key)),
                ("/etc/nginx/snippets/runninggu-ssl-params.conf", str(ssl_params)),
                ("/var/log/nginx/runninggu-rejected.access.log", str(rejected_access)),
            ],
        )
        (work / "staging.conf").write_text(staging, encoding="utf-8")
        (work / "default.conf").write_text(default, encoding="utf-8")
        bootstrap = rewrite_server(
            ROOT / "staging-api.bootstrap.conf",
            [
                ("listen 80;", f"listen 127.0.0.1:{bootstrap_port};"),
                ("/var/log/nginx/runninggu-staging.access.log", str(named_access)),
            ],
        )
        (work / "bootstrap.conf").write_text(bootstrap, encoding="utf-8")
        bootstrap_config = work / "bootstrap-nginx.conf"
        bootstrap_config.write_text(
            f"""
pid {work / 'bootstrap-nginx.pid'};
error_log {lifecycle} notice;
events {{}}
http {{
    include {ROOT / 'runninggu-log-privacy.conf'};
    include {work / 'bootstrap.conf'};
}}
""",
            encoding="utf-8",
        )
        config = work / "nginx.conf"
        config.write_text(
            f"""
pid {work / 'nginx.pid'};
error_log {lifecycle} notice;
events {{}}
http {{
    include {ROOT / 'runninggu-log-privacy.conf'};
    include {work / 'staging.conf'};
    include {work / 'default.conf'};
}}
""",
            encoding="utf-8",
        )

        bootstrap_command = [
            nginx,
            "-p",
            str(work) + "/",
            "-c",
            str(bootstrap_config),
        ]
        bootstrap_syntax = subprocess.run(
            bootstrap_command + ["-t"], capture_output=True, text=True
        )
        if bootstrap_syntax.returncode != 0:
            raise RuntimeError("bootstrap nginx -t 실패: " + bootstrap_syntax.stderr.strip())

        base_command = [nginx, "-p", str(work) + "/", "-c", str(config)]
        final_syntax = subprocess.run(base_command + ["-t"], capture_output=True, text=True)
        if final_syntax.returncode != 0:
            raise RuntimeError("final nginx -t 실패: " + final_syntax.stderr.strip())

        process = subprocess.Popen(
            base_command + ["-g", "daemon off;"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        try:
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("nginx가 요청 검사 전에 종료됐습니다")
                try:
                    with socket.create_connection(("127.0.0.1", http_port), timeout=0.2):
                        break
                except OSError:
                    time.sleep(0.05)
            else:
                raise RuntimeError("nginx listen 준비 시간이 초과됐습니다")

            marker = "runninggu-private-" + uuid.uuid4().hex
            private_email = marker + "@example.invalid"
            latitude = "37.5665123"
            longitude = "126.9780987"
            token = marker + "-token"
            user_agent = marker + "/" + latitude + "/" + longitude
            encoded_email = urllib.parse.quote(private_email, safe="")
            headers = {"User-Agent": user_agent}
            statuses = {
                "httpRedirect": request(
                    http_port,
                    "staging-api.runninggu.store",
                    "GET",
                    "/api/auth/email/exists?email=" + encoded_email,
                    headers,
                ),
                "coordinateProxyFailure": request(
                    https_port,
                    "staging-api.runninggu.store",
                    "GET",
                    "/api/pois?lat=" + latitude + "&lng=" + longitude,
                    headers,
                    tls=True,
                ),
                "loginProxyFailure": request(
                    https_port,
                    "staging-api.runninggu.store",
                    "POST",
                    "/api/auth/login",
                    {"Content-Type": "application/json", **headers},
                    json.dumps({"email": private_email, "password": token}).encode(),
                    tls=True,
                ),
                "unknownHostHttp": request(
                    http_port,
                    "privacy-test.invalid",
                    "GET",
                    "/?email=" + encoded_email,
                    headers,
                ),
                "unknownHostHttps": request(
                    https_port,
                    "privacy-test.invalid",
                    "GET",
                    "/?lat=" + latitude + "&lng=" + longitude,
                    headers,
                    tls=True,
                ),
                "otherMethod": request(
                    http_port,
                    "staging-api.runninggu.store",
                    "TRACE",
                    "/" + marker,
                    headers,
                ),
                "oversizedHeader": request(
                    https_port,
                    "staging-api.runninggu.store",
                    "GET",
                    "/",
                    {"X-Privacy-Test": marker * 700, **headers},
                    tls=True,
                ),
            }
            expected = {
                "httpRedirect": {301},
                "coordinateProxyFailure": {502},
                "loginProxyFailure": {502},
                "unknownHostHttp": {0},
                "unknownHostHttps": {0},
                "otherMethod": {301, 405},
                "oversizedHeader": {400, 431},
            }
            status_ok = all(statuses[name] in allowed for name, allowed in expected.items())
            time.sleep(0.2)
        finally:
            subprocess.run(base_command + ["-s", "quit"], capture_output=True, text=True)
            try:
                _, stderr = process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                process.terminate()
                _, stderr = process.communicate(timeout=5)

        named_lines = named_access.read_text(errors="replace").splitlines()
        rejected_lines = rejected_access.read_text(errors="replace").splitlines()
        lifecycle_lines = lifecycle.read_text(errors="replace").splitlines()
        all_lines = named_lines + rejected_lines + lifecycle_lines + stderr.splitlines()
        forbidden = (marker, private_email, encoded_email, latitude, longitude, token)
        forbidden_matches = sum(
            1 for line in all_lines if any(value in line for value in forbidden)
        )
        format_mismatches = sum(
            not ACCESS_LINE.fullmatch(line) for line in named_lines + rejected_lines
        )
        method_reduced = any("method=OTHER" in line for line in named_lines)
        passed = (
            status_ok
            and bool(named_lines)
            and bool(rejected_lines)
            and forbidden_matches == 0
            and format_mismatches == 0
            and method_reduced
        )
        print(
            json.dumps(
                {
                    "nginxSyntaxValid": True,
                    "configVariantsTested": ["bootstrap", "final"],
                    "statuses": statuses,
                    "statusChecksPassed": status_ok,
                    "namedAccessLines": len(named_lines),
                    "rejectedAccessLines": len(rejected_lines),
                    "forbiddenMatches": forbidden_matches,
                    "formatMismatches": format_mismatches,
                    "unknownMethodReduced": method_reduced,
                    "passed": passed,
                },
                ensure_ascii=False,
            )
        )
        return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
