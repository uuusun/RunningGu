"""승인된 run의 가드 활성화·관측·해제를 수행하며 비밀값은 출력하지 않는다."""
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import fcntl
import hashlib
import importlib.util
import time
import urllib.request
import urllib.parse

RUN=sys.argv[2] if len(sys.argv)>2 else 'not-configured'
NO_KTO='--no-kto' in sys.argv
POLICY='app-capacity-v4-no-kto' if NO_KTO else 'app-capacity-v3'
REPO=Path('/opt/runninggu/repository')
OUT=Path('/opt/runninggu-validation')/RUN
TOOLS=Path(__file__).resolve().parent
STATE=Path('/run/runninggu-upstream-load-guard')
ENV=Path('/etc/runninggu/application.env')
KEYS={'RUNNINGGU_DEPLOYMENT_ENVIRONMENT':'staging','UPSTREAM_LOAD_GUARD_ENABLED':'false','UPSTREAM_LOAD_GUARD_RUN_ID':'','UPSTREAM_LOAD_GUARD_KTO_ENDPOINT_LIMIT':'0','UPSTREAM_LOAD_GUARD_KAKAO_TOTAL_LIMIT':'0','UPSTREAM_LOAD_GUARD_KAKAO_ENDPOINT_LIMIT':'0'}

def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()

def run(*args,check=True):
    p=subprocess.run(args,capture_output=True,text=True,timeout=600)
    if check and p.returncode:
        raise RuntimeError('command_failed')
    return p.stdout.strip()

def prop(key,unit='runninggu-backend.service'):
    return run('systemctl','show',unit,'--property='+key,'--value')

def write(name,data):
    temp=OUT/(name+'.tmp')
    temp.write_text(json.dumps(data),encoding='utf-8')
    os.replace(temp,OUT/name)

def set_guard(active):
    values=KEYS.copy()
    lines=ENV.read_text().splitlines()
    restore=OUT/'course-sync-original.json'
    if NO_KTO:
        if active:
            original=[x.partition('=')[2] for x in lines if x.partition('=')[0]=='COURSE_SYNC_ENABLED']
            assert len(original)==1 and original[0] in ('true','false') and not restore.exists()
            write('course-sync-original.json',{'value':original[0]})
            values['COURSE_SYNC_ENABLED']='false'
        elif restore.exists():
            values['COURSE_SYNC_ENABLED']=json.loads(restore.read_text())['value']
    if active:
        values.update(UPSTREAM_LOAD_GUARD_ENABLED='true',UPSTREAM_LOAD_GUARD_RUN_ID=RUN,UPSTREAM_LOAD_GUARD_KTO_ENDPOINT_LIMIT='100',UPSTREAM_LOAD_GUARD_KAKAO_TOTAL_LIMIT='5000',UPSTREAM_LOAD_GUARD_KAKAO_ENDPOINT_LIMIT='2000')
    assert all(sum(x.partition('=')[0]==k for x in lines)<=1 for k in values)
    lines=[x for x in lines if x.partition('=')[0] not in values]
    temp=ENV.with_suffix('.guard-tmp')
    with open(temp,'x') as f:
        f.write('\n'.join(lines+[k+'='+v for k,v in values.items()])+'\n')
    os.chmod(temp,0o600)
    os.replace(temp,ENV)
    return values

def ready():
    for _ in range(60):
        try:
            with urllib.request.urlopen('http://127.0.0.1:8080/api/contests?size=1',timeout=3) as r:
                if r.status==200:
                    return
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError('readiness_failed')

def process_matches(values):
    pid=prop('MainPID')
    env=dict(x.split(b'=',1) for x in Path('/proc/'+pid+'/environ').read_bytes().split(b'\0') if b'=' in x)
    return all(env.get(k.encode())==v.encode() for k,v in values.items())

def summary():
    state=json.loads((OUT/'guard-state.json').read_text())
    logs=run('journalctl','-u','runninggu-backend.service','--since',state['startedAt'],'--grep','runId='+re.escape(RUN)+r'\s+provider=','--output=cat','--no-pager',check=False)
    p=subprocess.run(['python3',str(REPO/'backend/deploy/validation/summarize-upstream-load-guard.py'),'--run-id='+RUN,'--require-endpoint','KAKAO_KEYWORD' if NO_KTO else 'KTO_SEARCH_FESTIVAL'],input=logs,capture_output=True,text=True)
    try:
        result=json.loads(p.stdout)
    except Exception:
        result={'passed':False,'summaryError':True}
    result['invocationUnchanged']=prop('InvocationID')==state['invocationId']
    result['nRestartsUnchanged']=prop('NRestarts')==state['nRestarts']
    result['ktoAttempts']=sum(r['attempts'] for r in result.get('providers',[]) if r['provider']=='KTO')
    return result

def require_no_kto_instrumentation(result):
    """공급자 오류는 기록하되 가드 계측·호출 제외 범위가 깨지면 시작하지 않는다."""
    assert result.get('guardLines',0)>0
    assert all(result.get(k)==0 for k in ('malformedLines','counterGaps','overLimit','ktoAttempts'))
    assert result.get('invocationUnchanged') is True and result.get('nRestartsUnchanged') is True
    assert result.get('requiredEndpointSeen') is True

def require_guard_safe(result):
    """시작 동기화 중에도 trip을 즉시 발견한다. 필수 endpoint의 첫 성공은 이후 확인한다."""
    assert all(result.get(k)==0 for k in ('unsafeEvents','non2xxResults','malformedLines','counterGaps','overLimit'))
    assert result.get('invocationUnchanged') is True and result.get('nRestartsUnchanged') is True

def finish():
    STATE.mkdir(mode=0o700,exist_ok=True)
    with (STATE/(RUN+'.lock')).open('w') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        original=OUT/'course-sync-original.json'
        course_restored=not NO_KTO or not original.exists() or process_matches(
            {'COURSE_SYNC_ENABLED':json.loads(original.read_text())['value']})
        if (OUT/'guard-disabled.json').exists() and process_matches(KEYS) and course_restored:
            return
        finish_locked()

def finish_locked():
    try:
        before=summary()
    except Exception:
        before={'passed':False,'summaryError':True}
    write('guard-summary.json',before)
    write('load-ended.json',{'runId':RUN,'at':now(),'reason':'guard_finish_requested'})
    values=set_guard(False)
    run('systemctl','restart','runninggu-backend.service')
    ready()
    result={'runId':RUN,'finishedAt':now(),'guardDisabled':process_matches(values),'readiness':True,'backendNRestarts':prop('NRestarts'),'guardSummary':before}
    if NO_KTO:
        result['courseSyncRestored']=process_matches({'COURSE_SYNC_ENABLED':values['COURSE_SYNC_ENABLED']}) if 'COURSE_SYNC_ENABLED' in values else True
    write('guard-disabled.json',result)
    run('systemctl','stop','runninggu-load-guard-expiry.timer',check=False)
    print(json.dumps(result))

def activate():
    OUT.mkdir(mode=0o770)
    run('chown','root:runninggu',str(OUT))
    STATE.mkdir(mode=0o700,exist_ok=True)
    statefile=STATE/(RUN+'.env')
    assert not statefile.exists()
    assert run('git','-c','safe.directory='+str(REPO),'-C',str(REPO),'rev-parse','HEAD')=='673a2f796052f4553113d5cd25608fb3821222ac'
    assert process_matches(KEYS)
    started=now()
    source=Path(__file__)
    run('systemd-run','--unit=runninggu-load-guard-expiry','--on-active=45m','/usr/bin/python3',str(source),'finish',RUN,*(['--no-kto'] if NO_KTO else []))
    try:
        values=set_guard(True)
        run('systemctl','restart','runninggu-backend.service')
        ready()
        assert process_matches(values)
        state={'runId':RUN,'acceptancePolicy':POLICY,'startedAt':started,'readyAt':now(),'invocationId':prop('InvocationID'),'nRestarts':prop('NRestarts')}
        if NO_KTO:
            assert process_matches({'COURSE_SYNC_ENABLED':'false'})
            state['courseSyncDisabled']=True
        write('guard-state.json',state)
        statefile.write_text('RUN_ID='+RUN+'\nGUARD_STARTED_AT='+started+'\nINVOCATION_ID='+state['invocationId']+'\nNRESTARTS='+state['nRestarts']+'\n')
        os.chmod(statefile,0o600)
        course=run('/bin/sh',str(REPO/'backend/deploy/common/read-required-env.sh'),str(ENV),'COURSE_SYNC_ENABLED')
        if course=='true':
            success=False
            for _ in range(60):
                require_guard_safe(summary())
                logs=run('journalctl','-u','runninggu-backend.service','--since',started,'--grep','두루누비 메타 동기화 완료|두루누비 메타 동기화 실패|두루누비 메타 동기화 중 내부 오류','--output=cat','--no-pager',check=False)
                assert not any(x in logs for x in ('동기화 실패','내부 오류'))
                if '동기화 완료. success=true' in logs:
                    success=True
                    break
                time.sleep(2)
            assert success
        probe=('http://127.0.0.1:8080/api/geocode?'+urllib.parse.urlencode({'query':'해운대해수욕장'})) if NO_KTO else 'http://127.0.0.1:8080/api/festivals?yearMonth=2026-09&size=1'
        try:
            with urllib.request.urlopen(probe,timeout=30) as r:
                assert r.status==200
        except Exception:
            if not NO_KTO: raise
            write('guard-probe-error.json',{'at':now(),'probeFailed':True})
        result=summary()
        if NO_KTO:
            require_no_kto_instrumentation(result)
            result['protectionVerified']=True
        else:
            assert result['passed'] and result['invocationUnchanged'] and result['nRestartsUnchanged']
        write('guard-preflight.json',result)
        print(json.dumps({'activated':True,'runId':RUN,'preflight':result}))
    except BaseException:
        finish()
        raise

def metrics():
    gate=summary()
    if NO_KTO: require_no_kto_instrumentation(gate)
    else: assert gate['passed'] and gate['invocationUnchanged'] and gate['nRestartsUnchanged']
    assert not (OUT/'metrics-started.json').exists()
    env=os.environ.copy()
    env.update(GIT_CONFIG_COUNT='1',GIT_CONFIG_KEY_0='safe.directory',GIT_CONFIG_VALUE_0=str(REPO))
    started=now()
    log=OUT/'runtime-metrics.log'
    command=['/bin/sh',str(TOOLS/'collect-runtime-metrics.sh'),'--duration-seconds','2400','--interval-seconds','5','--output',str(log)]
    p=subprocess.Popen(command,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,env=env)
    write('metrics-started.json',{'runId':RUN,'startedAt':started,'durationSeconds':2400,'intervalSeconds':5,'collectorPid':p.pid,'acceptancePolicy':POLICY,'guardAtStart':gate})
    stopping=threading.Event()
    jobs=[]
    began=time.monotonic()
    def jobs_worker():
        for offset,kind in ((600,'wal'),(1000,'backup'),(1200,'wal'),(1800,'wal')):
            if stopping.wait(max(0,offset-(time.monotonic()-began))):
                return
            if (OUT/'load-ended.json').exists():
                return
            unit='runninggu-postgres-'+('wal-archive-check' if kind=='wal' else 'backup')+'.service'
            before=prop('InvocationID',unit)
            item={'kind':kind,'startedAt':now(),'offsetSeconds':offset}
            try:
                run('systemctl','start',unit)
                item.update({k:prop(k,unit) for k in ['Result','ExecMainStatus','InvocationID']})
                item['newInvocation']=before!=item['InvocationID']
                item['passed']=item['Result']=='success' and item['ExecMainStatus']=='0' and item['newInvocation']
            except Exception:
                item.update(passed=False,error='maintenance_failed')
            item['finishedAt']=now()
            jobs.append(item)
            write('backup-wal-results.json',jobs)
    worker=threading.Thread(target=jobs_worker)
    worker.start()
    try:
        tick=0
        while p.poll() is None:
            if tick%15==0:
                active=not (OUT/'load-ended.json').exists()
                current=summary() if active else None
                mem={a[0].rstrip(':'):int(a[1]) for line in Path('/proc/meminfo').read_text().splitlines()
                     if (a:=line.split())[0] in ['MemTotal:','MemAvailable:','SwapTotal:','SwapFree:']}
                alert=None
                if active and any(current.get(k)!=0 for k in ['unsafeEvents','non2xxResults','malformedLines','overLimit']):
                    alert='guard_failed'
                if NO_KTO and active:
                    if current.get('unsafeEvents',0)>0:
                        write('upstream-blocked.json',{'at':now(),'reason':'guard_blocked','runId':RUN})
                    # 가드는 그대로 차단하며 PC는 내부 요청과 관측을 이어간다.
                    if alert=='guard_failed' and all(current.get(k)==0 for k in ('malformedLines','overLimit','counterGaps')):
                        alert=None
                    if current.get('ktoAttempts',0)!=0:
                        alert='unexpected_kto_attempt'
                if active and (not current['invocationUnchanged'] or not current['nRestartsUnchanged']):
                    alert='unexpected_backend_restart'
                if active and prop('ActiveState')!='active':
                    alert='backend_not_active'
                if prop('ActiveState','runninggu-graphhopper.service')!='active':
                    alert='graphhopper_not_active'
                # GC/메모리/swap 값은 중단식에 사용하지 않는다 (§5.3).
                write('metrics-progress.json',{'at':now(),'elapsedSeconds':round(time.monotonic()-began),
                    'memory':mem,'maintenanceCompleted':len(jobs),'guard':current,'safetyAlert':alert})
                if alert:
                    write('safety-alert.json',{'at':now(),'reason':alert})
                    # PC가 신규 송신을 중단할 때까지 가드는 켠 채 유지한다.
            if time.monotonic()-began>2460:
                raise RuntimeError('collector_deadline')
            time.sleep(1)
            tick+=1
        code=p.wait()
        write('metrics-completed.json',{'runId':RUN,'startedAt':started,'finishedAt':now(),'collectorExitCode':code})
    except BaseException:
        p.terminate()
        try: p.wait(timeout=30)
        except subprocess.TimeoutExpired: p.kill(); p.wait()
        write('metrics-failed.json',{'runId':RUN,'at':now(),'error':'collector_failed'})
        raise
    finally:
        stopping.set()
        worker.join(timeout=610)
        finish()

def maintenance():
    compose=['docker','compose','--env-file','/etc/runninggu/compose.env','-f',str(REPO/'backend/compose.yaml'),'-f',str(REPO/'backend/compose.ec2.yaml'),'exec','-T','--user','postgres','postgres']
    begin=now()
    check=subprocess.run(compose+['pgbackrest','--stanza=runninggu','check'],capture_output=True,text=True,timeout=180)
    unit='runninggu-postgres-wal-archive-check.service'
    before=prop('InvocationID',unit)
    run('systemctl','start',unit)
    wal={k:prop(k,unit) for k in ['Result','ExecMainStatus','InvocationID']}
    wal['newInvocation']=before!=wal['InvocationID']
    info=json.loads(run(*(compose+['pgbackrest','--stanza=runninggu','info','--output=json'])))[0]
    latest=info['backup'][-1]
    result={'runId':RUN,'startedAt':begin,'finishedAt':now(),'pgBackRestCheckExitCode':check.returncode,'wal':wal,'latestBackup':{k:latest.get(k) for k in ['label','type','timestamp','error']},'repositoryStatus':info['status']}
    result['passed']=check.returncode==0 and wal['Result']=='success' and wal['ExecMainStatus']=='0' and wal['newInvocation'] and info['status']['code']==0
    write('maintenance.json',result)
    print(json.dumps(result))

def status():
    result={'runId':RUN,'at':now()}
    for name in ('guard-state','guard-preflight','metrics-started','metrics-progress','metrics-completed','metrics-failed','guard-disabled','backup-wal-results','safety-alert','upstream-blocked','load-ended'):
        path=OUT/(name+'.json')
        if path.exists(): result[name]=json.loads(path.read_text())
    if (OUT/'guard-state.json').exists() and not (OUT/'guard-disabled.json').exists(): result['currentGuard']=summary()
    print(json.dumps(result))

def baseline():
    """비밀 환경값은 출력하지 않고 승인된 설정과 실제 프로세스만 대조한다."""
    result={'at':now(),'runId':RUN,'acceptancePolicy':POLICY}
    metadata='http://169.254.169.254/latest/'
    request=urllib.request.Request(metadata+'api/token',method='PUT',headers={'X-aws-ec2-metadata-token-ttl-seconds':'60'})
    with urllib.request.urlopen(request,timeout=3) as response: token=response.read().decode()
    request=urllib.request.Request(metadata+'dynamic/instance-identity/document',headers={'X-aws-ec2-metadata-token':token})
    with urllib.request.urlopen(request,timeout=3) as response: identity=json.load(response)
    result['instance']={k:identity[k] for k in ['instanceId','instanceType','availabilityZone','region']}
    token=''
    assert result['instance']=={'instanceId':'i-07aa483968f4daddc','instanceType':'c7i-flex.large','availabilityZone':'ap-northeast-2b','region':'ap-northeast-2'}
    result['backendSha']=run('git','-c','safe.directory='+str(REPO),'-C',str(REPO),'rev-parse','HEAD')
    result['release']=Path('/opt/runninggu/current').resolve().name
    assert result['backendSha']==result['release']=='673a2f796052f4553113d5cd25608fb3821222ac'
    result['graph']=Path('/opt/runninggu-data/graph/current').resolve().name
    assert result['graph']=='gh11-korea-20260901-2ff6731b181a-2b8515dd29fc'
    result['backend']={k:prop(k) for k in ['ActiveState','SubState','NRestarts','InvocationID','MemoryHigh','MemoryMax','MemoryCurrent']}
    assert result['backend']['MemoryHigh']=='671088640' and result['backend']['MemoryMax']=='805306368'
    args=Path('/proc/'+prop('MainPID')+'/cmdline').read_bytes().split(bytes([0]))
    result['backendJvm']=[a.decode() for a in args if a.startswith((b'-Xms',b'-Xmx',b'-Xlog'))]
    assert '-Xms256m' in result['backendJvm'] and '-Xmx512m' in result['backendJvm']
    result['containers']=[]
    for name in ['backend-graphhopper-1','backend-postgres-1']:
        data=json.loads(run('docker','inspect',name))[0]
        item={'name':name,'id':data['Id'],'image':data['Image'],'restartCount':data['RestartCount'],
              'state':{k:data['State'][k] for k in ['Status','Running','OOMKilled','StartedAt']},
              'limits':{k:data['HostConfig'][k] for k in ['Memory','MemorySwap','MemoryReservation']}}
        if 'graphhopper' in name:
            opts=' '.join(data['Config'].get('Env',[]))+' '+' '.join(data['Config'].get('Cmd',[]))
            item['jvm']=re.findall(r'-Xm[sx][^\s]+',opts)
            assert '-Xms512m' in item['jvm'] and '-Xmx2g' in item['jvm']
            assert item['limits']=={'Memory':2684354560,'MemorySwap':2684354560,'MemoryReservation':2147483648}
        assert item['state']['Running'] and not item['state']['OOMKilled']
        result['containers'].append(item)
    result['swappiness']=int(Path('/proc/sys/vm/swappiness').read_text())
    result['mem']={a[0].rstrip(':'):int(a[1]) for line in Path('/proc/meminfo').read_text().splitlines()
        if (a:=line.split())[0] in ['MemTotal:','MemAvailable:','SwapTotal:','SwapFree:']}
    assert result['swappiness']==10 and 4194000<=result['mem']['SwapTotal']<=4194304
    result['guardOff']=process_matches(KEYS)
    assert result['guardOff']
    result['bootId']=Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    result['graphhopperInvocation']=prop('InvocationID','runninggu-graphhopper.service')
    result['nginx']=prop('ActiveState','nginx.service')
    ready()
    result['readiness']=True
    result['passed']=True
    (TOOLS/('baseline-'+RUN+'.json')).write_text(json.dumps(result))
    print(json.dumps(result))

if __name__=='__main__':
    try:
        action=sys.argv[1]
        if action=='activate': activate()
        elif action=='metrics': metrics()
        elif action=='maintenance': maintenance()
        elif action=='finish': finish()
        elif action=='status': status()
        elif action=='baseline': baseline()
        else: raise RuntimeError('invalid_action')
    except BaseException:
        if len(sys.argv)>1 and sys.argv[1] in ('activate','metrics'):
            try: finish()
            except BaseException: pass
        print(json.dumps({'passed':False,'error':'operation_failed','action':sys.argv[1] if len(sys.argv)>1 else 'missing'}))
        raise SystemExit(1)
