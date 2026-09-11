# 서버 로그 개인정보 보호 실서버 적용·검증 — 2026-09-10~11

## 대상과 판정 범위

| 환경 | 검사 시점 상태 | 이번 판정 |
|---|---|---|
| staging | `a2c7700`·TLS·nginx·백엔드·GraphHopper active | 임시 logger 차단 제거 후 전체 검사 통과 |
| production | `a2c7700`·TLS·nginx·백엔드·GraphHopper active | 과거 archive 1건 정리 후 전체 검사 통과 |

검사는 로그 원문과 시험 표식을 출력하지 않고 상태, 조회 행 수와 검출 건수만 남겼다.
초기 적용은 `origin/fix/log-privacy`의 `90a4ad0`·`607c944`를 사용했다. 구현은 PR #332의
squash commit `801fa7d`로 `develop`에 병합됐고, 두 서버의 최종 실행 JAR은 후속
`develop` commit `a2c7700`의 CI artifact다. `runninggu-server.jar` SHA-256은 두 환경 모두
`2c6e74b358bc9e589c4667c12427ab771658cd46ef2e0929e30a3f28b9ea9c49`다.

## 공통 서버 상태

SSM 공통 점검에서 두 서버 모두
`/etc/nginx/nginx.conf`가 `conf.d/*.conf`를 59행, `sites-enabled/*`를 60행에서 읽는 것을
확인했다. 따라서 `conf.d/runninggu-log-privacy.conf`의 `map`과 `log_format`이 server
설정보다 먼저 정의된다. nginx 1.24의 `nginx -t`도 두 서버에서 통과했다.

## staging

### 적용

- 초기 SSM 적용에서 기존 site 설정을
  `/var/backups/runninggu-log-privacy-20260910T084230Z`에 백업하고 최소 access log,
  요청 처리 error log 폐기 설정을 설치했다. `nginx -t`, reload, active 확인이 통과했다.
- named·기본 거부 access log는 모두 `0640 www-data:adm`이다. 기존 요청 error log도
  `0640 www-data:adm`으로 유지하되 새 설정은 더 기록하지 않는다.
- 후속 SSM 적용에서 기존 JAR의 Throwable·Hibernate 제약
  상세 출력을 막는 `20-log-privacy.conf` systemd drop-in을 `0644 root:root`로 설치했다.
  재시작 뒤 backend active와 loopback 인증 조회 200을 확인했다. 저장소 코드가 배포되면
  이 임시 logger 차단은 안전한 `code`·예외 클래스·`traceId` 로그로 대체한다.

### 실제 요청 검사와 과거 로그 정리

첫 실제 요청 검사 결과는 다음과 같다.

- HTTP redirect 301, 이메일 조회 200, 좌표 검증 실패 400, 로그인 실패 401,
  알 수 없는 Host 연결 종료, 과대 헤더 400
- named·기본 거부 access log 모두 증가, 기존 요청 error log 크기 불변
- nginx 파일 22개·20,675행, 시험 시점 backend/nginx journal 14행 검사
- 시험 표식 검출 0건, 좌표 일반 패턴 0건, 비밀값 일반 패턴 0건
- 과거 이메일 일반 패턴 86건 때문에 최종 `passed=false`

원문 없이 출처만 집계한 결과 nginx 압축 로그 3개에 79건, 과거 backend journal에 7건이
있었다. journal 파일을 좁힌 결과 7건은 모두 현재 활성
파일이 아닌 archive 7개에 각각 1건씩 있었고, 현재 활성 journal은 0건이었다. nginx
journal은 최근 14일 78행에서 0건이었다.

승인된 정리 명령은 실행 직전에도 정확히
nginx 파일 3개·이메일 79건, journal archive 7개·이메일 7건인지 확인했다. nginx 압축
로그는 이메일 부분만 `[REDACTED_EMAIL]`로 치환해 나머지 진단 내용을 보존했고, 현재 활성
journal을 제외한 해당 archive 7개만 제거했다.

정리 직후 같은 실제 요청 검사를 다시 실행한 최종 결과는 다음과 같다.

- HTTP redirect 301, 이메일 조회 200, 좌표 검증 실패 400, 로그인 실패 401,
  알 수 없는 Host 연결 종료, 과대 헤더 400
- named·기본 거부 access log 증가, 기존 요청 error log 크기 불변
- nginx 파일 22개·20,683행, 최근 14일 backend/nginx journal 5,361행 검사
- 시험 표식 0건, 이메일·좌표·비밀값 일반 패턴 모두 0건, `passed=true`

후속 상태 점검에서 `journalctl --verify`, `nginx -t`,
nginx·backend active 상태를 다시 확인했다. journal 파일은 9개, 사용량은 41.8MB였고
세 nginx 로그의 권한은 모두 `0640 www-data:adm`이었다. staging의 과거 로그 차단 사유는
해소됐다.

### 병합 release 배포와 임시 설정 제거 — 2026-09-11

- GitHub Actions run `34487652241`의
  `runninggu-backend-a2c7700671f6c9ca60c67687b9e7863cfeb144a6` artifact를 사용했다.
  로컬에서 manifest의 `git_commit=a2c7700671f6c9ca60c67687b9e7863cfeb144a6`, workflow
  run ID와 `SHA256SUMS` 4개를
  확인했다. 전달 ZIP은 119,541,177 bytes, SHA-256
  `18de0a44333025ef2bde981b562fda09564c5e7549eaa0d18d9b6e55ac91a765`이며 private S3의
  `backend/staging/<commit>/log-privacy-final-20260911.zip` 객체로 AES256 저장했다.
- 배포 직전 PostgreSQL backup을 새로 실행해 `Result=success`, exit 0을 확인했다.
- 서버 저장소의 `backend/postgres/Dockerfile` 로컬 변경은 `a2c7700`에 병합된 내용과
  동일했다. 원본 저장소와 patch를 보존하고 깨끗한 exact commit clone으로 활성 저장소를
  교체했다. artifact와 설치된 release에서 `SHA256SUMS`를 각각 다시 통과했다.
- 첫 전환은 `runninggu-contest-import.service` 미설치와 새 Compose 계약의
  `PGBACKREST_REPO1_PATH` 누락을 발견해 이전 JAR로 롤백했다. 이때 backend 재기동도
  Compose 사전 검사에서 막혀 일시적으로 HTTPS 502였고, 스테이징 확정값
  `/runninggu/staging`을 환경 파일 백업 후 추가해 기존 API를 HTTPS 200으로 복구했다.
  저장소 정식 importer unit을 `0644 root:root`로 설치하고 load·unit 검증을 통과시켰다.
- `a2c7700`으로 재전환한 결과 Importer는
  `Result=success`, exit 0, backend는 active/running, 외부 HTTPS는 200이었다. 기존
  `20-log-privacy.conf`는 `/var/backups/runninggu-staging-a2c7700-20260911`로 이동했고
  최종 `DropInPaths`에는 `memory.conf`만 남았다.

최종 SSM 검사는 다음을 확인했다.

- HTTP redirect 301, 이메일 조회 200, 좌표 검증 실패 400, 로그인 실패 401,
  알 수 없는 Host 연결 종료, 과대 헤더 400
- named·기본 거부 access log 증가, 기존 요청 error log 크기 불변
- nginx 파일 22개·20,952행, 최근 14일 backend/nginx journal 6,028행 검사
- 시험 표식 0건, 이메일·좌표·비밀값 일반 패턴 모두 0건, `passed=true`
- nginx·backend·GraphHopper active, 세 nginx 로그 `0640 www-data:adm`, `nginx -t` 통과
- `journalctl --verify`는 각 journal 파일을 `PASS`로 판정했다. 일부 파일의 unused data
  진단은 있었으나 검증 실패는 없었다.

## production

### HTTP bootstrap 적용

- 초기 SSM 적용에서 기존 설정을
  `/var/backups/runninggu-log-privacy-20260910T085609Z`에 백업하고 named·기본 server에
  최소 access log와 요청 error log 폐기 설정을 설치했다.
- `nginx -t`, reload, active 확인이 통과했다. production·기본 거부 access log는 모두
  `0640 www-data:adm`이다.
- 후속 SSM 검사에서 named Host 404, 알 수 없는 Host 404,
  허용 목록 밖 `TRACE` 405를 확인했다. 두 access log가 증가했고 nginx 파일 15행과 시험
  시점 journal을 검사한 결과 표식 0건, 최소 형식 불일치 0건, `passed=true`였다.

### TLS·백엔드 최종 검사 — 2026-09-11

운영 상태 점검에서 release `a2c7700`, backend·nginx·
GraphHopper active, 임시 logger drop-in 없음, `nginx -t` 통과와 두 access log의
`0640 www-data:adm`을 확인했다. 인증서는 `CN=api.runninggu.store`, Let's Encrypt `YE1`,
유효기간 2026-09-10 13:22:57Z부터 2026-12-09 13:22:56Z까지다. 외부에서 HTTPS API 200과
HTTP→HTTPS 301도 확인했다.

#336에 기록된 앞선 전체 검사는 2026-09-10 14:24:11Z부터 14:26:05Z까지 실행되어 nginx
4개·59행과 최근 14일 journal 234행에서 일반 패턴 0건·`passed=true`를 확인했다. 아래 이메일
기록이 든 archive는 그 검사 종료 18분 19초 뒤인 14:44:24Z에 닫혔으므로 두 판정은 서로 다른
시점의 실행이다.

이후 첫 전체 검사는 요청 상태·access log 증가·
시험 표식·좌표·비밀값 검사를 통과했지만, 최근 14일 backend journal의 이메일 형식 1건으로
`passed=false`였다. 원문을 가져오지 않고 출처만 집계한 결과 이 기록은 현재 안전 release의
backend 기동 시각 2026-09-10 14:44:48Z보다 24초 앞선 14:44:24Z에 닫힌 archive 한 파일의
마지막 기록이었다. 활성 journal이나 현재 release에서 새로 기록된 값이 아니었다.

정리 명령은 실행 직전에 대상이 8MiB archive 한 개,
이메일 형식 1건, 안전 release 기동 전 기록인지 다시 확인한 뒤 그 archive만 제거했다.
8MiB는 systemd의 binary journal 파일 할당 크기이지 해석된 텍스트 로그의 분량이 아니다.
앞선 234행과 아래 211행도 그 사이의 배포·journal 회전·archive 정리를 포함한 서로 다른 시점의
조회 결과이므로 삭제 전후 행 수로 단순 차감하지 않는다. `journalctl --verify`를 통과한 뒤 같은
전체 검사를 재실행한 결과는 다음과 같다.

- HTTP redirect 301, 이메일 조회 200, 좌표 검증 실패 400, 로그인 실패 401,
  알 수 없는 Host 연결 종료, 과대 헤더 400
- named·기본 거부 access log 증가, 기존 요청 error log 크기 불변
- nginx 파일 4개·216행, 최근 14일 backend/nginx journal 211행 검사
- 시험 표식 0건, 이메일·좌표·비밀값 일반 패턴 모두 0건, `passed=true`

따라서 staging과 production 모두 TLS를 통과한 실제 backend 요청, nginx 파일 로그,
backend/nginx journal, 최근 보존 로그 일반 패턴 검사를 충족했다. 로그 개인정보 보호는 더
이상 약관 활성화의 차단 조건이 아니다.
