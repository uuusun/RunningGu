# Resend SMTP 스테이징 전환 기록 (2026-09-10)

## 배경

- AWS SES 프로덕션 액세스 요청이 승인되지 않아 일반 사용자 주소로 거래성 메일을 보낼 수 없었다.
- 운영책임자 결정으로 이슈 #321의 거래성 메일 발송자를 Resend로 전환했다.
- 가입 인증과 비밀번호 재설정만 발송하고 P0 마케팅 메일은 발송하지 않는 결정-59는 유지한다.

## 설정

- Resend에서 `runninggu.store` 도메인을 도쿄(`ap-northeast-1`) 발송 리전으로 등록하고 `Verified` 상태를 확인했다.
- Gabia DNS에 Resend DKIM TXT와 Return-Path용 CNAME을 추가했다. 기존 AWS SES DKIM 레코드는 삭제하지 않았다.
- 수신 기능과 열람·클릭 추적은 활성화하지 않았다.
- 스테이징 인스턴스 `i-07aa483968f4daddc`의 `/etc/runninggu/application.env`를 백업한 뒤 다음 비밀이 아닌 항목을 변경했다.

```text
SMTP_HOST=smtp.resend.com
SMTP_PORT=587
SMTP_USERNAME=resend
SMTP_FROM_ADDRESS=no-reply@runninggu.store
SMTP_FROM_NAME=런닝구
MAIL_ENABLED=true
```

`SMTP_PASSWORD`에는 Resend API 키를 서버 환경변수로만 저장했다. 키 값이나 일부 문자열은 이 기록과 명령 출력에 남기지 않았다.

## 검증 결과

1. `smtp.resend.com:587` STARTTLS 연결에서 인증서 검증 성공을 확인했다.
2. `runninggu-backend.service` 재시작 뒤 `active`를 확인했다.
3. 스테이징 공개 API `GET /api/contests?size=1`은 200으로 응답했다.
4. 개별 검증 시험 주소에 `POST /api/auth/email/send-code`를 호출해 204를 받았고, Resend에서 `[런닝구] 이메일 인증 코드`가 `Delivered`인 것을 확인했다.
5. 심사 계정 주소에 `POST /api/auth/password/reset-request`를 호출해 202를 받았고, Resend에서 `[런닝구] 비밀번호 재설정`이 `Delivered`인 것을 확인했다.
6. 2026-09-10 이건모가 실제 Gmail 받은편지함에서 가입 인증 코드를 열어 가입을 완료했다. 같은 세션에서 EMAIL 탈퇴와 재가입, 서버 DB 삭제까지 확인했다([#241](https://github.com/uuusun/RunningGu/issues/241)).
7. 2026-09-11 운영책임자 유선경이 실제 받은편지함에서 비밀번호 재설정 메일을 열고, 재설정 링크 진입 → 새 비밀번호 설정 → 새 비밀번호 로그인을 직접 확인했다.

최초 환경변수 치환 때 셸 문자열 보간으로 발신 주소가 잘못 저장되어 발송이 실패했다. `SMTP_FROM_ADDRESS=no-reply@runninggu.store`로 바로잡고 서비스를 재시작한 뒤 위 두 발송이 성공했다.

## 남은 확인

- 최종 출시 후보 서명 APK에서 운영 API를 대상으로 가입·비밀번호 재설정 회귀 확인
- 활성화 뒤 PRIVACY 1.2의 Resend 처리위탁·국외 처리 문안과 앱 공개본 대조

`Delivered`는 Resend가 수신 측 메일 서버로 전달했다고 기록한 상태다. 6·7번은 이 상태값과 별개로 사람이 실제 받은편지함과 후속 화면을 확인한 결과다. 7번은 이 문서를 수정한 세션이 재현한 결과가 아니라 운영책임자의 직접 확인을 기록한 것이며, 이메일 주소·인증 코드·재설정 링크·비밀번호는 기록하지 않았다. 확인에 사용한 앱 빌드와 서버 환경 식별자는 전달받지 않았으므로 최종 출시 후보 검증을 대신하지 않는다.
