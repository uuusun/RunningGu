#!/usr/bin/env python3
"""4GiB 기본 운영용 소량 점검. 부하 스케줄 없이 요청별 안전한 결과를 즉시 보존한다."""

from __future__ import annotations

import argparse
import datetime as dt
import getpass
import json
from pathlib import Path
import sys
import time

import run_api_load as api


READ_CASES = tuple(case for case in api.CASE_GROUPS if case not in {"favorite_add", "favorite_delete"})


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def error_code(error: BaseException) -> str:
    if isinstance(error, KeyboardInterrupt):
        return "operator_interrupted"
    if isinstance(error, api.LoadError):
        # LoadError는 기존 실행기가 사용하는 고정 코드다. 예외 원문은 기록하지 않는다.
        return error.code
    return "internal_smoke_error"


class Evidence:
    def __init__(self, output: Path, run_id: str, digest: str):
        output.mkdir(parents=True, exist_ok=False)
        self.path = output / "api-smoke-summary.json"
        self.data = {
            "scope": "basic_operation_smoke", "runId": run_id,
            "requestSetSha256": digest, "startedAt": now(), "finishedAt": None,
            "loadExecuted": False, "scheduledLoadExecuted": False,
            "plannedCases": list(READ_CASES), "completedCases": [],
            "requests": [], "stages": [], "observations": [],
            "accountIsolationPassed": False, "cleanupSucceeded": False,
            "logoutSucceeded": False, "status": "running", "error": None,
        }
        self.save()

    def save(self):
        temp = self.path.with_suffix(".tmp")
        temp.write_text(json.dumps(self.data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        # Windows의 일시적인 파일 공유 잠금만 재시도한다. HTTP 요청은 다시 보내지 않는다.
        for attempt in range(10):
            try:
                temp.replace(self.path)
                break
            except PermissionError as error:
                if getattr(error, "winerror", None) not in {5, 32, 33} or attempt == 9:
                    raise
                time.sleep(0.05)

    def stage(self, name, status, error=None):
        self.data["stages"].append({"stage": name, "status": status, "at": now(), "error": error})
        self.save()


class RecordingClient:
    def __init__(self, client, evidence: Evidence):
        self.client = client
        self.evidence = evidence

    def exchange(self, spec, access_token=None, before_send=None):
        row = {
            "sequence": len(self.evidence.data["requests"]) + 1,
            "caseId": spec.case_id, "group": spec.group, "account": spec.account_label,
            "startedAt": now(), "expectedStatus": spec.expected_status,
            "status": None, "durationMs": None, "responseBytes": None,
            "httpExpected": None, "state": "started", "error": None,
        }
        self.evidence.data["requests"].append(row)
        self.evidence.data["loadExecuted"] = True
        self.evidence.save()
        try:
            result = self.client.exchange(spec, access_token, before_send)
            row.update(status=result.status, durationMs=round(result.duration_ms, 3),
                       responseBytes=result.response_bytes,
                       httpExpected=result.status == spec.expected_status, state="completed")
            if spec.group in api.GROUP_LIMITS_MS and result.duration_ms > api.GROUP_LIMITS_MS[spec.group][1]:
                self.evidence.data["observations"].append({
                    "sequence": row["sequence"], "caseId": spec.case_id,
                    "kind": "latency_target_exceeded", "durationMs": round(result.duration_ms, 3),
                    "targetMs": api.GROUP_LIMITS_MS[spec.group][1],
                })
            return result
        except BaseException as error:
            row.update(state="interrupted" if isinstance(error, KeyboardInterrupt) else "failed",
                       error=error_code(error), durationMs=getattr(error, "duration_ms", None))
            raise
        finally:
            row["finishedAt"] = now()
            self.evidence.save()


def validate_gate(gate: dict, run_id: str):
    api.require(gate.get("runId") == run_id, "guard_gate_mismatch")
    api.require(all(gate.get(key) is True for key in (
        "guardPreflightPassed", "metricsStarted", "actualSendingVerified")), "guard_gate_missing")
    expires = dt.datetime.fromisoformat(gate["expiresAt"])
    api.require(expires.tzinfo is not None and expires > dt.datetime.now(dt.timezone.utc), "guard_gate_expired")


def execute(fixture: dict, run_id: str, output: Path, gate: dict,
            secret_prompt=getpass.getpass, client_factory=api.HttpClient) -> dict:
    api.require(bool(api.RUN_ID.fullmatch(run_id)), "run_id")
    api.validate_fixture(fixture, require_approved=True)
    validate_gate(gate, run_id)
    evidence = Evidence(output, run_id, api.canonical_sha256(fixture))
    client = RecordingClient(client_factory(), evidence)
    sessions = {}
    writes_started = False
    stage = "login"
    try:
        sessions = api.login_sessions(client, secret_prompt)
        evidence.stage(stage, "completed")
        stage = "primary_cases"
        for case in READ_CASES:
            label = "A" if case in {"me", "favorite_list"} else None
            spec = api.request_spec(case, fixture, label)
            api.perform(spec, client, fixture, sessions)
            evidence.data["completedCases"].append(case)
            evidence.save()
        evidence.stage(stage, "completed")
        stage = "reserved_target_check"
        target = fixture["inputs"]["favoriteContestId"]
        for label in ("A", "B"):
            result = api.perform(api.request_spec("favorite_list", fixture, label), client, fixture, sessions)
            api.require(target not in api.favorite_ids(result.payload), "reserved_target_not_empty")
        evidence.stage(stage, "completed")
        stage = "account_isolation"
        writes_started = True
        api.verify_account_isolation(client, fixture, sessions)
        evidence.data["accountIsolationPassed"] = True
        evidence.stage(stage, "completed")
        evidence.data["status"] = "completed"
    except BaseException as error:
        code = error_code(error)
        evidence.data.update(status="interrupted" if isinstance(error, KeyboardInterrupt) else "needs_attention", error=code)
        evidence.stage(stage, "failed", code)
    finally:
        try:
            evidence.data["cleanupSucceeded"] = (not writes_started) or api.cleanup_favorites(client, fixture, sessions)
        except BaseException as error:
            evidence.stage("cleanup", "failed", error_code(error))
        logout_results = []
        for session in sessions.values():
            try:
                logout_results.append(api.logout_session(client, session))
            except BaseException as error:
                logout_results.append(False)
                evidence.stage("logout", "failed", error_code(error))
        evidence.data["logoutSucceeded"] = bool(sessions) and all(logout_results)
        evidence.data["cleanupAttempted"] = writes_started
        if not (evidence.data["cleanupSucceeded"] and evidence.data["logoutSucceeded"]):
            evidence.data["status"] = "needs_attention"
        evidence.data["finishedAt"] = now()
        evidence.save()
        sessions.clear()
    return evidence.data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--run-id")
    parser.add_argument("--guard-evidence", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    fixture, digest = api.load_fixture(args.fixture)
    api.validate_fixture(fixture, require_approved=True)
    if not args.execute:
        print(json.dumps({"scope": "basic_operation_smoke", "loadExecuted": False,
                          "scheduledLoadExecuted": False, "requestSetSha256": digest,
                          "plannedCases": list(READ_CASES)}, sort_keys=True))
        return 0
    api.require(bool(args.run_id and args.guard_evidence and args.output), "execute_arguments")
    gate = json.loads(args.guard_evidence.read_text(encoding="utf-8-sig"))
    result = execute(fixture, args.run_id, args.output, gate)
    print(json.dumps({"runId": args.run_id, "status": result["status"],
                      "completedCases": len(result["completedCases"]), "error": result["error"]}))
    return 0 if result["status"] == "completed" else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except api.LoadError as error:
        print(json.dumps({"status": "not_started", "error": error.code}))
        raise SystemExit(1)
