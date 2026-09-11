# 약관 활성화 준비 검증 — 2026-09-11

## 범위

PR #278의 출시 소스 활성값과 공개 후보 문안, 선행 구현, 운영 전환 순서를 대조했다. 이 기록의
`활성값`은 앱·서버 **소스 계약**을 뜻한다. 운영 서버 배포와 스토어 앱 공개는 아래 후속 단계가
끝나기 전까지 완료로 계산하지 않는다.

## 소스·문서 계약

| 대상 | 확인값 |
|---|---|
| Android `AgreementDoc` | `TOS=1.1`, `PRIVACY=1.2`, `MARKETING=1.1` |
| 서버 `runninggu.auth.agreements.*-version` | `TOS=1.1`, `PRIVACY=1.2`, `MARKETING=1.1` |
| 앱 번들 파일 | `v1.1/tos.md`, `v1.2/privacy.md`, `v1.1/marketing.md` |
| 현재 계약 문서 | SPEC, API 명세, 화면–API 매핑표, ERD·DFD 검증 리포트, 출시 가이드, APK 검증 문서를 같은 값으로 수정 |
| 과거 증거 | 당시 활성값 1.0을 기록한 시점 증거는 수정하지 않음 |

소스 설정과 문서의 현재형 1.0 문구를 정해진 대상에서 검색해 0건임을 확인했다. 공개 후보
세 파일의 내부 표시 검색 결과는 Resend 주소의 `Market Street #5039` 한 건뿐이며, 주소 호수
회귀 테스트가 이를 허용하고 한글·괄호·줄 시작 이슈 참조는 계속 차단한다.

## 선행 조건

- 가입 연령 확인은 서버 #263과 앱 #264에 반영됐다. A2 별도 필수 체크박스, EMAIL·KAKAO
  `ageOver14`, `CODE_EXPIRED → mustResend`를 자동 테스트가 고정한다.
- 인증·세션 보존과 정리는 #240에 반영됐다. 운영 pgBackRest 전체 백업·WAL·실패 감시와
  별도 볼륨 복원은 #331·#336에 기록돼 있다. 2026-09-11 합성 사용자를 사용한 격리
  리허설에서 백업 뒤 탈퇴, 즉시 복원, 최신 WAL 복원을 차례로 수행했고 최신 복원에서
  사용자·동의·저장 코스가 모두 0건임을 확인했다
  ([복구 리허설 기록](withdrawal-recovery-drill-20260911.md)).
- 로그 개인정보 보호는 #337의 staging·production 실서버 검사에서 통과했다.
- EMAIL 가입·탈퇴·재가입과 KAKAO 탈퇴·재가입 거부 및 DB 삭제는 #241, 실제 받은편지함
  비밀번호 재설정과 새 비밀번호 로그인은 #338에 기록됐다.

## 위탁·국외 처리 사실 대조

- 2026-09-10 AWS Billing의 운영 계정 Payment Preferences에서 `Service provider`가
  `Amazon Web Services Korea LLC`임을 운영책임자가 확인했다. AWS는 계정의 실제 판매자를
  Billing의 Seller of Record에서 확인하도록 안내한다
  ([AWS 공식 안내](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/finding-the-seller-of-record.html)).
- Resend DPA의 계약 주체 `Plus Five Five, Inc.`, 주소 `2261 Market Street #5039`, 연락처
  `privacy@resend.com`을 개인정보 1.2와 대조했다
  ([Resend DPA](https://resend.com/legal/dpa)).
- 실제 `runninggu.store` 도메인은 도쿄 발송 리전을 사용한다. 발송 리전과 관계없이 계정
  메타데이터·로그·API 기록은 미국에 저장된다는 공식 안내를 문안에 반영했다
  ([Resend 리전 안내](https://resend.com/docs/dashboard/domains/regions)).
- 이메일 데이터 보유 기간 30일을 문안과 대조했다
  ([Resend 보유 안내](https://resend.com/docs/dashboard/webhooks/how-to-store-webhooks-data)).

외부 법률 검토는 완료하지 않았다. 2026-09-09 팀 결정에 따라 최초 출시 차단 조건에서는
제외하되, 완료했다고 표시하지 않고 README에 향후 검토 쟁점을 유지한다. 앱에 번들되는 공개
후보 세 문안에는 초안 안내·내부 이슈 번호·검토 상태 표시를 넣지 않는다.

## 검증

```text
Android  :app:testDebugUnitTest :app:assembleDebug
결과     BUILD SUCCESSFUL, 112개 클래스·930개 테스트, 실패·오류·건너뜀 0
APK      v1.1/tos.md, v1.2/privacy.md, v1.1/marketing.md 포함

Backend  bootJar
결과     BUILD SUCCESSFUL, 실행 JAR 생성
```

로컬 Docker Engine이 없어 Testcontainers 통합 테스트는 이 후속 문서 변경에서 다시 실행하지
못했다. PR #278 최신 head의 Unit/integration/bootJar CI 성공을 확인했다. 이번 후속 변경은
백엔드 실행 코드와 설정값을 바꾸지 않는다.

## 운영 활성화 순서

1. [완료] 격리된 탈퇴 전후 백업·WAL 복구 리허설을 완료하고 #227 조건을 닫았다.
2. PR #278이 병합된 커밋으로 최종 서명 APK를 만들고 A2에서 세 전문의 버전·문안을 확인한다.
3. 스토어 심사 통과 뒤 앱 공개를 보류한 상태에서 운영 서버를 같은 약관 버전으로 전환한다.
4. 운영 서버 버전을 확인한 뒤 앱을 공개한다.
5. 공개 앱 신규 가입의 `USER_AGREEMENT` 세 버전과 같은 `changedAt`을 확인한다.
