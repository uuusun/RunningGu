# 서버 로그 개인정보 보호 로컬 구현·검증 — 2026-09-10

## 범위

- 기준: `origin/develop` `7c899c4`
- 작업 브랜치: `fix/log-privacy`
- 검증 대상: Spring Boot 전역·배치·Hibernate 로그, nginx 앱 host·기본 거부 server 로그,
  실서버 표식 검사기
- 금지 원문: 비밀번호·토큰·인증 코드·이메일 주소·이용자 좌표

이 기록은 저장소 구현과 로컬 검증 결과다. 실서버 적용 결과는
[`server-log-privacy-staging-production-20260910.md`](server-log-privacy-staging-production-20260910.md)에
분리해 기록한다.

## 구현 결과

- 처리되지 않은 예외와 배치 실패는 예외 message·cause·stack 대신 안전한 `code`, 예외
  클래스, `traceId`, 작업 상태·건수만 기록한다.
- framework 요청 상세 로그와 내장 Tomcat 접속 로그를 명시적으로 끄고 접속 기록은 nginx
  최소 형식 한 곳에서만 만든다.
- framework Throwable 출력은 `%nopex`로 원문을 제거하고 PostgreSQL 제약 Detail을 출력하는
  Hibernate logger를 끈다.
- nginx 접속 로그는 IP·시각·allowlist 방식·status·응답 크기·처리시간만 남긴다. URI·query·
  Host·User-Agent는 앱 host와 기본 거부 server 모두 기록하지 않는다.
- 요청 원문을 필드별로 제외할 수 없는 nginx 요청 처리 오류 로그는 저장하지 않는다.
  4xx·5xx와 upstream 상태는 최소 접속 로그, 앱 오류는 `code`·예외 클래스·`traceId`, nginx
  기동·설정 오류는 systemd journal로 확인한다.
- 서버 내부에서 고유한 가짜 이메일·좌표·토큰 요청을 보내고 nginx 파일과 backend/nginx
  journal을 검사하는 `check-server-log-privacy.py`를 추가했다. 원문은 출력하지 않고 상태·
  조회 행 수·검출 건수만 출력한다.

## 로컬 자동 검증

### 백엔드 표적 테스트

JDK 21로 아래 4개 클래스, `@Test` 12개를 실행했다.

```powershell
cd backend
.\gradlew.bat test `
  --tests com.runninggu.server.ProblemDetailIntegrationTest `
  --tests com.runninggu.server.course.application.CourseSyncServiceTest `
  --tests com.runninggu.server.auth.infrastructure.AuthDataCleanupSchedulerTest `
  --tests com.runninggu.server.common.logging.LogPrivacySourceTest `
  --no-daemon
```

결과: `BUILD SUCCESSFUL`. 전역 중첩 예외·framework Throwable·배치 실패에 넣은 이메일·좌표·
토큰 표식이 출력되지 않고 안전한 진단 필드는 유지됐다.

테스트 민감도도 확인했다. `%nopex` 방어를 추가하기 전에 전역 처리기에 Throwable 인자를
임시로 되살리자 `ProblemDetailIntegrationTest` 2개 중 개인정보 로그 검사 1개만 실패했다.
안전한 구현을 즉시 복구한 뒤 위 최종 12개가 통과했다. 현재는 같은 회귀를 소스 검사와
전역 `%nopex`가 함께 막는다.

### nginx·실서버 검사기 자체 테스트

아래 묶음을 연속 두 번 실행했다.

```powershell
python backend/deploy/nginx/test_log_privacy.py
python backend/deploy/validation/test_check_server_log_privacy.py
python -m py_compile backend/deploy/validation/check-server-log-privacy.py
```

각 실행 결과: nginx 설정 정적 테스트 4개 통과, 실서버 검사기 단위 테스트 2개 통과,
Python 문법 검사 통과. 두 실행의 결과가 같았다.

### Linux nginx 격리 실행

WSL Ubuntu 22.04에 Ubuntu 패키지의 nginx 1.18.0을 설치했다. 저장소의 bootstrap·최종·기본
거부 설정에서 listen port, 인증서, 로그 경로와 upstream만 임시 경로로 바꾸고 나머지는 그대로
읽는 격리 검사기를 연속 두 번 실행했다.

```bash
python3 backend/deploy/nginx/test_nginx_runtime.py
```

두 실행 모두 bootstrap·최종 설정의 `nginx -t`가 통과했다. 실제 요청 결과는 HTTP redirect
301, upstream이 없는 좌표·로그인 요청 502, 알 수 없는 HTTP·HTTPS Host 연결 종료, 허용 목록
밖 `TRACE` 405, 과대 헤더 400이었다. named 접속 로그 5행과 기본 거부 접속 로그 2행이 생겼고,
모든 행이 `runninggu_minimal` 형식과 일치했다. 이메일·좌표·토큰·경로 표식 검출은 0건,
형식 불일치는 0건이었으며 `TRACE`는 원문 대신 `OTHER`로 기록됐다. 최종 결과는 두 번 모두
`passed=true`였다.

### 빌드와 전체 테스트

- Windows Docker Desktop의 Linux 엔진 pipe는 끝내 생성되지 않았다.
- WSL Ubuntu 22.04에 독립 Docker Engine 29.1.3과 OpenJDK 21.0.12를 구성하고 PostgreSQL
  17.10 Testcontainers와 Ryuk이 실제로 기동되는 상태에서 전체 검증을 다시 실행했다.

```bash
cd backend
/tmp/runninggu-gradle-wrapper/gradlew test bootJar --no-daemon --console=plain
```

결과: `BUILD SUCCESSFUL`(15분 26초). XML 결과 기준 84개 suite, `@Test` 446개가 실행됐고
실패·오류·건너뜀은 모두 0개다. `AuthSchemaIntegrationTest` 7개도 실제 PostgreSQL에서
실행됐으며, 중복 이메일 DB 제약 오류의 이메일 표식이 Hibernate 로그에 남지 않는 검사가
통과했다.

## 서버 적용 검증 기준

staging과 운영에서 각각 다음을 완료한다.

1. 저장소 설정을 설치하고 `nginx -t`, reload, backend active 상태를 확인한다.
2. [EC2 실행서 §13.1](../aws-ec2-staging-runbook.md#131-로그-개인정보-표식-검사)의 검사기를
   대상 host로 실행한다.
3. `statusChecksPassed=true`, 두 접속 로그 증가, 기존 요청 오류 로그 크기 불변,
   `forbiddenMatches=0`, 최근 보존 로그의 이메일·좌표·비밀값 일반 패턴 0건,
   `passed=true`를 확인한다.
4. 대상 커밋·적용 시각·검사한 파일/행 수·journal 행 수·검출 건수만 서버별 증거 문서에
   기록한다. 표식이나 로그 원문은 복사하지 않는다.

과거 패턴이 1건이라도 나오면 약관 활성화·공개 조건을 통과시키지 않는다. 정확한 파일 또는
journal 보존 범위를 정해 삭제하거나 보존기간이 끝날 때까지 공개를 미룬 뒤 다시 검사한다.
