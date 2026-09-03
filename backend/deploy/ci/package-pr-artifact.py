#!/usr/bin/env python3
"""PR head 검증 묶음의 출처와 checksum을 기록한다 (artifact 계약 §11.1)."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import subprocess


PAYLOADS = ("runninggu-server.jar", "runninggu-contest-import.jar", "data/contest_snapshot.json")
MANIFEST = "release-manifest.txt"


def commit(value: str) -> str:
    if not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("commit은 40자리 lowercase SHA여야 합니다.")
    return value


def positive_integer(value: str) -> str:
    if not re.fullmatch(r"[1-9][0-9]*", value):
        raise ValueError("PR·run·attempt 식별자는 양의 정수여야 합니다.")
    return value


def manifest_bytes(actual_head: str, expected_head: str, run_id: str, attempt: str,
                   pr_number: str, base_commit: str, integration_commit: str) -> bytes:
    if commit(actual_head) != commit(expected_head):
        raise ValueError("실제 checkout HEAD와 검증할 PR head가 다릅니다.")
    fields = (
        ("git_commit", actual_head),
        ("workflow_run_id", positive_integer(run_id)),
        ("workflow_run_attempt", positive_integer(attempt)),
        ("artifact_kind", "pr-validation"),
        ("allowed_environment", "staging"),
        ("pull_request_number", positive_integer(pr_number)),
        ("head_commit", actual_head),
        ("base_commit", commit(base_commit)),
        ("integration_test_commit", commit(integration_commit)),
    )
    return "".join(f"{key}={value}\n" for key, value in fields).encode("utf-8")


def package(output: Path, content: bytes) -> None:
    if output.is_symlink() or not output.is_dir():
        raise ValueError("묶음 경로가 일반 directory가 아닙니다.")
    for name in (*PAYLOADS, MANIFEST, "SHA256SUMS"):
        path = output / name
        if path.is_symlink() or path.parent.is_symlink():
            raise ValueError(f"묶음 파일에 symlink를 허용하지 않습니다: {name}")
        if name in PAYLOADS and (not path.is_file() or path.stat().st_size == 0):
            raise ValueError(f"필수 payload가 없거나 비어 있습니다: {name}")
    allowed = {*PAYLOADS, MANIFEST, "SHA256SUMS", "data"}
    for path in output.rglob("*"):
        if path.is_symlink() or path.relative_to(output).as_posix() not in allowed:
            raise ValueError("묶음에 허용하지 않은 파일 또는 symlink가 있습니다.")
    (output / MANIFEST).write_bytes(content)
    checksums = "".join(
        f"{hashlib.sha256((output / name).read_bytes()).hexdigest()}  {name}\n"
        for name in (*PAYLOADS, MANIFEST)
    )
    (output / "SHA256SUMS").write_bytes(checksums.encode("utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expected-head", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--attempt", required=True)
    parser.add_argument("--pr-number", required=True)
    parser.add_argument("--base-commit", required=True)
    parser.add_argument("--integration-commit", required=True)
    args = parser.parse_args()
    try:
        actual_head = subprocess.check_output(
            ["git", "-C", str(args.repository), "rev-parse", "HEAD"], text=True
        ).strip()
        package(args.output, manifest_bytes(
            actual_head, args.expected_head, args.run_id, args.attempt,
            args.pr_number, args.base_commit, args.integration_commit,
        ))
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"PR 검증용 묶음 생성 실패: {error}\n")
    print(f"PR 검증용 묶음 생성: PR {args.pr_number}, commit {actual_head}")


if __name__ == "__main__":
    main()
