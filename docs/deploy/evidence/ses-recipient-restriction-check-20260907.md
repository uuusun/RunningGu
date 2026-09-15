# 가입 인증메일 수신 제한 재확인 — 2026-09-07

## 확인한 사실

- [09-04 SMTP 설정 기록](ses-smtp-staging-20260904.md): 서울 SES 샌드박스 유지, 인증된 시험 수신 주소 2개를 사용했다. IAM 사용자 `runninggu-staging-smtp`의 인라인 정책 `runninggu-staging-send-only`는 `ses:SendRawEmail`, 발신 주소, 수신 주소 두 개, 발신·수신 identity Resource를 제한했다. 당시 Resource 누락을 보완한 뒤 두 주소 모두 API 204와 실제 수신을 확인했다.
- [09-05 SES 신청 기록](ses-production-access-20260905.md): 프로덕션 액세스 신청 직후 `Under review` 확인. 09-06 14:27 KST에 AWS 추가 정보 요청에 회신했다. 확인한 자료에는 그 이후의 승인 완료 기록이 없다. **현재도 미승인이라고 실시간 확인한 것은 아니다.**
- 운영 Gmail은 SNS 반송·신고 알림 수신 구독을 확인했다. 이 사실은 해당 주소의 SES email identity 인증 또는 SMTP 수신 허용을 증명하지 않는다.
- 에뮬레이터 앱 화면에는 `인증 메일을 보내지 못했어요. 다시 시도해 주세요.`가 표시됐다. 입력 필드·비밀번호·인증 코드는 출력하지 않았다. 앱 로그에서 구체적인 HTTP 오류 코드를 얻지는 못했다.
- 2026-09-07 06:11:06 UTC에 공개 `GET /api/contests?size=1` 응답은 200이었다. 서버 연결 성공을 SMTP 발송 성공으로 해석하지 않는다.

## 제한은 두 곳에서 확인한다

| 제한 | 확인할 것 | 의미 |
|---|---|---|
| SES 샌드박스 | 서울 리전 `ProductionAccessEnabled`, 해당 수신 email identity 인증 상태 | 샌드박스에서는 검증된 수신 주소·도메인 등에만 발송 가능 |
| SMTP IAM 정책 | `runninggu-staging-send-only`의 `ses:Recipients`, Resource, 관련 Deny 등 | SES 프로덕션 승인이 나더라도 IAM의 별도 수신 제한은 자동으로 풀리지 않음 |

근거: [AWS SES 샌드박스](https://docs.aws.amazon.com/ses/latest/dg/request-production-access.html), [SES IAM 수신자 제한](https://docs.aws.amazon.com/ses/latest/dg/control-user-access.html). 09-07 공식 문서를 확인했다.

현재 자료상 수신 제한이 이번 발송 실패의 우선 확인 대상이다. 실제 SMTP 오류·현재 정책을 읽지 못했으므로 실패 원인으로 확정하지 않는다. 새 Gmail을 만들면 해결된다는 근거도 없다. 사용할 수신 주소의 인증·발송 허용부터 확인해야 한다.

## 실제 AWS 상태는 아직 미확인

브라우저 도구를 초기화해 재시도했으나 `failed to write kernel assets: 지정된 경로를 찾을 수 없습니다. (os error 3)`가 반복됐다. 현재 호스트에 AWS CLI·환경 자격 증명·설정된 AWS profile도 없어 인증된 AWS 조회를 수행하지 못했다. 인증정보를 브라우저에서 추출하거나 다른 방법으로 우회하지 않았다.

이전 턴에서 시도한 진단용 `POST /auth/email/send-code`는 자동 승인 검토가 실행 전에 거절했다. 실제 메일 발송·인증 상태 변경을 수반한다는 이유였다. 이후 사용자의 현재 요청 범위는 승인 상태·등록 수신자 확인이므로 **재발송하지 않았으며** IAM 정책·SES identity·애플리케이션 설정도 변경하지 않았다.

## AWS 연결 복구 후 조회할 항목

1. SES 서울 리전 계정의 프로덕션 승인 여부·발송 활성 상태.
2. 심사용 주소 `runninggu.play@gmail.com`의 email identity 등록·Verified 상태.
3. 기존 SMTP IAM 정책이 그 주소와 필요한 identity를 허용하는지. 광범위한 수신자 개방 대신 심사용 주소만 추가하는 방식으로 검토한다.
4. 필요한 변경을 확인한 뒤 계정 소유자의 수신자 확인 절차와 앱 발송 검증을 진행한다. 기존에 인증된 시험 주소를 사용하는 대안도 있지만, 개인 테스트 데이터를 심사용으로 넘기지는 않는다.

## 같은 날 후속 요청: 심사용 주소 등록 지시

- 사용자가 심사용 Gmail을 테스트 수신 주소로 등록하도록 명시적으로 요청했다. 이 요청은 해당 주소의 SES identity 등록과 기존 SMTP 허용 목록에 해당 주소를 추가하는 범위다. 기존 발신·수신 제한은 유지한다.
- 브라우저 도구의 `cua.getState()`와 computer-use의 `@oai/sky` 초기화가 모두 `failed to write kernel assets: 지정된 경로를 찾을 수 없습니다. (os error 3)`로 실패했다. 초기화 실패로 AWS 화면은 읽거나 조작하지 못했다.
- 현재 사용자 프로필의 AWS 설정·자격 증명 파일, AWS CLI 실행 파일, AWS 인증 관련 환경변수의 존재 여부를 확인했으나 직접 실행할 연결은 없었다. 비밀값은 읽거나 출력하지 않았다.
- **AWS 등록 명령·SMTP 정책 변경·확인 메일 발송은 실행하지 못했다.** 이번 차단은 자동 승인 검토의 거절이 아니라 도구 연결 실패다. 과거 발송 진단 거절과 구분한다.
- gitignore 대상 `.cache/ses-test-recipient-commands-20260907.md`에 CloudShell에서 기존 등록 상태를 먼저 조회하고, 미등록일 때만 등록하는 명령과 IAM의 두 추가 항목을 정리했다. 원격 실행·수신·등록 완료 검증은 남아 있다.
