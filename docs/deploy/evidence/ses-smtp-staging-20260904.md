# 스테이징 SES·DNS·SMTP 설정 기록 — 2026-09-04

> **수신 identity 권한 누락을 보완한 뒤 실제 앱 인증 메일 발송 2건 모두 204로 성공했다.**
> 사용자가 **두 메일함 모두 도착**을 확인했다. 아래 최초 실패 기록과 수정 후 성공을 구분한다.
> 이 기록은 프로덕션 메일 준비, 정상 가입 또는 30분 앱 API 부하 시험의 합격 증거가 아니다.

## 범위와 승인

- 운영책임자가 SES·가비아 DNS·SMTP 설정과 테스트 수신 주소 2개를 지정했다.
  가비아 로그인과 두 AWS 이메일 identity 확인 링크 클릭도 사용자에게 확인받았다.
- 최초 발송 실패 원인 설명 후, 기존 발신·수신 제한을 유지하며 두 수신 identity만 IAM Resource에
  추가하는 것을 운영책임자가 승인했다. 네이버 계정/메일함 접근 권한 변경이 아니다.
- 대상은 서울 `ap-northeast-2`의 기존 staging EC2와 `runninggu.store`다.
  배포 source는 `1c3f2ef02514a2ab3efcb85bb8edb7196759579f`로 유지했다.
- Free Plan 업그레이드, SES production access 신청, EC2 크기 변경, 보안 그룹 공개 범위 변경,
  `main` 릴리스, 다른 DNS 레코드 변경은 하지 않았다.
- 수신 주소·SMTP 사용자 값/비밀번호·인증 코드 원문은 이 문서나 Git에 넣지 않는다.

## SES·DNS에서 완료한 것

- 서울 SES에 `runninggu.store` identity를 만들고 Easy DKIM RSA 2048을 설정했다.
- 가비아에 AWS가 제공한 `_domainkey` CNAME 3개만 TTL 600으로 추가했다.
  기존 `staging-api` A 레코드는 유지했고 최종 레코드는 4개다.
- 가비아 저장 시각은 16:53:55 KST. DNS 조회로 세 CNAME의 기대 대상과 기존 A 레코드를 확인했다.
- AWS 재조회에서 domain verified, DKIM `SUCCESS`, 테스트 수신 identity 2개 verified를 확인했다.
- 기본 MAIL FROM을 사용한다. 별도 MX·SPF·DMARC·custom MAIL FROM을 설정한 것으로 표시하지 않는다.
- `ProductionAccessEnabled=false` 유지. 설정 전 계정 조회 quota는 200/24시간·1/초였다.
  SES 샌드박스와 AWS Free Plan은 서로 다른 설정이다.

## SMTP 자격 증명과 최소 권한

- 콘솔 로그인이 없는 전용 IAM 사용자 `runninggu-staging-smtp`와 인라인 정책
  `runninggu-staging-send-only`를 만들었다. 발송 동작은 `ses:SendRawEmail` 하나로 제한했다.
- `ses:FromAddress`는 가이드의 `no-reply@runninggu.store`로, `ses:Recipients`는 승인된 두 주소로
  제한했다. 수신 목록 누락도 거부한다. 다른 주소로 발송할 수 있게 `Resource=*`로 열지 않는다.
- 최초 Resource는 발신 도메인 identity 하나였다. 실제 발송에서 수신 identity ARN에 대한
  `Access denied`가 확인되어, 승인 후 두 수신 identity만 Resource에 추가했다.
  최종 Resource는 도메인 1개 + 수신 identity 2개이며 기존 Condition·Action은 그대로다.
  편집 내용의 기대 JSON 일치와 AWS의 정책 저장 성공을 확인했다. 추가 키 발급·서버 재시작은 없었다.
- 서울용 SMTP 비밀번호를 생성해 기존 KMS로 암호화한 비공개 S3 임시 객체로 EC2에 전달했다.
  EC2에서 암호화 속성·SHA-256을 대조했고 비밀값을 명령 인수/출력에 넣지 않았다.
- 적용 후 임시 S3 객체 **1개·329B 영구 삭제 성공, 삭제 실패 0개**를 콘솔에서 확인했다.
  버전 관리가 없는 객체로 S3 복구는 불가하다. 같은 경로의 기존 배포 ZIP·source archive 2개는 유지했다.
  EC2의 실제 SMTP 설정은 유지되며 S3에서 비밀값을 다시 내려받는 런타임 의존성은 없다.

## EC2 적용 — 성공

SSM `c1a8da7b-8619-45d7-b299-e36ce6a4605f`: **Success / exit 0**,
08:03:04–08:03:18 UTC.

```text
smtp_tls=TLSv1.3
smtp_auth_code=235
mail_config_applied_at=2026-09-04T08:03:05.671658+00:00
backend_ready_at=2026-09-04T08:03:18.644582+00:00
mail_enabled=true; smtp_values_verified_without_printing
db_jwt_external_keys_heap_limits_preserved; postgres_graphhopper_not_restarted
```

- `/etc/runninggu/application.env`의 SMTP 6개 항목과 `MAIL_ENABLED=true`만 변경했다.
  원래 소유권/모드 유지·원자 교체 후 백엔드만 한 번 재시작했다.
- 이전 환경 파일과 테스트 수신 입력은 EC2의 `/opt/runninggu-validation/smtp-20260904/`에
  root 전용으로 보관했다. 디렉터리 `0700`, 개별 파일 `0600`이다. 내용을 로그로 출력하지 않는다.
- DB·JWT·외부 API 키·GH 설정·힙 값의 변경 없음과 실제 프로세스 SMTP 환경 일치를 검사했다.
- Spring `MemoryHigh=1GiB`, `MemoryMax=1536MiB` 유지. DB·GH 컨테이너의 시작 시각도 그대로였다.
- backend·GH active, `NRestarts=0`, 내부 대회 조회 HTTP 200을 확인했다.
  이는 SMTP 로그인 증거이지 메시지 발송/수신 증거가 아니다.

## 실제 발송 실패와 원인

1. SSM `8ea73d11-c1fc-4993-bb4c-e2e1d9d155fb`: **Failed / exit 1**,
   08:15:50–08:15:51 UTC. 공개 HTTPS `POST /api/auth/email/send-code`의 첫 수신 요청이 **502**였다.
   두 번째 요청, 후속 journal 개인정보 검사, 해당 명령의 마지막 readiness 검사는 실행되지 않았다.
2. SSM `f55dc0f0-2ef5-4e04-8d4b-6664cbd46c38`: **Failed / exit 1**, 08:18:42 UTC.
   인증 코드가 없는 진단 메일로 SMTP 단계를 분리했다. 인증 `235`, MAIL FROM `250`, RCPT TO `250`,
   **DATA `554`**였다. 응답은 SMTP 사용자가 **수신 identity에 `ses:SendRawEmail` 권한이 없다**는 내용이다.
   수신 주소를 가려 출력했고 진단 메일도 수락되지 않았다.
3. 원인은 이 시험의 IAM Resource 누락으로 확인했다. RAM·GraphHopper 문제, DNS 미전파,
   SMTP 비밀번호 오류로 추정하지 않는다. 발송 실패를 성공으로 표시하거나 정책 전체를 열어 우회하지 않는다.

계약상 SMTP 실패는 `502 EXTERNAL_API_ERROR`이며 발송 코드·쿨다운은 반영하지 않는다.
이 실패 원인을 확인하면서 코드 검증(`/auth/email/verify`)·회원가입 endpoint를 실행하거나
DB 직접 삽입·인증 우회를 하지 않았다.

## 권한 보완 후 실제 앱 재검증 — 성공

SSM `0ee21bb9-eb79-49dd-8449-e4b5ce645ece`: **Success / exit 0**,
08:32:36–08:32:40 UTC. 최초 시험과 같은 검증 코드임을 실행 전 대조했다.

```text
recipient_1_http_status=204
recipient_2_http_status=204
known_smtp_credentials_or_recipient_matches_in_backend_journal=0
https_readiness=200; private_file_permissions=ok; signup_not_executed
mail_smoke_finished_at=2026-09-04T08:32:40.048889+00:00
```

- EC2에서 공개 staging HTTPS → Nginx → 백엔드의 `POST /api/auth/email/send-code`를
  각 주소에 1회 실행했고, 모두 **204·빈 응답**을 확인했다. 두 발송 사이에 1.1초 간격을 뒀다.
- TLS 인증서 검증을 유지했고 redirect·자동 재시도는 하지 않았다.
- 08:03 UTC 이후 백엔드 journal을 메모리에서 검사해 실제 SMTP 사용자 값·비밀번호·두 수신 주소의
  원문 일치 0건을 확인했다. journal 원문은 출력하지 않았다. 이 검사가 모든 로그 저장소와
  모든 종류의 시크릿 부재를 증명하는 것은 아니다.
- 환경 파일 `0640`, 비공개 입력 파일 `0600`, 상위 디렉터리 `0700`, root 소유와 실제 프로세스의
  `MAIL_ENABLED=true`를 확인했다. 후속 공개 HTTPS 대회 조회는 200이었다.
- **204는 서버 발송 처리 성공이며 수신함 배달 확인과는 다르다.** 사용자에게 새로 보낸
  `[런닝구] 이메일 인증 코드` 메일의 주소별 도착 여부를 요청했다. 코드 원문은 요청하지 않았다.
- 이후 사용자가 **두 메일함 모두 도착**이라고 답해 실제 수신도 확인했다. 일반 수신함/스팸함 중
  어느 폴더였는지, 원본 헤더의 DKIM/SPF 판정은 별도로 확인하지 않았으므로 확대해 주장하지 않는다.
- 인증 코드 검증·회원가입·로그인·비밀번호 재설정은 실행하지 않았다.

## 부수 점검과 한계

- 준비 도구 단위 테스트 `python -m unittest discover -s scripts/api -p 'test_*.py' -v`: **14개 통과**.
- 제한된 로컬 실행 환경의 공개 조회는 transport 실패였다. 기본 curl은 로컬 proxy 연결 실패를 보고했다.
  허용된 네트워크 환경에서 동일 공개 조회 도구를 다시 실행하자 7항목 모두 기존 기대 결과로 통과했다.
  이 로컬 실행 환경 실패를 staging 서비스 장애로 집계하지 않는다.
- 최초 recipient 생성 1회는 `BadRequestException`, 재입력 후 성공했다. 최초 오류의 원인은 미확정이다.
  SESv2 identity 목록의 잘못된 paginator 사용은 IAM 생성 전에 실패했고 NextToken 방식으로 수정했다.
- CloudShell 결과 조회에 기존 터미널 입력이 섞여 SSM 콘솔에서 실제 적용 결과를 확인했다.
  SSM 편집기의 복사된 이전 명령/공백 문제는 **실행 전에** 발견해 전체 교체·전송 내용 일치 확인으로 처리했다.
- backend 코드·API·DB 스키마는 변경하지 않아 `test bootJar`를 다시 실행하지 않았다.
  HTTP 계약과 제품 timeout·재시도 정책 변경은 없다.

## 남은 일

1. 정상 가입/로그인용 비공개 입력 절차, 외부 API 허용 호출량, 고정 성공 요청 세트와 혼합 부하 도구를 준비한다.
   프로덕션 수신 제한 해제·비밀번호 재설정 전체 흐름·30분 앱 API 시험·4GiB 시험은 미완료다.

EC2는 기존 8GiB 실행 상태다. commit·push·PR 생성은 하지 않았다.

후속 정상 가입 요청의 [사전 점검](signup-preflight-20260904.md)에서 서버의 연령 확인 구현 #263이
당시 미머지임을 확인했다. 이후 사용자 머지와 [새 SHA 배포](staging-develop-b9e632d-20260904.md)로
그 차단은 해소됐으며 SMTP 설정은 그대로 보존했다. 메일 발송/수신 완료와 정상 가입·로그인
검증 완료는 계속 구분한다.

## 참고

- [기존 API 계약 §1-3](../../files/런닝구_API_명세서.md#1-3-post-authemailsend-code)
- [AWS SMTP 자격 증명](https://docs.aws.amazon.com/ses/latest/dg/smtp-credentials.html)
- [AWS SES IAM 권한 제어](https://docs.aws.amazon.com/ses/latest/dg/control-user-access.html)
- [AWS SES 샌드박스](https://docs.aws.amazon.com/ses/latest/dg/request-production-access.html)
