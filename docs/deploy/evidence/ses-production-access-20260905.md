# 2026-09-05 SES 프로덕션 액세스 신청 제출·알림 검증

## 사용자 요청과 신청 내용

사용자가 서비스 소개 페이지 작업을 보류하고 기존 주소·발신 정보로 SES 프로덕션 액세스
신청을 진행하도록 요청했다. 이후 사용자가 AWS Service Terms·Acceptable Use Policy 동의와
최종 제출을 승인하여 2026-09-05 22:33 KST에 AWS 콘솔의 서울 리전에서 아래 값으로 제출했다.

| 항목 | 내용 |
|---|---|
| 리전 | Asia Pacific (Seoul), `ap-northeast-2` |
| Mail type | Transactional |
| Website URL | `https://runninggu.store` |
| Additional contacts | `runninggu.play@gmail.com` |
| Preferred contact language | English |
| 사용할 발신 주소 | `no-reply@runninggu.store` — 신청서에 별도 발신 주소 입력란은 없으며 아래 콘솔 모의 발송에서 검증 |
| 신청 상태 | **제출 완료, Under review(심사 중)** — 2026-09-05 22:33 KST 콘솔 확인 |

콘솔에 `Successfully submitted request for production access.` 성공 알림과
`Status: Under review`, Request production access의 `In progress` 표시를 확인했다.
콘솔은 요청 내용 검토에 최대 24시간이 걸릴 수 있다고 안내한다. 승인 완료나 해당 시간 내
최종 승인을 보장하는 것으로 기록하지 않는다.

현재 콘솔에서 샌드박스 상태와 `runninggu.store` identity의 Verified 상태를 확인했다.
선택한 Website URL에 공개 소개 페이지가 연결됐다는 뜻은 아니다. 페이지 제작·DNS·호스팅은
이번 요청에서 진행하지 않았으며, 추가 자료 요청이 오면 실제 서비스 준비 상태에 맞춰 답한다.

## 반송·신고 알림 구성

- 서울 리전에 Standard SNS topic `runninggu-ses-feedback`을 SES 콘솔에서 생성했다.
- 도메인 identity의 Bounce·Complaint 알림을 해당 topic에 연결했다. 저장 성공과 저장된 값을
  확인했다. Delivery 알림과 원본 메일 헤더 포함은 선택하지 않았다.
- `runninggu.play@gmail.com`을 Email 구독자로 등록했고, 해당 Gmail로 도착한 AWS 구독 확인
  메일을 통해 `Subscription confirmed!`와 `You have successfully subscribed.`를 확인했다.
- 기존 Email feedback forwarding은 Enabled 상태다. 새 SNS 수신 경로를 별도로 검증했다.
- 계정 수준 Suppression list는 Enabled, Suppression reasons는 Bounce and complaints임을
  현재 콘솔에서 확인했다. 이 설정은 이번 작업에서 변경하지 않았다.

운영자는 Gmail의 반송·신고 알림을 확인하고 SES 발송 차단 목록과 원인을 대조한다.
영구 반송·신고 주소는 원인 확인 없이 차단 목록에서 제거하거나 반복 발송하지 않는다.
문제 증가 시 발송 원인과 요청 경로를 조사한다. 백엔드의 새 재시도 정책이나 사용자 DB 상태를
이번 운영 기록으로 추가하지 않는다.

## 실제 검증 결과

앱 서버를 거치지 않고 AWS SES 콘솔의 메일함 시뮬레이터에 각각 한 번 발송했다.
발신 주소는 두 테스트 모두 `no-reply@runninggu.store`다.

| 검증 | 실제 확인 |
|---|---|
| Bounce | 2026-09-05 22:26 KST, 운영 Gmail에서 `notificationType=Bounce`, `bounceType=Permanent`, 시뮬레이터 수신 주소를 확인 |
| Complaint | 2026-09-05 22:27 KST, 운영 Gmail에서 `notificationType=Complaint`, `complaintFeedbackType=abuse`, `Amazon SES Mailbox Simulator`를 확인 |

이 결과는 SES → SNS → 운영 Gmail의 실제 알림 전달 검증이다. 일반 사용자 가입 메일 수신,
백엔드 SMTP 환경변수 반영, 앱 회원가입 전체 흐름 검증을 완료했다는 뜻은 아니다.
시뮬레이터 메일은 발송 할당량과 반송·신고율에 포함되지 않는다. 또한 시뮬레이터 주소의
실제 차단 목록 등록까지 검증한 것으로 보고하지 않는다.

## 2026-09-06 추가 정보 요청에 회신

- 2026-09-05 22:33 KST에 AWS가 보낸 서비스 용도·발송 빈도·수신자 관리·반송/신고/수신거부
  처리·메일 예시 보완 요청을 확인했다. 승인·거절 통보가 아니라 추가 정보 요청이었다.
- 사용자가 수정한 영문 초안으로 회신하도록 요청하여 **2026-09-06 14:27:02 KST**에
  기존 Support Center 문의의 Web 회신으로 제출했다. Correspondence에 발신자·시각·전체
  본문이 추가된 것을 확인했다. 첨부파일이나 새 문의는 만들지 않았다.
- 서비스 소개와 거래성 메일 용도, 실제 SNS 알림 검증, 미요청 수신자 행동 안내를 보강한
  메일 예시를 포함했다. 소개 페이지 준비 중·수정 문구 운영 미배포·정량 발송량 미확정 상태를
  그대로 설명했다. [제출 본문·검증 범위](../ses-production-access-reply-20260906.md)를 따른다.

## 남은 단계

1. 추가 설명 회신 이후의 AWS 심사 결과 또는 후속 정보 요청 확인. 아직 프로덕션 액세스
   승인은 확인하지 않았다. 최초 제출 직후 확인한 샌드박스 제한은 24시간 200통·초당 1통과
   사전 인증 수신자 제한이다.
2. 승인 후 SES에 미리 인증하지 않은 수신 주소로 실제 가입 인증·비밀번호 재설정 검증.

근거: [SES 프로덕션 액세스 신청](https://docs.aws.amazon.com/ses/latest/dg/request-production-access.html),
[SNS 알림 구성](https://docs.aws.amazon.com/ses/latest/dg/configure-sns-notifications.html),
[메일함 시뮬레이터](https://docs.aws.amazon.com/ses/latest/dg/send-an-email-from-console.html).
