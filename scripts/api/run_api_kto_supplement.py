#!/usr/bin/env python3
"""제외했던 공공데이터·동선 생성 245건 보완 검증. 계획은 staging-4g-kto-supplement-plan."""
import argparse
import json
from pathlib import Path
import time

import run_api_capacity as cap
import run_api_load as api

PROFILE = "app-capacity-v5-kto-only"
CASES = ("festival", "poi", "itinerary_generate")

def selected_schedule():
    original = api.arrivals.build_schedule()
    api.require(api.arrivals.schedule_sha256(original) == cap.SCHEDULE_HASH, "schedule_hash_mismatch")
    return [r for r in original if r["caseId"] in CASES]

def scope():
    selected = selected_schedule()
    return {"executionProfile": PROFILE, "guardPolicy": cap.POLICY, "referencePlanned": 2100,
            "selectedPlanned": len(selected), "excludedCount": 2100-len(selected),
            "warmupPlanned": sum(r["phase"] == "warmup" for r in selected),
            "measurementPlanned": sum(r["phase"] == "measurement" for r in selected),
            "requestsPerMinute": 7, "maxInFlight": 4,
            "fixtureSha256": cap.FIXTURE_HASH, "referenceScheduleSha256": cap.SCHEDULE_HASH,
            "selectedScheduleSha256": api.arrivals.schedule_sha256(selected),
            "authenticationRequired": False, "fullMixedLoadPassed": False}

def execute(fixture, run_id, output, gate, client_factory=api.HttpClient, scheduler=None):
    api.require(bool(api.RUN_ID.fullmatch(run_id)), "run_id_contract")
    api.require(api.canonical_sha256(fixture) == cap.FIXTURE_HASH, "fixture_hash_mismatch")
    api.validate_fixture(fixture, require_approved=True)
    cap.validate_gate(gate, run_id)
    api.require(gate.get("executionProfile") == PROFILE, "profile_gate_mismatch")
    selected = selected_schedule()
    ev = cap.Evidence(output, run_id, selected)
    cap.save(output/"scope.json", scope())
    ev.event("execution_profile", **scope())
    client = cap.RecordingClient(client_factory(), ev)
    preflight = []
    cpu, wall = time.process_time(), time.monotonic()
    try:
        ev.stage_event("preflight")
        for case in CASES:
            response = api.perform(api.request_spec(case, fixture), client, fixture, {})
            preflight.append({"caseId": case, "success": True, "durationMs": round(response.duration_ms, 3)})
        ev.stage_event("load")
        if scheduler:
            scheduler(client, fixture, {}, selected, ev)
        else:
            cap.execute_schedule(client, fixture, {}, selected, ev, refresh_at=None)
    except BaseException as error:
        ev.cancel(cap.code(error))
    finally:
        ev.load_finished_at = ev.load_finished_at or cap.now()
        for row in ev.rows.values():
            if row["dispatchedAt"] is None and row["error"] is None:
                row["error"] = ev.error or "schedule_not_run"
        rows = list(ev.rows.values())
        c = cap.counts(rows)
        cap.save(output.parent/"load-finished.json", {"runId": run_id, "at": ev.load_finished_at, "reason": ev.error})
        cap.save(output/"partial-summary.json", {"runId": run_id, "at": cap.now(), "error": ev.error,
                  "counts": c, "requests": rows, "auxiliaryCounts": cap.counts(ev.aux), "auxiliaryRequests": ev.aux})
        # 공통 요약기의 인증/전체 건수 조건을 부분 시험 합격으로 바꾸지 않고 별도 범위로 판정한다.
        summary = api.summarize_run(run_id, cap.FIXTURE_HASH, selected, ev.results, [],
            c["dispatched"], preflight, False, False, time.process_time()-cpu, time.monotonic()-wall)
        thresholds = [r for r in summary["groups"]+summary["heavyScenarios"] if r["planned"]]
        passed = (ev.error is None and c["successful"] == len(selected) and
            summary["missedStarts"] == 0 and summary["lateDispatches"] == 0 and
            all(r["passed"] for r in thresholds) and len(preflight) == 3)
        summary.update(executionProfile=PROFILE, acceptancePolicy=PROFILE, guardPolicy=cap.POLICY,
            startedAt=ev.started_at, loadStartedAt=ev.load_started_at, loadFinishedAt=ev.load_finished_at,
            finishedAt=cap.now(), error=ev.error, counts=c, requests=rows,
            auxiliaryCounts=cap.counts(ev.aux), auxiliaryRequests=ev.aux,
            phases=[{"phase": phase, **cap.counts([r for r in rows if r["phase"] == phase])}
                    for phase in ("warmup", "measurement")],
            status="completed" if ev.error is None and c["notExecuted"] == 0 else "needs_attention",
            passed=passed, subsetChecksPassed=passed, fullAppLoadPassed=False,
            referencePlannedRequests=2100, selectedPlannedRequests=245, excludedRequestCount=1855,
            referenceScheduleSha256=cap.SCHEDULE_HASH, verdictScope="kto_dependent_requests_only",
            capacityVerdict="requires_runtime_analysis", cleanupRequired=False, logoutRequired=False,
            cleanupSucceeded=None, logoutSucceeded=None, credentialsCollected=False)
        summary["apiCases"] = []
        for case in CASES:
            matching = [r for r in rows if r["phase"] == "measurement" and r["caseId"] == case]
            duration = [r["durationMs"] for r in matching if r["durationMs"] is not None]
            summary["apiCases"].append({"caseId": case, **cap.counts(matching),
                "p50Ms": api.percentile(duration, 50) if duration else None,
                "p95Ms": api.percentile(duration, 95) if duration else None,
                "maxMs": max(duration) if duration else None})
        summary["notExecutedReasons"] = dict(api.Counter(r["error"] for r in rows if not r["dispatchedAt"]))
        summary["requestFailureClasses"] = dict(api.Counter(r["error"] for r in rows
            if r["dispatchedAt"] and r["completedAt"] and not r["success"]))
        cap.save(output/"api-load-summary.json", summary)
        ev.event("run_finished", counts=c, error=ev.error, executionProfile=PROFILE)
        ev.events.close()
    return summary

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--fixture", type=Path, default=Path(__file__).parent/"fixtures/staging-api-load-v1.approved.json")
    p.add_argument("--execute", action="store_true")
    p.add_argument("--run-id")
    p.add_argument("--output", type=Path)
    p.add_argument("--guard-evidence", type=Path)
    args = p.parse_args()
    fixture, digest = api.load_fixture(args.fixture, require_approved=True)
    api.require(digest == cap.FIXTURE_HASH, "fixture_hash_mismatch")
    if not args.execute:
        print(json.dumps(scope(), sort_keys=True))
        return 0
    api.require(bool(args.run_id and args.output and args.guard_evidence), "execute_arguments")
    result = execute(fixture, args.run_id, args.output,
                     json.loads(args.guard_evidence.read_text(encoding="utf-8-sig")))
    print(json.dumps({k: result[k] for k in ("runId", "status", "counts", "passed", "error")}))
    cap.save(args.output.parent/"completed.json", {"runId": args.run_id, "status": result["status"],
             "counts": result["counts"], "passed": result["passed"], "credentialsCollected": False, "at": cap.now()})
    return 0 if result["passed"] else 1

if __name__ == "__main__":
    raise SystemExit(main())
