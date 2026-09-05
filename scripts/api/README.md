# 앱 API 시험 준비 도구

기준은 [앱 API 부하 시험 계획](../../docs/deploy/api-load-test-plan.md)이다.
공개 조회 준비 검사, 오프라인 도착 시간표 작성기와 30분 혼합 부하 실행기가 있다.
고정 요청 세트는 운영책임자가 2026-09-05 승인해 `APPROVED`로 고정했다. 같은 변경의 PR 검토와
staging 외부 호출 가드 배포·활성화·사전 점검이 끝나기 전에는 실제 부하를 실행하지 않는다.

## 실행

Python 표준 라이브러리만 사용한다. 명령은 저장소 root 기준이다.

```bash
python3 -m unittest discover -s scripts/api -p 'test_*.py' -v
python3 scripts/api/prepare_api_load.py --year-month 2026-09 --probe-public
```

`--probe-public`을 명시해야 `https://staging-api.runninggu.store`에 최대 7개 GET을 실행한다.
호스트 변경·인증정보 전달·쓰기·재시도·리다이렉트 추적 옵션은 없다. TLS 기본 검증을 유지한다.

| 검사 | 최소 확인 |
|---|---|
| 대회 목록 | 200, 활성 대회와 숫자 ID, 게스트 `favorite=false` |
| 첫 대회 상세 | 200, ID/활성 여부 일치, 종목·일자·유효 좌표 존재 |
| 마감 임박 | 200, 최대 4개·접수 중·게스트 찜 없음 |
| 월간 건수 | 200, 지정 월의 양수 건수 |
| 코스 지역 | 200, 지역별 양수 건수 |
| 코스 목록 | 200, curated 원천·출처 표시·지역별 합계와 전체 수 일치 |
| 미인증 내 정보 | **401이어야 통과**, 200이면 인증 경계 실패 |

정상적으로 빈 결과를 반환하는 API라도 이 도구에서는 `empty_fixture`로 표시한다. 이는 빈 결과의
제품 계약이 잘못됐다는 뜻이 아니라 **성공 내용이 있는 부하 입력을 아직 확보하지 못했다는 뜻**이다.
처음 조회된 대회를 확인하지만 ID를 자동으로 운영 부하 세트에 승격하지 않는다.

stdout JSON은 이 도구 내부의 준비 보고이며 backend가 소비하는 파일 계약이 아니다.
7개 `caseId`의 status·통과 여부·건수·고정 오류 분류만 남긴다. 응답 원문·사용자 정보·좌표·토큰·
예외 메시지는 출력하지 않는다. `loadExecuted=false`, `fullRequestSetFrozen=false`는 항상 유지한다.
이 최소 검사를 HTTP 계약 전체·전체 endpoint 스모크·라우팅 품질·성능 합격으로 해석하지 않는다.

2026-09-04에는 단위 테스트 14개와 실제 staging 점검 2회를 수행했다.
증거는 [사전 점검 기록](../../docs/deploy/evidence/api-load-preparation-20260904.md)에 남긴다.

## 승인된 혼합 부하 실행기 검증

```bash
python3 scripts/api/run_api_load.py \
  --fixture scripts/api/fixtures/staging-api-load-v1.approved.json
```

이 명령은 fixture 계약·hash와 2,100건 시간표만 검증하고 HTTP·로그인·쓰기를 실행하지 않는다.
`approvalStatus=APPROVED`, `loadExecuted=false`, `readyForLoad=true`와 요청 세트 SHA-256
`6811484a70d406e555c9bdce8273744ef5be597795a73186c9ed9ea42a818ef1`이 정상 결과다.
실행기는 `CANDIDATE` 값을 `--execute`와 함께 받으면 비공개 입력·네트워크 전에
`request_set_not_approved`로 거부한다. 승인 파일을 결과에 맞춰 고쳐 같은 시험으로 취급하지 않는다.

실제 실행은 승인된 fixture와 실행기가 PR 검토·머지되고, EC2에서
[runbook §15.3](../../docs/deploy/aws-ec2-staging-runbook.md#153-staging-앱-api-부하-시험의-외부-호출-가드)의
가드 활성화·preflight를 통과한 뒤에만 한다. 실행 시 두 전용 계정의 이메일·비밀번호를 터미널
숨김 입력으로 받으며 파일·명령행·결과 JSON에 저장하지 않는다.

```bash
python3 scripts/api/run_api_load.py \
  --fixture scripts/api/fixtures/staging-api-load-v1.approved.json \
  --execute \
  --run-id '<가드와 동일한_run_id>'
```

실행기는 로그인과 계정 분리 확인, 14개 정상 preflight, 부하 직전·20분 시점의 두 계정 토큰 갱신,
5분 준비 + 30분 본 시험, 즐겨찾기 정리·로그아웃을 수행한다. 분당 60건의 고정 도착 시각을
응답 완료에 맞춰 늦추지 않고 동시 진행 4개가 모두 차 있으면 `missed_start`로 실패한다.
응답 원문·토큰·이메일·비밀번호·검색어·좌표는 결과에 남기지 않는다.

결과 JSON의 요청 수·실패 분류·그룹별 p50/p95/max와 curated·OSM·동선 생성 각각의
p50/p95/max·응답 byte·실행기 CPU를 보존한다. 이 값만으로
부하 생성기 NIC 비포화를 증명하지는 않는다. EC2 5초 자원 표본, 백업·WAL, 가드 journal 최종
요약과 부하 생성기 OS의 네트워크 표본을 같은 run 증거에 함께 남겨야 한다.

## 읽기 전용 계약 경계 검사

```bash
python3 scripts/api/probe_readonly_boundaries.py --probe-staging
```

최대 14개 GET으로 미인증 개인 조회 4종의 401, 코스 페이지·크기 및 마감임박 limit 위반의
400, 대회 cursor 다음 페이지, 지역 정렬·앞뒤 공백 필터, 마지막 코스 페이지 이후의 빈 200을
확인한다. 빈 200은 이 명시적 경계 시험에서만 정상이며 부하 성공 입력으로 승격하지 않는다.
기존 고정 HTTPS transport를 사용하며 인증값·응답 원문·사용자 좌표를 출력하지 않는다.

마케팅 재로그인 표시 결함 [#287](https://github.com/uuusun/RunningGu/issues/287)은 별도 미해결이다.
이 도구는 해당 설정을 변경하거나 재시험하지 않으며 인증된 GET `/me`의 성공도 증명하지 않는다.
외부 프록시·GraphHopper·쓰기·30분 부하·고정 요청 세트 확정은 포함하지 않는다.

## 오프라인 도착 시간표

```bash
python3 scripts/api/plan_api_arrivals.py
python3 scripts/api/plan_api_arrivals.py --include-schedule
```

네트워크·서버 변경·로그인·API 쓰기를 전혀 실행하지 않는다. 승인된 §4 비율에 따라
준비 부하 5분 300건 + 본 시험 30분 1,800건의 예정 시각을 1초 간격으로 만든다.
분마다 16개 세부 항목의 수량을 유지하고 항목을 섞는다. 시간표 자체에는 실제 실행 시각·응답
시간이 없으며 `maxInFlight=4`는 실행기가 집행한다.

각 분의 `favorite_add`는 10초, `favorite_delete`는 50초 슬롯에 두고 삭제에는 같은 분의 선행
추가 sequence를 연결한다. 정상 최대 응답시간 3초보다 충분히 떨어뜨려, 합격 범위의 느린 추가가
끝나기 전에 삭제가 도착하는 시간표 자체의 경합을 만들지 않는다.
혼합 부하 실행기는 추가 성공과 같은 계정 의존성을 확인하고, 선행 실패/미완료 시 삭제를 실행하지
않고 전체 시험을 실패시킨다. 시간표 작성기 자체에는 회원·대회 ID나 토큰이 없다.
로그인·토큰 갱신 등 별도 요청도 이 2,100건에 숨겨 넣지 않는다.

`scheduleSha256`은 순서·시각·의존성을 담은 **내부 시간표**의 동일성만 나타낸다.
실제 HTTP 입력·내용 기대값·외부 API 예산을 고정한 전체 요청 세트의 hash가 아니다.
`readyForLoad=false`, `loadExecuted=false`, `fullRequestSetFrozen=false`를 항상 반환한다.

2026-09-05의 테스트·동일성 결과와 외부 호출 경계는
[후속 준비 기록](../../docs/deploy/evidence/api-load-upstream-preparation-20260905.md)에 남긴다.
