# nginx 로그 경로·보관 설정 확인 — 2026-09-07

## 2026-09-10 로그 개인정보 보완 연결

이 문서 아래의 09-07 결과는 당시 설정과 실서버 확인 기록으로 유지한다. 후속
`fix/log-privacy`는 URI·query·Host·User-Agent를 제외한 `runninggu_minimal` 접속 로그를
앱 host·bootstrap·기본 거부 server에 공통 적용하고, 요청 원문을 부분 제외할 수 없는 nginx
요청 처리 오류 로그를 저장하지 않도록 바꾼다. 백엔드도 예외 message·cause·stack 대신
`code`·예외 클래스·`traceId`만 기록한다.

정책과 자동 검사 범위는 [서버 로그 개인정보 보호 정책](../server-log-privacy-policy.md), 적용과
실서버 표식 검사는 [EC2 실행서 §13.1](../aws-ec2-staging-runbook.md#131-로그-개인정보-표식-검사)을
따른다. 이 절은 후속 구현 연결이며 staging이나 운영 서버 적용 증거가 아니다. 서버별 검사
결과가 생기면 대상 커밋·적용 시각·검사 행 수·금지 표식 검출 건수만 별도 증거 문서에 남긴다.

## 2026-09-08 리뷰 반영

#313 재리뷰에서 공개 처리방침의 목적·14일 보관·HTTP/HTTPS 적용 범위를 PRIVACY 1.2
초안과 맞췄다. #304의 저장 동선 좌표 설명을 보존하며 충돌을 해소했고, 해결된 보관 기간·
HTTP 접속 로그 미확인 표기는 제거했다. `$uri`가 정규화·rewrite 후 경로인 점도 실행서에 적었다.

**오류 로그에는 `runninggu_noqs`가 적용되지 않는다.** 요청 문맥에 원래 요청 주소가
기록되면 질의 문자열의 이메일·선택 장소 좌표가 남을 수 있다. 알 수 없는 Host의 전역
접속 로그도 제외 보장 대상이 아니다. 이 두 경로의 노출 방지는 공개 전 별도 조치·검증이
남으며, 본문 보완이 AGENTS 8장 준수나 공개 승인을 뜻하지 않는다.
이번 반영은 저장소 문서·공개 페이지 초안만 수정했다. 아래 09-07의 실서버 기록을
새로 검증한 것으로 계산하지 않으며 활성 약관 1.0과 서버 설정은 변경하지 않았다.


## 결정과 반영 범위

사용자는 2026-09-07 nginx 접속·오류 파일 로그 보관기간을 14일로 확정했다. 저장소에는
`backend/deploy/logrotate/nginx`를 추가하고 앱 host의 HTTP·HTTPS 접속 로그 경로·형식을
통일했다. 배포 실행서와 PRIVACY 1.2 초안도 같은 기간으로 맞췄다. 앱·서버의 활성 약관
버전은 바꾸지 않았다.

같은 날 EC2 `runninggu-staging-4g-2b`에도 적용했다. `/etc/logrotate.d/nginx` 교체,
timer·1회 실행, nginx 설정 검사, 파일 권한과 HTTPS API 응답을 확인했다. 저장소 설정과
운영 문서는 이 적용 기록과 같은 변경으로 반영한다.

## 현재 확인된 것

| 대상 | 저장소 설정 | 실서버 확인 |
|---|---|---|
| HTTPS 접속 로그 | `/var/log/nginx/runninggu-staging.access.log`, 쿼리 문자열을 제외하는 `runninggu_noqs` 형식 | 설정 검사·HTTPS API 스모크 성공 |
| HTTPS 오류 로그 | `/var/log/nginx/runninggu-staging.error.log`, `warn` 수준 | 설정 검사 성공 |
| HTTP 접속·오류 로그 | HTTPS와 같은 파일·형식을 명시 | 설정 파일에서 HTTP·HTTPS 각 1회 지정, `nginx -t` 성공 |
| 알 수 없는 Host의 접속 로그 | nginx 전역 `/var/log/nginx/access.log`를 상속 | 14일 회전 대상에 포함. 앱 API의 `runninggu_noqs` 보장 대상은 아님 |
| nginx 회전·보관 | `/var/log/nginx/*.log`, `daily`, `rotate 14`, `maxage 14`, 압축, `0640 www-data:adm` | timer 활성·1회 실행 성공, 기존 파일까지 권한 정리 |
| systemd journal | `SystemMaxUse=500M`, `SystemMaxFileSize=50M`, `SystemKeepFree=1G` | 실제 적용 여부 미확인. nginx 파일의 기간 설정과 별개 |

근거 파일은 `backend/deploy/nginx/staging-api.conf`, `backend/deploy/logrotate/nginx`,
`backend/deploy/systemd/journald-runninggu.conf`다. Ubuntu 패키지의 기존
`/etc/logrotate.d/nginx`와 별도 RunningGu 파일을 함께 두지 않고, 실행서대로 기존 파일을
저장소 정책으로 교체해야 같은 로그 경로의 중복 정의를 피할 수 있다.

## 실서버 적용 기록

AWS Systems Manager Run Command로 로그 본문·환경변수·시크릿을 출력하지 않고 설정과 파일
메타데이터만 확인했다.

- 적용 전 조회 `2ab6be68-a7a6-47c0-9799-a6e45a3b57ca`: 성공. Ubuntu 기본 nginx
  logrotate는 `daily`, `rotate 14`, 압축과 `0640 www-data:adm` 생성을 이미 사용했고
  `maxage`는 없었다. timer는 활성·직전 실행 성공이었다
- 첫 적용 `5c5784b4-2188-4ff1-9426-9ebaf4c91d3d`: 실패. SSM 기본 `/bin/sh`가
  Bash 전용 `pipefail`을 첫 줄에서 거부해 종료 코드 2가 났다. 파일 쓰기 전 실패라 서버
  설정 변경은 없었다
- 최종 적용 `cb74337d-c6a5-4838-aa97-8844cca7b4a6`: 성공. Bash로 명시 실행했고
  `nginx -t`, logrotate 전체 설정 디버그 검사, nginx reload, logrotate timer와 1회 service,
  외부 HTTPS 대회 API 스모크가 모두 성공했다
- 기존 일부 회전본이 `0644 root:root`였으므로 `/var/log/nginx/*.log*`의 현재 파일을 모두
  `0640 www-data:adm`으로 제한했다. 최종 출력에서 접속·오류 로그와 회전본 전체를 확인했다
- 변경 전 site와 logrotate 파일은 `/etc/runninggu/backups`에 UTC 시각을 붙여 보관했다

`rotate 14`는 회전본 개수의 상한이고, `maxage 14`는 14일이 지난 회전본을 매일 실행되는
회전 작업에서 삭제한다. 따라서 처리방침의 "14일 보관 후 정기 삭제"는 14×24시간이 되는
순간 삭제하는 TTL이 아니라 **14일 기준을 넘긴 회전본을 다음 일일 정리에서 삭제하는 운영
주기**를 뜻한다. 현재 기록 중인 파일과 회전 경계 때문에 다음 일일 실행까지 남을 수 있으므로
"최대 14×24시간"이라고는 표기하지 않는다.

14일이 실제로 지난 회전본의 자동 삭제는 시간 경과 뒤에만 관찰할 수 있다. 현재 완료 근거는
`maxage 14`를 포함한 설정 검사, 활성 timer, 성공한 1회 service다. nginx 설정과 logrotate
대상에서는 별도 사본 경로를 확인하지 못했다. CloudWatch Agent·rsyslog·AWS 계정 전체의 로그
전달 구성까지 감사한 것은 아니므로 별도 전달을 추가하거나 발견하면 같은 보유기간을 적용한다.

## 후속 점검에 사용할 조회 명령

아래는 **설정과 파일 정보만 읽는 명령**이다. 로그 본문·비밀번호·토큰·환경파일은 출력하지
않는다. 이후 배포나 14일 경과 뒤 같은 인스턴스에서 재검증할 때 사용한다.

```bash
sudo python3 - <<'PY'
from pathlib import Path
from datetime import datetime, timezone
import re
import subprocess

print('조회 시각:', datetime.now(timezone.utc).isoformat())

print('\n[nginx 디스크 설정: 로그·include 지시문]')
# 실행 중 프로세스에 재적용하지 않고 파일만 읽는다. 현재 프로세스에 적재된 설정과 다를 수 있다.
for path in sorted(Path('/etc/nginx').rglob('*')):
    if not path.is_file() or not (path.name == 'nginx.conf' or path.suffix == '.conf'
            or path.parent.name in ('sites-enabled', 'sites-available')):
        continue
    text = path.read_text(errors='replace')
    kept = []
    in_format = False
    for line in text.splitlines():
        value = line.strip()
        if value.startswith('#'):
            continue
        if re.match(r'^(access_log|error_log|log_format|include|listen|server_name)\s', value) or in_format:
            kept.append(value)
            in_format = (in_format or value.startswith('log_format ')) and ';' not in value
    if kept:
        print(str(path), '\n  ' + '\n  '.join(kept))

print('\n[nginx 로그 파일: 이름·크기·수정 시각, 본문 미조회]')
directory = Path('/var/log/nginx')
print('디렉터리 존재:', directory.exists())
for path in sorted(directory.glob('*')):
    if path.is_file():
        stat = path.stat()
        print(path.name, stat.st_size, datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat())

print('\n[logrotate 전역·nginx 관련 설정: 정책 지시문만]')
files = [Path('/etc/logrotate.conf'), *sorted(Path('/etc/logrotate.d').glob('*'))]
allowed = re.compile(r'^(daily|weekly|monthly|yearly|hourly|rotate|maxage|minage|size|maxsize|minsize|compress|nocompress|delaycompress|nodelaycompress|missingok|notifempty|ifempty|dateext|nodateext|dateformat|create|nocreate|copytruncate|nocopytruncate|su|include|olddir|noolddir|sharedscripts|nosharedscripts)\b')
for path in files:
    if not path.is_file():
        continue
    text = path.read_text(errors='replace')
    if path.name != 'logrotate.conf' and 'nginx' not in path.name and '/var/log/nginx' not in text:
        continue
    print(str(path))
    in_script = False
    for line in text.splitlines():
        value = line.strip()
        if re.match(r'^(prerotate|postrotate|firstaction|lastaction|preremove)\b', value):
            print('  ' + value.split()[0] + ' [본문 생략]')
            in_script = True
        elif value == 'endscript':
            in_script = False
        elif not in_script and (allowed.match(value) or value.startswith('/var/log/nginx')):
            print('  ' + value)

print('\n[logrotate 기록 중 nginx 경로만]')
state = Path('/var/lib/logrotate/status')
if state.exists():
    for line in state.read_text(errors='replace').splitlines():
        if '/var/log/nginx/' in line:
            print(line)

print('\n[자동 실행 상태]')
for unit, props in [('logrotate.timer', ['ActiveState', 'SubState', 'LastTriggerUSec', 'NextElapseUSecRealtime']),
                    ('logrotate.service', ['Result', 'ExecMainStatus', 'ExecMainExitTimestamp'])]:
    result = subprocess.run(['systemctl', 'show', unit, '--no-pager', *['--property=' + p for p in props]],
                            capture_output=True, text=True, timeout=10)
    print(unit, '조회 종료 코드:', result.returncode)
    print(result.stdout.strip())
cron = Path('/etc/cron.daily/logrotate')
print('cron.daily/logrotate 존재:', cron.exists())
if cron.exists():
    print('systemd 환경에서는 cron을 생략하는 분기 존재:', '/run/systemd/system' in cron.read_text(errors='replace'))
PY
```

이 출력으로 저장소의 14일 정책이 실제 경로·회전 주기·보관 개수·최근 자동 실행에 적용됐는지
확인한다. 추가 include 경로·별도 사본·외부 로그 전송이 발견되면 그 대상에도 14일 삭제 정책을
적용하거나 개인정보처리방침에 별도 보유기간을 적어야 한다. 이 명령은 실제 프로세스의 열린
파일 및 별도 백업·외부 전송까지 검증하지 않는다.
