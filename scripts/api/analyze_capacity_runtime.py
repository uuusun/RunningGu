#!/usr/bin/env python3
"""EC2 자원·GC를 동일 UTC 시계열로 정리한다. 횟수/메모리 값만으로 용량을 판정하지 않는다."""
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys


def gc_event(message,at,service,invocation=None):
    pause=re.search(r'GC\((\d+)\) (Pause .+?) ([0-9.]+)ms$',message.strip())
    if not pause:
        return None
    heap=re.search(r'(\d+)([KMG])->(\d+)([KMG])\((\d+)([KMG])\)',pause[2])
    factor={"K":1/1024,"M":1,"G":1024}
    heaps=[int(heap[i])*factor[heap[i+1]] for i in (1,3,5)] if heap else None
    reason=pause[2][:heap.start()].strip() if heap else pause[2]
    return {"at":at,"service":service,"invocationId":invocation,"gcId":int(pause[1]),
            "kind":reason,"full":"Pause Full" in reason,"pauseMs":float(pause[3]),
            "heapMiB":heaps}


def parse_metrics(raw):
    samples=[]
    current=None
    for line in raw.splitlines():
        if line.startswith("sample "):
            fields=dict(re.findall(r'([A-Za-z_]+)=([^ ]+)',line))
            current={"sample":fields,"systemd":[],"containers":[]}
        elif current is not None and line.startswith("systemd "):
            current["systemd"].append(dict(re.findall(r'([A-Za-z_]+)=([^ ]+)',line)))
        elif current is not None and line.startswith("container "):
            current["containers"].append(dict(re.findall(r'([A-Za-z_]+)=([^ ]+)',line)))
        elif current is not None and line.startswith("sample_end "):
            current["complete"]=line.partition("sequence=")[2]==current["sample"]["sequence"]
            samples.append(current)
            current=None
    return samples


def cpu_deltas(samples):
    rows=[]
    keys=("cpu_user","cpu_nice","cpu_system","cpu_idle","cpu_iowait","cpu_irq","cpu_softirq","cpu_steal")
    for left,right in zip(samples,samples[1:]):
        a,b=left["sample"],right["sample"]
        if not all(k in a and k in b for k in keys):
            continue
        delta={k:int(b[k])-int(a[k]) for k in keys}
        total=sum(delta.values())
        rows.append({"at":b["at"],"busyPercent":round(100*(total-delta["cpu_idle"]-delta["cpu_iowait"])/total,3) if total>0 else None,
            "iowaitPercent":round(100*delta["cpu_iowait"]/total,3) if total>0 else None,
            "counterReset":any(v<0 for v in delta.values())})
    return rows


def utc(value):
    return dt.datetime.fromisoformat(value.replace("Z","+00:00"))


def main():
    run_id=sys.argv[1]
    tools=Path(__file__).resolve().parent
    output=tools.parent/run_id
    # 도구는 /opt/runninggu-validation/<도구폴더>, run은 그와 나란히 저장한다.
    def read(name): return json.loads((output/(name+".json")).read_text())
    def command(*args):
        p=subprocess.run(args,capture_output=True,text=True,timeout=120)
        if p.returncode: raise RuntimeError("audit_read_failed")
        return p.stdout+p.stderr
    guard=read("guard-state")
    disabled=read("guard-disabled")
    started=read("metrics-started")
    ended=read("load-ended")
    raw=(output/"runtime-metrics.log").read_text()
    samples=parse_metrics(raw)
    logs=command("journalctl","-u","runninggu-backend.service","--since",guard["startedAt"],"--output=json","--no-pager")
    gc=[]
    jvm_oom=0
    for line in logs.splitlines():
        obj=json.loads(line)
        message=str(obj.get("MESSAGE",""))
        jvm_oom+=int("OutOfMemoryError" in message)
        at=dt.datetime.fromtimestamp(int(obj["__REALTIME_TIMESTAMP"])/1e6,dt.timezone.utc).isoformat()
        event=gc_event(message,at,"backend",obj.get("_SYSTEMD_INVOCATION_ID"))
        if event: gc.append(event)
    gh=command("docker","logs","--timestamps","--since",guard["startedAt"],"backend-graphhopper-1")
    for line in gh.splitlines():
        prefix,_,message=line.partition(" ")
        jvm_oom+=int("OutOfMemoryError" in message)
        event=gc_event(message,prefix,"graphhopper")
        if event: gc.append(event)
    for event in gc:
        t=utc(event["at"])
        event["window"]=("before_readiness" if t<utc(guard["readyAt"]) else
            "normal_observation" if t<utc(ended["at"]) else "planned_guard_cleanup")
    gc.sort(key=lambda r:r["at"])
    kernel=command("journalctl","-k","--since",guard["startedAt"],"--output=cat","--no-pager")
    swap=[]
    for a,b in zip(samples,samples[1:]):
        left,right=a["sample"],b["sample"]
        delta={k:int(right[k])-int(left[k]) for k in ("pswpin","pswpout")}
        if any(delta.values()):
            swap.append({"at":right["at"],**delta})
    normal=[r for r in samples if utc(guard["readyAt"])<=utc(r["sample"]["at"])<utc(ended["at"])]
    memory=[float(r["sample"]["mem_available_percent"]) for r in samples]
    full=[r for r in gc if r["full"] and r["window"]=="normal_observation"]
    cpu=cpu_deltas(samples)
    result={"runId":run_id,"acceptancePolicy":guard.get("acceptancePolicy","app-capacity-v3"),
        "capacityVerdict":"requires_api_correlation","guard":disabled,
        "collection":read("metrics-completed"),"baseline":json.loads((tools/("baseline-"+run_id+".json")).read_text()),
        "backupWal":read("backup-wal-results") if (output/"backup-wal-results.json").exists() else [],
        "rawMetricsSha256":hashlib.sha256(raw.encode()).hexdigest(),
        "sampleCount":len(samples),"expectedSamples":480,
        "completeSequences":[r["sample"]["sequence"] for r in samples]==[str(i) for i in range(480)] and all(r["complete"] for r in samples),
        "endMarkerPresent":"ended_at=" in raw,
        "maxSampleGapSeconds":max((int(b["sample"]["epoch"])-int(a["sample"]["epoch"]) for a,b in zip(samples,samples[1:])),default=None),
        "minimumMemAvailablePercent":min(memory) if memory else None,
        "firstMemAvailablePercent":memory[0] if memory else None,
        "lastMemAvailablePercent":memory[-1] if memory else None,
        "swapFirstKiB":int(samples[0]["sample"]["swap_used_kib"]) if samples else None,
        "swapLastKiB":int(samples[-1]["sample"]["swap_used_kib"]) if samples else None,
        "swapPeakKiB":max((int(r["sample"]["swap_used_kib"]) for r in samples),default=None),
        "swapIntervals":swap,"cpu":cpu,"samples":samples,"gcEvents":gc,
        "normalFullGcCount":len(full),"normalFullGcPauseTotalMs":round(sum(r["pauseMs"] for r in full),3),
        "jvmOomIndicatorLines":jvm_oom,
        "kernelOomIndicatorLines":len(re.findall(r"Out of memory|oom-kill|Killed process",kernel)),
        "normalUnhealthySystemdSamples":sum(s.get("ActiveState")!="active" or s.get("SubState")!="running" for r in normal for s in r["systemd"]),
        "normalOomContainerSamples":sum(c.get("oom_killed")=="true" for r in normal for c in r["containers"]),
        "normalUnhealthyContainers":sum(c.get("status")!="running" for r in normal for c in r["containers"])}
    (output/"runtime-evidence.json").write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps({k:v for k,v in result.items() if k not in {"samples","cpu","gcEvents","baseline","guard"}}))
    print(json.dumps({"fullGcEvents":full,"maxHostCpuPercent":max((r["busyPercent"] for r in cpu if r["busyPercent"] is not None),default=None)}))


if __name__=="__main__":
    main()
