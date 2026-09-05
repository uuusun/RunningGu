# 정상 가입·로그인 사전 점검 — 2026-09-04

> **최초 사전 점검 당시에는 계정 생성·코드 재발송·로그인 시험을 실행하지 않았다.**
> 후속 가입·재로그인은 확인했다. 계정 관리의 [마케팅 상태 복원 결함](marketing-relogin-diagnosis-20260904.md)은 미수정이다.
> 최초 점검 당시에는 배포 기준 `develop`에 필수 연령 확인 구현이 없어 PR 반영을 요청했다.
> **후속 #263 머지·staging 배포로 그 차단 조건은 해소됐다.** 최초 판정은 source 대조이며,
> 새 버전의 실제 연령 입력 거부 시험은 [후속 배포 기록](staging-develop-b9e632d-20260904.md)에 구분했다.

## 후속 상태

- #263은 `2026-09-04T08:54:23Z` 머지됐으며 `b9e632d768c99f05f4028545b7e265817038d9c8`의
  develop push CI 성공 묶음을 staging에 배포했다. SMTP·DB·GraphHopper·메모리 설정은 보존했다.
- 연령 입력 누락/null/문자열/숫자 거부와 false의 `AGE_REQUIREMENT_NOT_MET`를 실제 HTTPS에서 확인했다.
- 사용자 승인 후 기존 release 설정으로 staging 주소를 넣은
  [내부 검증 APK](android-staging-release-20260904.md)를 빌드·설치했다. 사용자가 직접 가입을 완료했고
  09:51 UTC 서버 집계로 인증 200·가입 201·첫 계정 생성을 확인했다. 후속 로그아웃·재로그인과
  내 정보 화면 표시 보고는 확인했으나 GET `/me` 호출이 없고 마케팅 표시 불일치가 있어 전체 완료는 아니다.
- 아래 본문은 **머지 전 최초 점검 시점의 이력**이며 현재 PR이 여전히 Draft라는 의미가 아니다.

## 요청 범위

SES·DNS·SMTP 설정과 두 메일함 수신을 확인한 뒤, 운영책임자가 테스트 계정 2개의 정상 가입·로그인
검증 진행을 요청했다. 비밀번호·인증 코드를 채팅으로 받거나 DB 직접 삽입으로 가입을 대체하지 않는다.
다른 PR의 머지·추가 배포는 별도 승인 없이 수행하지 않는다.

## 명세와 배포 source 비교

- `SPEC.md` §4.2·결정-58, API 명세 §1-5·§1-8, 화면/API 매핑표 §10 D-34는 이메일·카카오
  가입 요청 최상위의 필수 `ageOver14`를 확정했다. 누락은 `400 VALIDATION_FAILED`, false는
  `400 AGE_REQUIREMENT_NOT_MET`이며 회원·토큰 생성 전에 거부한다.
- 배포 기록의 source와 현재 GitHub `develop`은 모두
  `1c3f2ef02514a2ab3efcb85bb8edb7196759579f`였다. 이번 턴에서 EC2 source를 별도 재조회한 것은 아니다.
- 이 source의 `SignupRequest`는 email·password·nickname·agreements만 받는다. backend main/test에서
  `ageOver14`·`AGE_REQUIREMENT_NOT_MET` 구현을 찾지 못했다. signup controller/service에도
  연령 확인값을 전달하는 경로가 없다.
- 따라서 정상 가입이 반드시 실패한다는 의미가 아니다. 기존 source에서 가입이 가능하더라도
  필수 연령 확인을 포함한 **현재 계약의 정상·오류 흐름 검증 완료**로 판정할 수 없다.

## 관련 PR 상태 — GitHub 읽기 전용 확인

| 항목 | 점검 결과 |
|---|---|
| 서버 [#263](https://github.com/uuusun/RunningGu/pull/263) | OPEN, Draft, base develop, head `92ca9f3906181c625c90bcdaeb8f4eb6f9bec415` |
| #263 병합 가능성 | `MERGEABLE`, `mergeStateStatus=CLEAN` — 실제 머지하지 않음 |
| #263 리뷰 | 민지의 현재 head APPROVED 리뷰 존재. 집계 `reviewDecision`은 빈 값이므로 branch protection 충족을 별도로 단정하지 않음 |
| #263 CI | 2026-09-02 head의 Spec sync check와 Unit, integration, and bootJar 모두 SUCCESS. 현재 develop 통합 배포 시험은 아님 |
| 앱 [#264](https://github.com/uuusun/RunningGu/pull/264) | 2026-09-03 머지, commit `68d09d621b9b0c04165b00ef4a7cbf6cc6653e12`. 가입 DTO·요청 조립·별도 연령 체크 UI·오류 처리 포함 |
| 이슈 [#228](https://github.com/uuusun/RunningGu/issues/228) | OPEN. 본문 초안보다 확정된 SPEC/API 명세를 기준으로 판단 |

#263은 연령 검증 구현이며 약관 활성 버전 변경은 포함하지 않는다. 현재 활성 1.0과 다음
TOS 1.1·PRIVACY 1.2의 동시 활성화는 별도 범위다. 이번 준비에서 약관 버전을 임의 전환하지 않는다.

## 다음 순서와 미실행 범위

1. 사용자에게 #263 Draft 해제·머지와 새 develop CI 묶음의 staging 재배포 진행 여부를 확인한다.
   실제 실행 전 최신 리뷰·CI·head와 병합 조건을 다시 확인한다.
2. 승인된 배포 경로로 소스를 반영하되 SMTP·DB·JWT·GraphHopper·기존 메모리 설정을 보존한다.
3. 가입 약관/연령의 사용자 확인과 비공개 코드·비밀번호 입력 방법을 준비하고, 정상 verify → signup →
   login → 인증된 내 정보 조회를 수행한다. 자동으로 동의값을 만들어 가입하지 않는다.
4. 외부 API 상한·고정 입력·도구 준비를 마친 뒤 승인된 앱 부하 시험으로 이어간다.

새 회원·토큰 생성, 약관 동의, 추가 메일 발송, PR 변경·머지·배포·서버 설정 변경은 하지 않았다.
backend 구현을 변경하지 않아 `test bootJar`도 다시 실행하지 않았다. 기존 CI 결과와 구분한다.
로컬 네트워크 제한으로 첫 GitHub 조회가 실패해 허용된 환경에서 재조회했으며, 서버 장애로 집계하지 않는다.
