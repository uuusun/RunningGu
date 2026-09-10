# 서버 로그 개인정보 보호 실서버 적용·검증 — 2026-09-10

## 대상과 판정 범위

| 환경 | EC2 | 검사 시점 상태 | 이번 판정 |
|---|---|---|---|
| staging | `i-07aa483968f4daddc` | nginx·인증서·백엔드 active | 과거 로그 정리 후 전체 표식 검사 통과 |
| production | `i-0c9f040b65d41d6c1` | HTTP bootstrap만 active, 인증서·백엔드 없음 | bootstrap 비노출 확인, TLS·백엔드 검사는 미완료 |

검사는 로그 원문과 시험 표식을 출력하지 않고 상태, 조회 행 수와 검출 건수만 남겼다.
백엔드·staging 설정 기준은 `90a4ad0`, production 설정 기준은 `607c944`이며 두 커밋은
`origin/fix/log-privacy`에 올라가 있다. staging 백엔드 JAR은 아직 이 브랜치 산출물로
교체하지 않았고, 커밋 기반 산출물이 배포될 때까지 systemd 방어 설정을 추가했다.

## 공통 서버 상태

SSM 명령 `e82dc3c6-be0b-4d02-b4e4-ad53cfbf7864`에서 두 서버 모두
`/etc/nginx/nginx.conf`가 `conf.d/*.conf`를 59행, `sites-enabled/*`를 60행에서 읽는 것을
확인했다. 따라서 `conf.d/runninggu-log-privacy.conf`의 `map`과 `log_format`이 server
설정보다 먼저 정의된다. nginx 1.24의 `nginx -t`도 두 서버에서 통과했다.

## staging

### 적용

- SSM `14f97116-5a07-4606-8892-0d2ff48fead5`: 기존 site 설정을
  `/var/backups/runninggu-log-privacy-20260910T084230Z`에 백업하고 최소 access log,
  요청 처리 error log 폐기 설정을 설치했다. `nginx -t`, reload, active 확인이 통과했다.
- named·기본 거부 access log는 모두 `0640 www-data:adm`이다. 기존 요청 error log도
  `0640 www-data:adm`으로 유지하되 새 설정은 더 기록하지 않는다.
- SSM `1087234c-5934-492e-a5aa-c447aab4fcb7`: 기존 JAR의 Throwable·Hibernate 제약
  상세 출력을 막는 `20-log-privacy.conf` systemd drop-in을 `0644 root:root`로 설치했다.
  재시작 뒤 backend active와 loopback 인증 조회 200을 확인했다. 저장소 코드가 배포되면
  이 임시 logger 차단은 안전한 `code`·예외 클래스·`traceId` 로그로 대체한다.

### 실제 요청 검사와 과거 로그 정리

SSM `5a17a7a8-8f58-4e27-93ce-368f89321b97` 결과는 다음과 같다.

- HTTP redirect 301, 이메일 조회 200, 좌표 검증 실패 400, 로그인 실패 401,
  알 수 없는 Host 연결 종료, 과대 헤더 400
- named·기본 거부 access log 모두 증가, 기존 요청 error log 크기 불변
- nginx 파일 22개·20,675행, 시험 시점 backend/nginx journal 14행 검사
- 시험 표식 검출 0건, 좌표 일반 패턴 0건, 비밀값 일반 패턴 0건
- 과거 이메일 일반 패턴 86건 때문에 최종 `passed=false`

원문 없이 출처만 집계한 SSM `c1cdeb66-4310-4090-9fc5-3998fba5149a` 결과, nginx 압축
로그 3개에 79건, 과거 backend journal에 7건이 있었다. SSM
`cde308a2-d575-4a8c-803d-69f617fa735a`로 journal 파일을 좁힌 결과 7건은 모두 현재 활성
파일이 아닌 archive 7개에 각각 1건씩 있었고, 현재 활성 journal은 0건이었다. nginx
journal은 최근 14일 78행에서 0건이었다.

승인된 정리 SSM `6cc30327-8aeb-46c1-a423-e340d0d7d14b`는 실행 직전에도 정확히
nginx 파일 3개·이메일 79건, journal archive 7개·이메일 7건인지 확인했다. nginx 압축
로그는 이메일 부분만 `[REDACTED_EMAIL]`로 치환해 나머지 진단 내용을 보존했고, 현재 활성
journal을 제외한 해당 archive 7개만 제거했다.

정리 직후 같은 실제 요청 검사를 다시 실행한 최종 결과는 다음과 같다.

- HTTP redirect 301, 이메일 조회 200, 좌표 검증 실패 400, 로그인 실패 401,
  알 수 없는 Host 연결 종료, 과대 헤더 400
- named·기본 거부 access log 증가, 기존 요청 error log 크기 불변
- nginx 파일 22개·20,683행, 최근 14일 backend/nginx journal 5,361행 검사
- 시험 표식 0건, 이메일·좌표·비밀값 일반 패턴 모두 0건, `passed=true`

SSM `43b6eeb6-68c9-4c83-b256-a837de9d3b1e`에서 `journalctl --verify`, `nginx -t`,
nginx·backend active 상태를 다시 확인했다. journal 파일은 9개, 사용량은 41.8MB였고
세 nginx 로그의 권한은 모두 `0640 www-data:adm`이었다. staging의 과거 로그 차단 사유는
해소됐다.

## production

### HTTP bootstrap 적용

- SSM `1d4f72c2-f7e1-48f3-94b6-db0446652b7a`: 기존 설정을
  `/var/backups/runninggu-log-privacy-20260910T085609Z`에 백업하고 named·기본 server에
  최소 access log와 요청 error log 폐기 설정을 설치했다.
- `nginx -t`, reload, active 확인이 통과했다. production·기본 거부 access log는 모두
  `0640 www-data:adm`이다.
- SSM `b134f786-7b72-4825-b0ec-2c969634ef10`: named Host 404, 알 수 없는 Host 404,
  허용 목록 밖 `TRACE` 405를 확인했다. 두 access log가 증가했고 nginx 파일 15행과 시험
  시점 journal을 검사한 결과 표식 0건, 최소 형식 불일치 0건, `passed=true`였다.

### 남은 검사

production은 검사 시점에 인증서와 backend service가 없어 HTTPS API·backend journal을
검사할 수 없었다. 운영 배포 작업이 최종 TLS 설정을 설치할 때 `runninggu_minimal`과
`error_log /dev/null crit`를 모든 server에 유지해야 한다. 인증서·백엔드가 active가 되면
다음 명령으로 전체 표식 검사를 다시 실행한다.

```bash
sudo python3 backend/deploy/validation/check-server-log-privacy.py \
  --host api.runninggu.store \
  --named-access-log runninggu-production.access.log \
  --legacy-error-log runninggu-production.error.log
```

production의 TLS·백엔드 검사가 완료되기 전에는 전체 로그 보안 항목을 완료로 표시하지
않는다.
