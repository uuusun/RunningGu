#!/usr/bin/env python3
"""고정 공개 시험 좌표로 GraphHopper 성공·실패 로그의 정보 노출을 검사한다.

실행 중인 격리 시험 container 전용이다. 운영 부하/회귀 합격을 대신하지 않는다.
사용자 좌표·응답 본문·로그 원문은 출력하지 않는다(artifact 계약 §9.2).
"""

import argparse
import datetime
import json
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", required=True)
    parser.add_argument("--base-url", default="http://127.0.0.1:18989")
    args = parser.parse_args()
    parsed = urllib.parse.urlparse(args.base_url)
    if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost"):
        parser.error("격리 시험의 loopback HTTP 주소만 허용합니다.")
    inspected = subprocess.run(
        ["docker", "inspect", "--format", "{{json .NetworkSettings.Ports}}", args.container],
        capture_output=True, text=True, check=True,
    )
    bindings = json.loads(inspected.stdout).get("8989/tcp") or []
    if not any(binding["HostIp"] == "127.0.0.1"
               and int(binding["HostPort"]) == (parsed.port or 80) for binding in bindings):
        parser.error("시험 URL과 로그를 읽을 container의 loopback 포트가 다릅니다.")

    marker = "runninggu-log-privacy-" + uuid.uuid4().hex
    since = datetime.datetime.now(datetime.timezone.utc).isoformat()
    results = []
    for profile, expected_status in (("run", 200), ("privacy-test-invalid-profile", 400)):
        query = urllib.parse.urlencode({
            "point": "37.5246,126.9203", "profile": profile,
            "algorithm": "round_trip", "round_trip.distance": 3900,
            "round_trip.seed": 14, "points_encoded": "false", "elevation": "true",
            "instructions": "true", "details": "road_class",
        })
        request = urllib.request.Request(
            args.base_url.rstrip("/") + "/route?" + query,
            headers={"User-Agent": marker},
        )
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                status = response.status
                payload = json.load(response)
        except urllib.error.HTTPError as error:
            status = error.code
            payload = json.loads(error.read())
        if status != expected_status or (status == 200 and not payload.get("paths")):
            print(json.dumps({"expectedStatus": expected_status, "actualStatus": status,
                              "passed": False}))
            return 1
        results.append(status)

    # 비동기 log appender가 flush할 시간을 준다. 로그 원문은 결과에 포함하지 않는다.
    time.sleep(2)
    logs = subprocess.run(["docker", "logs", "--since", since, args.container],
                          capture_output=True, text=True, check=True)
    lines = (logs.stdout + logs.stderr).splitlines()
    leaked = sum(any(value in line for value in (
        "37.5246", "126.9203", marker, "/route?",
    )) for line in lines)
    print(json.dumps({"expectedHttpStatuses": results, "leakedLogLines": leaked,
                      "passed": leaked == 0}))
    return 0 if leaked == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
