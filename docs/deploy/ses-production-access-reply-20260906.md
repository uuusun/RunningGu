# 2026-09-06 SES 추가 정보 요청 회신 제출 기록

사용자가 작성한 내용으로 회신하도록 요청하여 **2026-09-06 14:27:02 KST에 제출했다.**
AWS Support Center의 기존 SES 프로덕션 액세스 문의에서 아래 본문을 전송했고,
Correspondence에 발신자·등록 시각·전체 회신 내용이 추가된 것을 확인했다.
Gmail 답장이 아니라 Support Center의 Web 회신을 사용했으며 첨부파일은 없다.
이 문서의 영문 본문은 제출 당시 기록으로 보존한다. 후속 AWS 답변·승인은 아직 확인하지 않았다.

AWS 메일은 2026-09-05 22:33 KST에 도착했고, 2026-09-06에 본문을 확인했다.
서비스 용도·발송 빈도·수신자 관리·반송/신고/수신거부 처리·메일 예시를 추가로 요청했다.
현재 확인한 메일은 승인이나 거절 통보가 아니다.

숫자로 확정된 예상 발송량은 없어 임의의 일일·월간 물량을 넣지 않았다. 공개 소개 페이지는
아직 준비 단계로 설명한다. 메일 예시는 실제 발송 코드의 제목·본문을 사용하되 인증 코드와
재설정 링크는 명시적인 자리표시자로 대체했다. 실제 토큰·사용자 이메일은 포함하지 않는다.
후속 사용자 요청에 따라 미요청 메일 행동 안내를 보강했고, 아래 예시는 수정된 로컬
백엔드 소스와 일치한다. 운영 배포를 완료했다고 표현하지 않는다.

## 제출한 영문 회신 본문

```text
Hello AWS Support Team,

Thank you for reviewing our request. We are requesting Amazon SES production access in the Asia Pacific (Seoul) region (ap-northeast-2) for the following transactional use case.

1. Service and purpose

RunningGu (런닝구) is an Android application for users in South Korea. It provides marathon schedules, travel itinerary recommendations around races, and running course recommendations. The application is currently in pre-release testing. Our service domain is https://runninggu.store; the public introduction page is still being prepared.

We will use SES only for email verification codes during account registration and password reset links requested by existing email-account users. We will not send marketing campaigns, newsletters, unsolicited messages, or promotional content.

2. Recipients and sending frequency

Recipients enter their email address directly in the application and request a verification or password reset email. Email registration requires successful verification before the account can be created. Password reset emails are sent only for existing email accounts. We do not use purchased, rented, scraped, or third-party mailing lists.

Each message is triggered by an individual request. There are no scheduled campaigns or recurring mailings. We are currently testing with a small team and do not yet have an established daily or monthly production volume. Future volume will depend on actual registration and password reset requests. We need production access so users can receive these messages without individually verifying their recipient addresses in the SES console before using the application.

Our backend implements a 60-second resend cooldown per email address for each flow. Signup verification codes expire after 10 minutes and are limited to five incorrect verification attempts. Password reset links expire after 30 minutes and are single-use.

3. Verified identity and bounce/complaint handling

The runninggu.store domain identity is verified in ap-northeast-2, and DKIM is enabled and successfully configured. Our sending address is no-reply@runninggu.store.

We configured SES bounce and complaint notifications to the Amazon SNS topic runninggu-ses-feedback, with a confirmed email subscription to our operational contact, runninggu.play@gmail.com. On September 5, 2026, we tested both bounce and complaint notifications using the SES mailbox simulator and confirmed that both notifications reached this mailbox.

Account-level suppression is enabled for bounces and complaints. Our operator will review these notifications and investigate the affected sending flow. We will not repeatedly send to suppressed addresses or remove suppression entries without investigating and resolving the cause.

4. Unsubscribe and support

We do not operate a marketing subscription list. These messages are limited to individually requested account verification and password recovery, with no recurring follow-up mailings. Users can contact runninggu.play@gmail.com regarding unexpected messages or other email concerns. Our revised templates tell recipients not to use or share codes or links they did not request, explain that receiving an email alone does not complete registration or change a password, and provide a support contact for repeated unexpected messages. The password reset template also advises users to open the app directly and change their password if they suspect account compromise. Users are instructed not to include codes, reset links, or passwords in support requests.

5. Email examples

The following revised Korean templates are prepared in our backend source and have not yet been deployed. The verification code and reset link shown below are placeholders, not live credentials.

Signup verification
From: no-reply@runninggu.store
Subject: [런닝구] 이메일 인증 코드

런닝구 이메일 인증 코드입니다.

[6-digit verification code]

인증 코드는 10분 동안 유효합니다.
본인이 요청하지 않았다면 인증 코드를 입력하거나 다른 사람에게 알려주지 마세요.
이 메일을 받는 것만으로 회원가입이 완료되지는 않습니다.
요청하지 않은 메일이 반복되면 runninggu.play@gmail.com으로 문의해 주세요.
문의할 때 인증 코드나 비밀번호를 보내지 마세요.

Password reset
From: no-reply@runninggu.store
Subject: [런닝구] 비밀번호 재설정

런닝구 비밀번호 재설정 링크입니다.

[single-use password reset link]

링크는 30분 동안 한 번만 사용할 수 있습니다.
본인이 요청하지 않았다면 링크를 열거나 다른 사람에게 전달하지 마세요.
이 메일을 받는 것만으로 비밀번호가 변경되지는 않습니다.
요청하지 않은 메일이 반복되면 runninggu.play@gmail.com으로 문의해 주세요.
계정 도용이 의심되면 런닝구 앱을 직접 열어 비밀번호를 변경해 주세요.
문의할 때 재설정 링크·인증 코드·비밀번호를 보내지 마세요.

Please let us know if you require any further information to review this request.

Thank you,
RunningGu Team
runninggu.play@gmail.com
```

## 확인 근거

- 제품 범위·메일 용도: `SPEC.md` §9.4·결정-59
- SES identity·DKIM·SNS·suppression 설정과 모의 알림 수신: [신청·검증 기록](evidence/ses-production-access-20260905.md)
- 실제 제목·본문: `backend/src/main/java/com/runninggu/server/auth/infrastructure/SpringMailVerificationMailSender.java`
- 인증·재설정 제약: `EmailVerificationTransaction`, `PasswordResetTransaction`, SPEC NFR-10
- 초안 작성 단계에서는 서버 배포·일반 사용자 발송 시험·AWS 회신 제출을 수행하지 않았다.
  이후 사용자 요청으로 위 시각에 회신 제출을 완료했으며 서버 배포·일반 사용자 발송 시험은 남아 있다.

## 2026-09-06 미요청 메일 안내 보완·검증

- 기존의 한 줄짜리 무시 안내를 코드·링크 비사용/공유 금지, 수신만으로 가입·비밀번호 변경이
  완료되지 않는다는 설명, 반복 수신 문의 경로로 보강했다. 재설정 메일은 계정 도용이 의심될
  때 앱을 직접 열어 비밀번호를 변경하도록 안내한다. 문의 시 비밀을 보내지 말라는 문구도
  포함했다. `SPEC.md` §9.4와 실제 백엔드 템플릿을 함께 수정했다.
- 근거: [Google의 미요청 비밀번호 찾기 메일 안내](https://support.google.com/accounts/answer/27446?hl=ko),
  [OWASP 비밀번호 재설정 지침](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html).
  메일 요청 자체를 계정 침해로 단정하지 않으며, 유효한 코드·토큰 확인 전에는 가입·비밀번호
  변경을 완료하지 않는 기존 구현을 확인했다. 인증 방식·HTTP API·DB 계약은 변경하지 않았다.
- JDK 21에서 `backend/gradlew.bat test bootJar --offline --console=plain`을 실행했다.
  `bootJar`는 생성에 성공했다. 전체 테스트는 359개 중 170개가 실패해 **명령 전체는 실패**다.
  실패한 32개 클래스의 결과는 Docker 환경 초기화 실패와 연결되며,
  `dockerDesktopLinuxEngine` 파이프가 없어 PostgreSQL Testcontainers를 시작하지 못했다.
  Docker Desktop 시작 명령은 이미 실행 중이라고 응답했으나 엔진 연결은 복구되지 않았다.
- 같은 전체 실행에서 `SpringMailVerificationMailSenderTest`의 기존 `@Test` 5개는 모두
  통과했다. SMTP mock 테스트이며 실제 수신함 검증은 아니다. 변경 안내 9줄이 회신 초안과
  일치하는 것도 대조했다. 문구 변경만으로 새 테스트나 복제형 본문 테스트는 추가하지 않았다.
- 통합 테스트 통과·운영 배포·실제 수정 메일 수신은 미완료다. AWS 회신은 이후 사용자 요청으로
  2026-09-06 14:27:02 KST에 제출했다.

## 2026-09-07 최신 develop에서 재검증

- 기준 `develop`은 `398d7dbb`; 기존 SPEC·메일 안내 미커밋 수정은 보존했다. JDK 21에서 `gradlew.bat test bootJar --offline --no-daemon --console=plain` 실행 결과 **429개 중 173개 실패, 명령 실패**다. Docker Desktop 시작을 시도해도 `dockerDesktopLinuxEngine` 파이프가 없어 PostgreSQL Testcontainers 초기화가 실패했다. 전체 통합 테스트 통과로 보고하지 않는다.
- 이어서 `gradlew.bat test --tests '*SpringMailVerificationMailSenderTest' bootJar --offline --no-daemon --console=plain`을 실행해 **메일 단위 테스트 5개 통과, bootJar 성공(최신 출력 유지)**을 확인했다. 전체 실행의 집계는 `.cache/backend-full-test-summary-20260907.json`, 두 실행 로그는 `.cache/backend-revalidation-20260907.log`·`.cache/backend-mail-bootjar-20260907.log`에 남겼다.
- 원격 서버·진행 중인 4GiB 시험은 변경하지 않았다. SES 승인·변경 템플릿의 실제 수신·서버 배포는 이번 로컬 검증으로 완료되지 않는다.
