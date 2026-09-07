#!/usr/bin/env python3
"""승인된 전체 2,100건 실행·중단 증거 보존 (api-load-test-plan §5.3).

요청 URL·body·응답 원문은 기록하지 않는다. JSONL은 요청 상태 전이마다 flush하며,
종료 JSON은 정상·예외·사용자 중단 모두 작성한다. HTTP 재시도는 없다.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import contextlib
import dataclasses
import datetime as dt
import getpass
import json
import os
from pathlib import Path
import threading
import time
import warnings

import run_api_load as api

POLICY = "app-capacity-v3"
NO_KTO_POLICY = "app-capacity-v4-no-kto"
KTO_CASES = frozenset({"festival", "poi", "itinerary_generate"})
REMAINING_EXTERNAL_CASES = frozenset({"near_curated", "near_osm", "geocode"})
FIXTURE_HASH = "6811484a70d406e555c9bdce8273744ef5be597795a73186c9ed9ea42a818ef1"
SCHEDULE_HASH = "88bd5580cc00ca57796b6ba98cbe4e54b727e02924f773561e7b4a638abd097d"


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def code(error):
    if isinstance(error, (KeyboardInterrupt, EOFError)):
        return "operator_interrupted"
    return error.code if isinstance(error, api.LoadError) else "internal_runner"


def save(path, data):
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for attempt in range(10):
        try:
            temp.replace(path)
            return
        except PermissionError as error:
            if getattr(error, "winerror", None) not in {5, 32, 33} or attempt == 9:
                raise
            time.sleep(0.05)


class Evidence:
    def __init__(self, output, run_id, schedule, exclude_kto=False):
        output.mkdir(parents=True, exist_ok=False)
        self.output, self.run_id, self.schedule = output, run_id, schedule
        self.exclude_kto = exclude_kto
        self.external_blocked = False
        self.lock = threading.RLock()
        self.local = threading.local()
        self.events = (output / "request-events.jsonl").open("x", encoding="utf-8", buffering=1)
        self.rows = {}
        self.aux = []
        self.results = []
        self.maintenance = []
        self.stop = threading.Event()
        self.error = None
        self.started_at = now()
        self.load_started_at = None
        self.load_finished_at = None
        self.stage = "login"
        self.event("run_started", acceptancePolicy=NO_KTO_POLICY if exclude_kto else POLICY, planned=len(schedule),
                   requestSetSha256=FIXTURE_HASH, scheduleSha256=api.arrivals.schedule_sha256(schedule))
        for item in schedule:
            self.rows[item["sequence"]] = {
                "requestId": f'{run_id}-load-{item["sequence"]:04d}',
                "sequence": item["sequence"], "phase": item["phase"],
                "caseId": item["caseId"], "group": api.CASE_GROUPS[item["caseId"]],
                "plannedOffsetMs": item["plannedOffsetMs"], "plannedAt": None,
                "dispatchedAt": None, "completedAt": None, "state": "not_executed",
                "status": None, "durationMs": None, "success": False, "error": None,
                "dispatchDelayMs": None, "responseBytes": 0,
            }

    def event(self, kind, **data):
        with self.lock:
            self.events.write(json.dumps({"event": kind, "at": now(), **data}, ensure_ascii=False) + "\n")
            self.events.flush()

    @contextlib.contextmanager
    def request_context(self, row):
        self.local.row = row
        try:
            yield
        finally:
            self.local.row = None

    def stage_event(self, stage):
        self.stage = stage
        self.event("stage", stage=stage)

    def skipped(self, item, reason, delay=0.0):
        row = self.rows[item["sequence"]]
        row.update(error=reason, dispatchDelayMs=round(delay, 3))
        self.event("not_executed", **row)
        self.results.append(api.TaskResult(item["sequence"], item["phase"], item["caseId"],
            row["group"], False, None, 0, reason, delay, False))

    def cancel(self, reason):
        self.error = self.error or reason
        self.stop.set()
        self.event("stop_requested", reason=reason)

    def check_stop(self):
        if self.exclude_kto and (self.output.parent / "upstream-blocked.json").exists():
            if not self.external_blocked:
                self.event("external_requests_suspended", reason="guard_blocked")
            self.external_blocked = True
        if (self.output.parent / "stop-requested.json").exists():
            self.cancel("external_stop")
        return self.stop.is_set()


class RecordingClient:
    def __init__(self, inner, evidence):
        self.inner, self.evidence = inner, evidence

    def validation_result(self, success, error):
        row = getattr(self.evidence.local, "last_row", None)
        if row is not None:
            row.update(success=success, error=error)
            self.evidence.event("content_validation", **row)

    def exchange(self, spec, access_token=None, before_send=None):
        ev = self.evidence
        row = getattr(ev.local, "row", None)
        if row is None:
            with ev.lock:
                row = {"requestId": f"{ev.run_id}-aux-{len(ev.aux)+1:04d}",
                    "phase": ev.stage, "caseId": spec.case_id, "group": spec.group,
                    "account": spec.account_label, "dispatchedAt": None, "completedAt": None,
                    "state": "not_executed", "status": None, "durationMs": None,
                    "success": False, "error": None, "responseBytes": 0}
                ev.aux.append(row)
        row["expectedStatus"] = spec.expected_status
        ev.local.last_row = row

        def sending():
            if ev.stage == "load" and ev.check_stop():
                raise api.LoadError("safety_stop_before_send")
            if before_send:
                before_send()
            row.update(dispatchedAt=now(), state="dispatched")
            ev.event("dispatch", **row)

        try:
            result = self.inner.exchange(spec, access_token, sending)
            row.update(status=result.status, durationMs=round(result.duration_ms, 3),
                responseBytes=result.response_bytes, state="completed",
                success=result.status == spec.expected_status,
                error=None if result.status == spec.expected_status else "unexpected_http")
            if spec.case_id in {"auth_login", "auth_refresh"} and row["success"]:
                payload=result.payload
                valid=isinstance(payload,dict) and all(isinstance(payload.get(k),str) and payload[k]
                    for k in ("accessToken","refreshToken"))
                if spec.case_id=="auth_login":
                    valid=valid and isinstance(payload.get("user"),dict) and api.positive_int(payload["user"].get("id"))
                if not valid:
                    row.update(success=False,error="authentication_contract")
            if result.status == 500 and ev.stage == "load" and not ev.exclude_kto:
                # 현재 가드 예외는 공통 500이다. 가드 여부 확인 전 추가 송신을 차단하되
                # 서버 자원 수집은 계속하고 RAM 부적합으로 단정하지 않는다.
                ev.cancel("http_500_guard_check_required")
            return result
        except BaseException as error:
            row.update(error=code(error), success=False,
                durationMs=getattr(error, "duration_ms", None),
                state="completed" if row["dispatchedAt"] else "not_executed")
            raise
        finally:
            row["completedAt"] = now() if row["dispatchedAt"] else None
            ev.event("exchange_result", **row)


def wait_until(target, ev, monotonic, sleep):
    while True:
        if ev.check_stop():
            return False
        delay = target - monotonic()
        if delay <= 0:
            return True
        sleep(min(delay, 0.2))


def execute_schedule(client, fixture, sessions, schedule, ev,
                     monotonic=time.monotonic, sleep=time.sleep, refresh_at=1200.5):
    semaphore = threading.BoundedSemaphore(api.arrivals.MAX_IN_FLIGHT)
    futures = {}
    start = monotonic() + 1
    start_utc = dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=1)
    ev.load_started_at = start_utc.isoformat()
    for row in ev.rows.values():
        row["plannedAt"] = (start_utc + dt.timedelta(milliseconds=row["plannedOffsetMs"])).isoformat()
    save(ev.output / "schedule.json", list(ev.rows.values()))
    ev.event("schedule_started", startedAt=ev.load_started_at)

    def refresh():
        if refresh_at is None:
            return
        if not wait_until(start + refresh_at, ev, monotonic, sleep):
            return
        for label in ("A", "B"):
            while not semaphore.acquire(timeout=0.2):
                if ev.stop.is_set():
                    return
            try:
                if ev.stop.is_set():
                    return
                elapsed = api.refresh_session(client, sessions[label])
                ev.maintenance.append({"operation": "refresh", "account": label,
                    "success": True, "durationMs": round(elapsed, 3)})
            except BaseException as error:
                ev.maintenance.append({"operation": "refresh", "account": label,
                    "success": False, "error": code(error)})
                ev.cancel("refresh_failed")
                return
            finally:
                semaphore.release()

    refresh_thread = threading.Thread(target=refresh, name="api-load-refresh")
    if refresh_at is not None:
        refresh_thread.start()

    def task(item, spec, target):
        row = ev.rows[item["sequence"]]
        try:
            with ev.request_context(row):
                result = api.execute_task(item, spec, client, fixture, sessions, semaphore,
                                          planned_start=target, monotonic=monotonic)
            row.update(success=result.success, error=result.error,
                       dispatchDelayMs=result.dispatch_delay_ms)
            ev.event("validated_result", **row)
            with ev.lock:
                ev.results.append(result)
            if result.error == "account_isolation":
                ev.cancel("account_isolation")
            return result
        except BaseException as error:
            ev.cancel(code(error))
            raise

    executor = concurrent.futures.ThreadPoolExecutor(max_workers=api.arrivals.MAX_IN_FLIGHT)
    try:
        for item in schedule:
            target = start + item["plannedOffsetMs"] / 1000
            if not wait_until(target, ev, monotonic, sleep):
                break
            if ev.external_blocked and item["caseId"] in REMAINING_EXTERNAL_CASES:
                ev.skipped(item, "excluded_after_guard_block")
                continue
            dependency = item.get("dependsOnSequence")
            if dependency is not None:
                parent = futures.get(dependency)
                if parent is None or not parent.done() or not parent.result().success:
                    ev.skipped(item, "dependency_not_complete" if parent and not parent.done() else "dependency_failed")
                    continue
            delay = max(0, (monotonic() - target) * 1000)
            if delay > api.MAX_DISPATCH_DELAY_MS:
                ev.skipped(item, "missed_start", delay)
                continue
            if not semaphore.acquire(blocking=False):
                ev.skipped(item, "missed_start", delay)
                continue
            label = api.account_for_item(item) if item["caseId"] in {"me","favorite_list","favorite_add","favorite_delete"} else None
            try:
                spec = api.request_spec(item["caseId"], fixture, label)
                futures[item["sequence"]] = executor.submit(task, item, spec, target)
            except BaseException:
                semaphore.release()
                raise
        for future in futures.values():
            future.result()
    except BaseException as error:
        ev.cancel(code(error))
    finally:
        ev.stop.set()
        # 송신 중 요청은 기존 20초 timeout 안에서 종결시켜 부분 결과를 보존한다.
        executor.shutdown(wait=True)
        if refresh_at is not None:
            refresh_thread.join()
        ev.load_finished_at = now()
        ev.event("schedule_finished", finishedAt=ev.load_finished_at)
        save(ev.output.parent / "load-finished.json", {"runId": ev.run_id,
             "at": ev.load_finished_at, "reason": ev.error})


def counts(rows):
    dispatched = [r for r in rows if r["dispatchedAt"]]
    completed = [r for r in dispatched if r["completedAt"]]
    successful = sum(r["success"] for r in completed)
    return {"planned": len(rows), "dispatched": len(dispatched), "completed": len(completed),
        "successful": successful, "failed": len(completed)-successful,
        "notExecuted": len(rows)-len(dispatched), "inFlight": len(dispatched)-len(completed)}


def validate_gate(gate, run_id, exclude_kto=False):
    policy = NO_KTO_POLICY if exclude_kto else POLICY
    api.require(gate.get("runId") == run_id and gate.get("acceptancePolicy") == policy, "guard_gate_mismatch")
    if exclude_kto:
        api.require(gate.get("courseSyncDisabled") is True and gate.get("ktoAttempts") == 0,
                    "kto_exclusion_gate_missing")
    protection_key = "guardProtectionVerified" if exclude_kto else "guardPreflightPassed"
    api.require(all(gate.get(k) is True for k in
        (protection_key,"metricsStarted","actualSendingVerified","expiryRecoveryReady")), "guard_gate_missing")
    expires = dt.datetime.fromisoformat(gate["expiresAt"])
    api.require(expires.tzinfo is not None and
        (expires-dt.datetime.now(dt.timezone.utc)).total_seconds() >= 36*60, "guard_gate_expired")


def execute(fixture, run_id, output, gate, secret_prompt=getpass.getpass,
            client_factory=api.HttpClient, schedule=None, scheduler=execute_schedule, finish_hook=None,
            exclude_kto=False):
    api.require(bool(api.RUN_ID.fullmatch(run_id)), "run_id_contract")
    api.require(api.canonical_sha256(fixture) == FIXTURE_HASH, "fixture_hash_mismatch")
    api.validate_fixture(fixture, require_approved=True)
    validate_gate(gate, run_id, exclude_kto)
    if schedule is None:
        schedule = api.arrivals.build_schedule()
        api.require(api.arrivals.schedule_sha256(schedule) == SCHEDULE_HASH, "schedule_hash_mismatch")
    reference_schedule = schedule
    excluded = [r for r in schedule if exclude_kto and r["caseId"] in KTO_CASES]
    if exclude_kto:
        schedule = [r for r in schedule if r["caseId"] not in KTO_CASES]
    ev = Evidence(output, run_id, schedule, exclude_kto)
    save(output / "scope.json", {"referencePlanned":len(reference_schedule),
        "referenceScheduleSha256":api.arrivals.schedule_sha256(reference_schedule),
        "selectedScheduleSha256":api.arrivals.schedule_sha256(schedule),
        "selectedPlanned":len(schedule),"excludedCount":len(excluded),"excludedRequests":excluded})
    client = RecordingClient(client_factory(), ev)
    sessions = {}
    preflight = []
    writes = False
    cleaned = logged_out = False
    cpu, wall = time.process_time(), time.monotonic()
    try:
        sessions = api.login_sessions(client, secret_prompt)
        ev.stage_event("reserved_target_check")
        target = fixture["inputs"]["favoriteContestId"]
        for label in ("A", "B"):
            response = api.perform(api.request_spec("favorite_list", fixture, label), client, fixture, sessions)
            api.require(target not in api.favorite_ids(response.payload), "reserved_target_not_empty")
        ev.stage_event("preflight")
        writes = True
        if exclude_kto:
            for case in api.CASE_GROUPS:
                if case in KTO_CASES | {"favorite_add", "favorite_delete"}:
                    continue
                label = "A" if case in {"me", "favorite_list"} else None
                try:
                    response = api.perform(api.request_spec(case, fixture, label), client, fixture, sessions)
                    preflight.append({"caseId":case,"success":True,"durationMs":round(response.duration_ms,3)})
                except api.LoadError as error:
                    if error.code == "account_isolation":
                        raise
                    preflight.append({"caseId":case,"success":False,"error":error.code})
            api.verify_account_isolation(client, fixture, sessions)
        else:
            preflight = api.preflight(client, fixture, sessions)
        ev.stage_event("refresh_before_load")
        for label in ("A","B"):
            elapsed = api.refresh_session(client, sessions[label])
            ev.maintenance.append({"operation": "refresh_before_load", "account": label,
                "success": True, "durationMs": round(elapsed,3)})
        ev.stage_event("load")
        scheduler(client, fixture, sessions, schedule, ev)
    except BaseException as error:
        ev.cancel(code(error))
    finally:
        ev.load_finished_at = ev.load_finished_at or now()
        save(output.parent / "load-finished.json", {"runId":run_id, "at":ev.load_finished_at, "reason":ev.error})
        save(output / "partial-summary.json", {"runId":run_id,"at":now(),"error":ev.error,
            "counts":counts(list(ev.rows.values())),"requests":list(ev.rows.values()),
            "auxiliaryCounts":counts(ev.aux),"auxiliaryRequests":ev.aux})
        # 제어기는 이 신호 직후 가드를 끄고 재기동한다. 복귀 후 정리 요청을 실행한다.
        ev.stage_event("guard_finish")
        if finish_hook is not None:
            try:
                finish_hook()
            except BaseException as error:
                ev.cancel("guard_finish_failed")
        try:
            ev.stage_event("cleanup")
            cleaned = not writes or api.cleanup_favorites(client, fixture, sessions)
            if writes and cleaned:
                for label in ("A","B"):
                    response = api.perform(api.request_spec("favorite_list", fixture, label), client, fixture, sessions)
                    cleaned = cleaned and target not in api.favorite_ids(response.payload)
        except BaseException:
            cleaned = False
        ev.stage_event("logout")
        logout_results = []
        for session in sessions.values():
            try:
                logout_results.append(api.logout_session(client, session))
            except BaseException:
                logout_results.append(False)
            finally:
                session.access_token = session.refresh_token = ""
        logged_out = bool(sessions) and all(logout_results)
        sessions.clear()
        summary = api.summarize_run(run_id, FIXTURE_HASH, schedule, ev.results, ev.maintenance,
            sum(r["dispatchedAt"] is not None for r in ev.rows.values()), preflight, cleaned, logged_out,
            time.process_time()-cpu, time.monotonic()-wall)
        rows = list(ev.rows.values())
        for row in rows:
            if row["dispatchedAt"] is None and row["error"] is None:
                row["error"] = ev.error or "schedule_not_run"
        summary.update(acceptancePolicy=NO_KTO_POLICY if exclude_kto else POLICY, startedAt=ev.started_at,
            loadStartedAt=ev.load_started_at, loadFinishedAt=ev.load_finished_at, finishedAt=now(),
            error=ev.error, counts=counts(rows),
            phases=[{"phase":phase, **counts([r for r in rows if r["phase"]==phase])}
                    for phase in ("warmup","measurement")],
            requests=rows, auxiliaryRequests=ev.aux, auxiliaryCounts=counts(ev.aux),
            failedRequests=counts(rows)["failed"], notExecutedRequests=counts(rows)["notExecuted"],
            completedRequests=counts(rows)["completed"], inFlightRequests=counts(rows)["inFlight"],
            passed=summary["passed"] and ev.error is None,
            status="interrupted" if ev.error=="operator_interrupted" else
                ("completed" if not ev.error and counts(rows)["notExecuted"]==0 else "needs_attention"))
        for group in summary["groups"]:
            group.update(counts([r for r in rows if r["phase"]=="measurement" and r["group"]==group["group"]]))
        summary["requestFailureClasses"] = dict(api.Counter(r["error"] for r in rows
            if r["dispatchedAt"] and r["completedAt"] and not r["success"]))
        summary["notExecutedReasons"] = dict(api.Counter(r["error"] for r in rows if not r["dispatchedAt"]))
        summary["apiCases"] = []
        for case in api.CASE_GROUPS:
            selected = [r for r in rows if r["phase"]=="measurement" and r["caseId"]==case]
            durations = [r["durationMs"] for r in selected if r["durationMs"] is not None]
            summary["apiCases"].append({"caseId":case, **counts(selected),
                "p50Ms":api.percentile(durations,50) if durations else None,
                "p95Ms":api.percentile(durations,95) if durations else None,
                "maxMs":max(durations) if durations else None})
        if exclude_kto:
            # 전체 앱 합격과 선택한 부분 부하의 기계 점검을 분리한다.
            scoped_thresholds = [r for r in summary["groups"] + summary["heavyScenarios"] if r["planned"]]
            expected_preflight = set(api.CASE_GROUPS) - KTO_CASES - {"favorite_add","favorite_delete"}
            refreshes = api.Counter((r.get("operation"),r.get("account")) for r in ev.maintenance if r.get("success"))
            subset_passed = (ev.error is None and counts(rows)["successful"] == len(schedule)
                and summary["missedStarts"] == 0 and summary["lateDispatches"] == 0
                and all(r["passed"] for r in scoped_thresholds)
                and {r["caseId"] for r in preflight if r.get("success")} == expected_preflight
                and refreshes == api.Counter({(op,a):1 for op in ("refresh_before_load","refresh") for a in ("A","B")})
                and cleaned and logged_out)
            summary.update(referencePlannedRequests=len(reference_schedule),excludedRequests=excluded,
                excludedRequestCount=len(excluded),selectedPlannedRequests=len(schedule),
                referenceScheduleSha256=api.arrivals.schedule_sha256(reference_schedule),
                fullAppLoadPassed=False,subsetChecksPassed=subset_passed,passed=subset_passed,
                verdictScope="selected_requests_without_kto",capacityVerdict="requires_runtime_analysis",
                additionalExcludedRequestCount=sum(r["error"]=="excluded_after_guard_block" for r in rows))
        save(output / "api-load-summary.json", summary)
        ev.event("run_finished", counts=summary["counts"], error=ev.error,
                 cleanupSucceeded=cleaned, logoutSucceeded=logged_out)
        ev.events.close()
    return summary


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture",type=Path,required=True)
    parser.add_argument("--execute",action="store_true")
    parser.add_argument("--run-id")
    parser.add_argument("--output",type=Path)
    parser.add_argument("--guard-evidence",type=Path)
    parser.add_argument("--exclude-kto",action="store_true")
    args=parser.parse_args()
    fixture,digest=api.load_fixture(args.fixture,require_approved=True)
    api.require(digest==FIXTURE_HASH,"fixture_hash_mismatch")
    api.require(api.arrivals.schedule_sha256(api.arrivals.build_schedule())==SCHEDULE_HASH,"schedule_hash_mismatch")
    if not args.execute:
        data = {**api.dry_run_summary(fixture,digest),"acceptancePolicy":NO_KTO_POLICY if args.exclude_kto else POLICY}
        if args.exclude_kto:
            selected = [r for r in api.arrivals.build_schedule() if r["caseId"] not in KTO_CASES]
            data.update(selectedPlanned= len(selected),excludedCount=2100-len(selected),
                plannedRequests=len(selected),requestsPerMinute=53,referencePlannedRequests=2100,
                referenceScheduleSha256=SCHEDULE_HASH,scheduleSha256=api.arrivals.schedule_sha256(selected),
                selectedScheduleSha256=api.arrivals.schedule_sha256(selected))
        print(json.dumps(data,sort_keys=True))
        return 0
    api.require(bool(args.run_id and args.output and args.guard_evidence),"execute_arguments")
    warnings.simplefilter("error",getpass.GetPassWarning)
    result=execute(fixture,args.run_id,args.output,json.loads(args.guard_evidence.read_text(encoding="utf-8-sig")),exclude_kto=args.exclude_kto)
    print(json.dumps({k:result[k] for k in ("runId","status","counts","passed","error")},sort_keys=True))
    return 130 if result["error"]=="operator_interrupted" else (0 if result["passed"] else 1)


if __name__=="__main__":
    raise SystemExit(main())
