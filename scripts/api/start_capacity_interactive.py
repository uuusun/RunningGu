#!/usr/bin/env python3
"""가드 확인 전에는 송신하지 않고 전용 콘솔에서 두 테스트 계정을 숨김 입력한다."""
import argparse
import ctypes
import getpass
import json
from pathlib import Path
import sys
import time
import warnings

import run_api_capacity as runner


def wait_file(path,deadline,cancel):
    while not path.exists():
        if cancel.exists():
            raise runner.api.LoadError("control_gate_cancelled")
        if time.monotonic()>deadline:
            raise runner.api.LoadError("control_gate_timeout")
        time.sleep(0.25)
    return json.loads(path.read_text(encoding="utf-8-sig"))


def prestart_summary(run_id,error,exclude_kto=False):
    """로그인 전 gate 중단도 미실행으로 명시한다. 지연·성능 수치는 만들지 않는다."""
    schedule=runner.api.arrivals.build_schedule()
    if exclude_kto:
        schedule=[r for r in schedule if r['caseId'] not in runner.KTO_CASES]
    def pending(size):
        return dict(planned=size,dispatched=0,completed=0,successful=0,failed=0,notExecuted=size,inFlight=0)
    return {"runId":run_id,"acceptancePolicy":runner.NO_KTO_POLICY if exclude_kto else runner.POLICY,"status":"not_started",
        "referencePlannedRequests":2100,"excludedRequestCount":2100-len(schedule),
        "loadExecuted":False,"error":error,"counts":pending(len(schedule)),
        "phases":[{"phase":p,**pending(sum(r['phase']==p for r in schedule))} for p in ('warmup','measurement')],
        "auxiliaryCounts":pending(0),"inputsDiscarded":True,"at":runner.now(),
        "requestSetSha256":runner.FIXTURE_HASH,"scheduleSha256":runner.api.arrivals.schedule_sha256(schedule),
        "missedStarts":None,"lateDispatches":None,"maxDispatchDelayMs":None,"apiGroups":[]}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id",required=True)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--exclude-kto",action="store_true")
    args=parser.parse_args()
    runner.api.require(bool(runner.api.RUN_ID.fullmatch(args.run_id)),"run_id_contract")
    args.output.mkdir(parents=True,exist_ok=False)
    warnings.simplefilter("error",getpass.GetPassWarning)
    if sys.platform=="win32":
        ctypes.windll.kernel32.SetConsoleTitleW("RunningGu 4GiB - hidden app account input")
    print("Enter RunningGu app accounts A/B. These are NOT AWS credentials.",flush=True)
    print("All four inputs are hidden; values stay in memory only.",flush=True)
    secrets=[]
    try:
        if not sys.stdin.isatty():
            raise runner.api.LoadError("interactive_console_required")
        for label in ("A email","A RunningGu password","B email","B RunningGu password"):
            value=getpass.getpass(label+" (hidden): ")
            if not value:
                raise runner.api.LoadError("empty_input")
            secrets.append(value)
        value=""
        runner.save(args.output/"input-ready.json",{"inputReady":True,"runId":args.run_id,"at":runner.now()})
        print("Input ready. Waiting for verified guard/metrics gate. No login sent yet.",flush=True)
        gate=wait_file(args.output/"start-authorized.json",time.monotonic()+1800,args.output/"cancel")
        fixture,_=runner.api.load_fixture(Path(__file__).parent/"fixtures/staging-api-load-v1.approved.json")
        def finish():
            print("Load ended. Waiting for guard OFF and service readiness before cleanup.",flush=True)
            state=wait_file(args.output/"guard-off.json",time.monotonic()+300,args.output/"cleanup-cancel")
            runner.api.require(state.get("runId")==args.run_id and state.get("guardDisabled") is True
                and state.get("readiness") is True,"guard_off_not_verified")
        result=runner.execute(fixture,args.run_id,args.output/"load",gate,
            secret_prompt=lambda _:secrets.pop(0),finish_hook=finish,exclude_kto=args.exclude_kto)
        secrets.clear()
        runner.save(args.output/"completed.json",{"runId":args.run_id,"status":result["status"],
            "passed":result["passed"],"counts":result["counts"],"at":runner.now(),"inputsDiscarded":True})
        print("Finished. Credentials discarded and safe evidence saved.",flush=True)
        return 0 if result["passed"] else 1
    except BaseException as error:
        secrets.clear()
        if not (args.output/"load").exists():
            runner.save(args.output/"pre-start-summary.json",prestart_summary(args.run_id,runner.code(error),args.exclude_kto))
        runner.save(args.output/"launcher-failed.json",{"runId":args.run_id,"at":runner.now(),
            "error":runner.code(error),"inputsDiscarded":True})
        print("Stopped. Credentials discarded. No secret content was written.",flush=True)
        return 1
    finally:
        secrets.clear()


if __name__=="__main__":
    raise SystemExit(main())
