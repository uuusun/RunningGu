# 서버 로그 개인정보 보호 정책

## 목적과 범위

런닝구 서버의 로그에는 비밀번호·토큰·인증 코드·이메일 주소·이용자 좌표 원문을 남기지
않는다. 대상은 Spring Boot 애플리케이션 로그, Hibernate 오류 로그, nginx 접속 로그,
nginx 요청 처리 오류 로그와 systemd journal이다(SPEC §9.4, NFR-19, 결정-61).

로그 보관기간만 줄여서는 원문 노출을 막을 수 없다. 저장 전에 요청·예외 원문을 제외하고,
시험 표식이 실제 오류 경로를 지난 뒤 모든 로그 저장소에서 0건인지 확인한다.

## 기록하는 정보

| 위치 | 기록하는 정보 | 제외하는 정보 |
|---|---|---|
| Spring Boot 전역 오류 | 서버 생성 `traceId`, 안정적 오류 `code`, 예외 클래스명 | 예외 message·cause·stack, 요청·응답·헤더 원문 |
| 배치·정리 오류 | 작업명, 안전한 상태 enum, 건수·소요시간·실패 횟수, 예외 클래스명 | 예외 message·cause·stack, 행 값, 외부 요청 URL |
| nginx 접속 파일 | IP, 시각, allowlist 방식, status, 응답 크기·시간, upstream status·시간 | URI·query·Host·User-Agent와 요청·응답 본문 |
| nginx 요청 처리 오류 | 파일에 기록하지 않음 | nginx가 자동으로 덧붙이는 request 원문 전체 |
| nginx 기동·설정 오류 | systemd journal의 프로세스 기동·설정 검사 결과 | 사용자 HTTP 요청 문맥 |

nginx OSS의 `error_log`는 `access_log`처럼 요청 필드를 골라 형식화할 수 없다. 앱 host와
기본 거부 server는 요청 문맥 오류를 `/dev/null`로 보내고, 4xx·5xx·upstream 상태는
`runninggu_minimal` 접속 로그에서 확인한다. 애플리케이션 오류는 응답과 로그의 `traceId`로
연결한다. `nginx -t`, reload·기동 실패는 systemd journal에서 확인한다.

PostgreSQL 제약 오류는 위반 행 값을 Detail에 포함할 수 있으므로
`org.hibernate.engine.jdbc.spi.SqlExceptionHelper` 로그를 끈다. HTTP 응답 계약과 DB 제약은
바꾸지 않으며, 전역 오류의 `code`와 `traceId`를 진단 기준으로 사용한다. framework가 별도
Throwable을 기록하는 경로도 전역 `%nopex` 변환으로 message·cause·stack을 출력하지 않는다.
framework 요청 상세 로그와 내장 Tomcat 접속 로그를 명시적으로 끄고 접속 기록은 nginx 최소
형식 한 곳에서만 만든다.

## 자동 검증

- `ProblemDetailIntegrationTest`: framework 요청 상세·Tomcat 접속 로그 비활성 설정을 확인하고,
  중첩 예외의 이메일·좌표·토큰 표식이 응답과 로그에 없으며 `INTERNAL_SERVER_ERROR`, 예외
  클래스, `traceId`가 남는지 확인한다.
- `CourseSyncServiceTest`, `AuthDataCleanupSchedulerTest`: 배치 실패 동작을 유지하면서 예외
  원문 표식이 로그에 없는지 확인한다.
- `AuthSchemaIntegrationTest`: 실제 PostgreSQL 제약 위반의 이메일 표식이 Hibernate 로그에
  없는지 확인한다.
- `LogPrivacySourceTest`: 애플리케이션 코드가 Throwable 또는 예외 message를 로그 인자로
  다시 넘기면 실패한다.
- `backend/deploy/nginx/test_log_privacy.py`: 모든 server가 최소 접속 로그와 요청 오류 폐기
  설정을 쓰며 형식에 요청자 제어 문자열이 없는지 확인한다.
- `backend/deploy/nginx/test_nginx_runtime.py`: Linux nginx에서 bootstrap·최종 설정을
  `nginx -t`로 검사하고, 고포트 격리 실행 뒤 이메일·좌표·토큰·알 수 없는 Host·허용 목록
  밖 방식·과대 헤더 요청의 접속 로그 형식과 표식 0건을 확인한다.
- `backend/deploy/validation/test_check_server_log_privacy.py`: 일반 이메일·좌표·비밀값 패턴과
  현재·압축 회전본 조회가 실제 검출되는지 확인한다.

자동 검사는 실제 서버의 include, 별도 로그 전달, journal 권한과 적용 상태를 증명하지 않는다.
배포할 때 저장소 설정과 `nginx -T`를 대조하고, 고유한 가짜 이메일·좌표·토큰 표식을 넣은
정상·검증 실패·잘못된 Host 요청 뒤 nginx 파일과 backend/nginx journal에서 표식 0건을
확인한다. 현재·압축 회전본과 최근 14일 journal에서는 일반 이메일·좌표·비밀값 형태도 함께
검사한다. 원본 로그와 표식 값은 증거 문서나 CI 출력에 복사하지 않고 저장소별 검사 행 수와
검출 건수만 기록한다.

운영은 같은 검사기에 운영 로그 파일명만 명시한다.

```bash
sudo python3 backend/deploy/validation/check-server-log-privacy.py \
  --host api.runninggu.store \
  --named-access-log runninggu-production.access.log \
  --legacy-error-log runninggu-production.error.log
```

과거 로그에서 일반 패턴이 발견되면 공개 조건을 통과시키지 않는다. 원문을 복사하지 않고
저장소·기간·검출 건수만 기록한 뒤, 해당 저장소의 정확한 파일 또는 journal 보존 범위를
확정해 삭제하거나 보존기간이 끝날 때까지 공개를 미룬다. nginx 파일과 host 전체 journal을
한꺼번에 삭제하는 명령은 영향 범위를 검토한 별도 운영 절차에서만 실행한다.

## 보관과 접근

nginx 접속 파일은 매일 회전하고 회전본 14개와 `maxage 14`를 적용한다. 새 파일은
`0640 www-data:adm`으로 만들고 회전본은 압축한다. systemd journal은 호스트 전체 용량 상한을
따르며 운영책임자만 원본을 조회한다. 외부 로그 전달이나 별도 사본을 추가하면 같은 금지 정보
검사와 별도 보유기간 결정을 적용한다.
