# 앱 API 부하 시험 사전 준비 — 2026-09-04

> 요청량·합격 기준은 운영책임자가 승인했다. **실제 30분 혼합 부하는 시작하지 않았다.**
> 인증 계정·메일 설정·외부 호출 상한·고정 요청 세트가 남아 있다. 4GiB 변경도 하지 않았다.

## 완료한 준비

- [계획](../api-load-test-plan.md)에 60 API 요청/분, warm-up 5분 + 본 시험 30분,
  최대 동시 진행 4개와 API 그룹별 합격 기준의 사용자 승인을 반영했다.
- 공개 조회만 최대 7회 실행하는 `scripts/api/prepare_api_load.py`와 단위 테스트를 추가했다.
  새 패키지·백엔드 API·DB 스키마·인증 우회·제품 timeout 변경은 없다.
- 실제 배포 source는 `1c3f2ef02514a2ab3efcb85bb8edb7196759579f` 그대로이며
  backend·GraphHopper·Nginx active, backend/GH `NRestarts=0`, `Result=success`를 확인했다.

## 실제 API 준비 검사 — 동일 결과 2회

```text
python scripts/api/prepare_api_load.py --year-month 2026-09 --probe-public
```

위 명령을 로컬 bundled Python으로 2회 실행했고 모두 exit 0, 아래 결과가 동일했다.
이 검사에는 부하용 동시 요청·POST/PUT/DELETE·외부 프록시 요청·메일 발송이 없다.

| 항목 | status | 확인 결과 |
|---|---:|---|
| 대회 목록 | 200 | 활성 항목 20개 |
| 목록의 첫 대회 상세 | 200 | 같은 ID·활성, 종목·일자·생성 기준 좌표 확인; 좌표 원문 출력 안 함 |
| 마감 임박 | 200 | 접수 중 항목 4개 |
| 2026-09 월간 일별 건수 | 200 | 일자 항목 6개 |
| 코스 지역 | 200 | 11개 지역 |
| 코스 목록 | 200 | 페이지 20개, 전체 261개와 지역별 합계 일치 |
| 토큰 없는 내 정보 | 401 | 의도한 인증 거부. 정상 부하 성공 응답으로 세지 않음 |

보고의 `scope=public_readiness_only`, `loadExecuted=false`, `fullRequestSetFrozen=false`를 확인했다.
결과 전체를 API 명세의 모든 필드 검증이나 성능 합격으로 확대하지 않는다.

## 도구 검증

```text
python -m unittest discover -s scripts/api -p 'test_*.py' -v
Ran 14 tests — OK
```

- 요청 범위·결정성·빈 fixture·잘못된 status·catalog 불일치·잘못된 데이터·출력 비밀값 차단을 검사했다.
- 전송은 GET·고정 staging 주소·인증정보 없음·10초 timeout·1MiB 응답 상한·JSON MIME 검사,
  리다이렉트 거부·네트워크 예외 메시지 미노출을 확인했다.
- **반례 검증:** 테스트 프로세스 메모리에서만 `require()`를 무력화하고
  `test_catalog_count_disagreement_fails`를 실행하면 실제로 1개 실패했다.
  `mutation_detected=True`를 확인했고 source 파일은 변조하지 않았다.
- backend 실행 코드를 바꾸지 않아 이 작업에서 backend `test bootJar`는 다시 실행하지 않았다.
  이전 merge CI 성공 기록과 이번 준비 도구 테스트를 혼동하지 않는다.

## 확인된 진행 장애

SSM `f9a2f83b-4b50-4f64-ac6d-a54f0bd78c71`, **Success / exit 0**, `07:19:35 UTC`:

```text
app_user_count=0
email_identity_count=0
kakao_identity_count=0
```

`BEGIN READ ONLY` 안에서 집계만 조회했다. 이메일·닉네임·비밀번호 해시·토큰 원문은 조회하지 않았다.
따라서 현재 DB에는 재사용 가능한 테스트 계정이 없다.

직전 배포 후 확인한 `MAIL_ENABLED=false`와 빈 SMTP host/username/password에 더해,
이번에는 서울 리전의 SES 상태를 읽기 전용으로 확인했다.

- `SendingEnabled=true`, `ProductionAccessEnabled=false`.
- 계정 API의 현시점 quota: 24시간 최대 200, 초당 최대 1, 지난 24시간 발송 0.
  이 수치는 실제 앱 발송 검증 결과나 메일 발송 승인이 아니다.
- `get-email-identity runninggu.store`: **NotFoundException**. 서울 리전에 해당 발신 도메인
  identity가 없다. 다른 identity 전체가 없다고 추정하지 않는다.
- 조회만 했으며 SES identity 생성·production access 신청·유료 플랜 전환·SMTP 자격 증명 생성·
  DNS 변경·회원 생성은 하지 않았다.
- 최초 결과 조회 때 기존 터미널의 입력과 섞여 조회 명령이 실패했다. 기존 입력을 지우지 않고
  별도 터미널을 연 뒤 위 SSM ID로 성공 결과를 재조회했다. EC2 점검 명령 자체는 성공했다.

## 다음에 필요한 입력·승인

1. 기존 배포 가이드의 SES 방식을 따라 **발신 도메인/DNS 인증·SMTP 설정**을 진행할 권한.
   계정 플랜 변경이나 SES production access 신청은 이번 준비에 포함하지 않는다.
2. 정상 가입·수신 확인에 사용할 **테스트용 이메일 주소 2개**와 해당 계정/생성 데이터 사용 동의.
   실제 개인 계정 비밀번호를 채팅으로 받거나 DB에 회원을 직접 삽입하지 않는다.
3. KTO·카카오의 허용 호출 상한과 잔여 할당량 확인. 값 존재만 확인한 키를 검증 완료로 세지 않는다.

이 조건을 확정한 뒤 정상 가입·외부 연동 사전 확인·고정 성공 요청 세트와 실제 부하 실행 도구를
완성한다. 공개 GET만 반복하는 것으로 승인된 혼합 비율의 인증·쓰기·라우팅 항목을 대체하지 않는다.
새 문서/도구의 commit·push·PR 생성은 아직 하지 않았다. EC2는 실행 중으로 유지했다.

## 후속 메일 설정 기록

위는 메일 설정 **전** 점검 상태다. 이후 사용자 승인으로 SES 도메인·수신 identity 인증,
가비아 DKIM CNAME, EC2 SMTP 설정을 적용했다. 최초 발송의 수신 identity IAM 권한 누락을 승인 후
보완했고, 동일 앱 API 재검증에서 두 주소 모두 204·두 메일함 도착을 확인했다.
설정 결과와 남은 인증/가입 검증은
[SES·SMTP 설정 기록](ses-smtp-staging-20260904.md)을 따른다. 회원 가입·전체 부하는 실행하지 않았다.
