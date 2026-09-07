# 앱 API 시험 준비 도구

2026-09-07 공공데이터·동선 생성 245건 보완 시험은
[보완 검증 기준](../../docs/deploy/staging-4g-kto-supplement-plan.md)을 따른다.
`run_api_kto_supplement.py`로 원래 슬롯 245건만 선택하고 서버 가드는 기본 v3를 쓴다.
이 실행은 로그인 없이 진행하며 서버 제어기에 `--no-kto`를 지정하지 않는다.
실제 245건은 모두 성공했고 [API·자원·복귀 결과](../../docs/deploy/evidence/api-load-ec2-4g-20260907-kto-supplement.md)를 남겼다.

앞선 1,855건 부분 부하는 [공공데이터 제외 기준](../../docs/deploy/staging-4g-no-kto-plan.md)을 따른다.
`run_api_capacity.py`와 `start_capacity_interactive.py`의 `--exclude-kto`는 축제·POI·동선 생성
245건을 제외해 기존 시각의 1,855건을 선택한다. 서버 제어기는 `--no-kto`를 사용한다.
`subsetChecksPassed`는 선택한 요청의 기계 점검이며 `fullAppLoadPassed`는 false다.
최종 RAM 판단은 API 결과와 자원 증거를 함께 분석해 문서로 남긴다.

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

2026-09-06에는 미커밋 도구 보완·검증과 실제 1회 시험을 사용자가 직접 승인했다.
실제 실행은 승인된 fixture·검증된 실행기와 EC2에서
[runbook §15.3](../../docs/deploy/aws-ec2-staging-runbook.md#153-staging-앱-api-부하-시험의-외부-호출-가드)의
가드 활성화·preflight를 통과한 뒤에만 한다. 실행 시 두 전용 계정의 이메일·비밀번호를 터미널
숨김 입력으로 받으며 파일·명령행·결과 JSON에 저장하지 않는다.

```bash
python3 scripts/api/run_api_load.py \
  --fixture scripts/api/fixtures/staging-api-load-v1.approved.json \
  --execute \
  --run-id '<가드와 동일한_run_id>' \
  --output '<새_결과_경로>' --guard-evidence '<확인한_gate_JSON>'
```

실행기는 로그인과 계정 분리 확인, 14개 정상 preflight, 부하 직전·20분 시점의 두 계정 토큰 갱신,
5분 준비 + 30분 본 시험, 즐겨찾기 정리·로그아웃을 수행한다. 분당 60건의 고정 도착 시각을
응답 완료에 맞춰 늦추지 않는다. HTTP 송신 직전이 예정 시각보다 500ms 초과 늦거나 동시 진행
4개가 모두 차 있으면 그 요청을 뒤늦게 전송하지 않고 `missed_start`로 실패한다.
응답 원문·토큰·이메일·비밀번호·검색어·좌표는 결과에 남기지 않는다.

결과 JSON의 요청 수·실패 분류·그룹별 p50/p95/max와 curated·OSM·동선 생성 각각의
p50/p95/max·응답 byte·실행기 CPU, `dispatchDelayLimitMs`·`maxDispatchDelayMs`·
`lateDispatches`를 보존한다. 이 값만으로
부하 생성기 NIC 비포화를 증명하지는 않는다. EC2 5초 자원 표본, 백업·WAL, 가드 journal 최종
요약과 부하 생성기 OS의 네트워크 표본을 같은 run 증거에 함께 남겨야 한다.

## 4GiB 기본 운영 소량 점검

`run_api_smoke.py`는 [기본 운영 점검 계획](../../docs/deploy/staging-4g-closeout-plan.md)에 따른
별도 실행 경로다. 승인 fixture의 14종을 순차로 각 1회 호출하고, 테스트 계정 A/B의 전용
즐겨찾기 추가·조회·삭제와 소유권 분리를 확인한다. 2,100건 부하 스케줄은 실행하지 않는다.

```bash
python3 scripts/api/run_api_smoke.py \
  --fixture scripts/api/fixtures/staging-api-load-v1.approved.json

python3 scripts/api/run_api_smoke.py \
  --fixture scripts/api/fixtures/staging-api-load-v1.approved.json \
  --execute --run-id '<가드와 동일한_run_id>' \
  --guard-evidence '<실측으로 확인한_gate.json>' --output '<새_결과_디렉터리>'
```

첫 명령은 오프라인 검사만 한다. 실제 실행의 gate는 실행자가 서버에서 가드 preflight,
실제 송신 계측과 자원 수집 시작을 확인한 뒤 만든다. `runId`, `guardPreflightPassed=true`,
`actualSendingVerified=true`, `metricsStarted=true`, timezone을 포함한 미래 `expiresAt`이
필요하다. 이 파일은 운영 확인 기록이며 서버가 발급한 인증서나 가드 활성화 수단이 아니다.
기존 fixture·외부 공급자 계약은 변경하지 않는다.

두 계정 비밀값은 숨김 입력으로 받고 요청마다 기대/실제 status·시간·고정 오류 분류를
`api-smoke-summary.json`에 즉시 저장한다. 완료한 14종의 내용 검사는 `completedCases`,
계정 분리는 `accountIsolationPassed`로 확인한다. 로그인·소유권 확인·정리·로그아웃은
14종과 구분한 요청 행으로 남긴다. 오류·중단 때도 부분 결과와 정리 결과를 보존한다.
전용 대상이 이미 즐겨찾기에 있으면 변경하기 전에 멈추며 기존 데이터는 삭제하지 않는다.
응답 원문·사용자 ID·좌표·이메일·토큰·비밀번호는 저장하지 않는다.

느린 성공 응답은 진단 항목이며 나머지 점검을 중단하지 않는다. 통신·공급자·응답 계약 오류는
자동 재시도하지 않는다. 운영자가 원인을 분류하고 필요한 읽기만 최대 1회 별도로 재확인한다.
실행 결과의 `completed`는 소량 API 점검 완료이며 전체 부하·인프라 종합 합격이 아니다.
가드 해제·백엔드 재기동·HTTPS 복귀와 자원·백업 판정은 실행서에 따라 별도로 완료해야 한다.

## 전체 부하의 2026-09-06 실행 경로

### 2026-09-07 제외했던 245건 보완

`run_api_kto_supplement.py`는 [보완 계획](../../docs/deploy/staging-4g-kto-supplement-plan.md)의
축제 70·관광지 70·동선 생성 105건만 원래 35분 시간표로 실행한다. 세 API는 미인증 계약이며
로그인·토큰 갱신·즐겨찾기 변경이 없다. 기존 실행의 계정 비밀값을 재사용하지 않는다.

```text
python scripts/api/run_api_kto_supplement.py
python scripts/api/run_api_kto_supplement.py --execute --run-id <고유_run_ID> --output <새_run>/load --guard-evidence <새_run>/start-authorized.json
```

첫 명령은 오프라인 검사다. gate는 실제 서버에서 확인한 기존 `app-capacity-v3` 필드에
`executionProfile=app-capacity-v5-kto-only`를 추가한다. 서버 제어기에는 `--no-kto`를 사용하지
않으며 정상 코스 동기화·KTO 계측을 유지한다. 기존 도구 경로의 baseline/activate/metrics/finish를 사용한다.
가드 활성·실제 송신·40분 수집·45분 만료 복구 확인 전에는 실행하지 않는다.

종료·오류에도 `load-finished.json`, `partial-summary.json`, 요청별 JSONL과 최종 요약을 저장한다.
그 즉시 가드 OFF·backend 재기동·readiness·HTTPS를 확인한다. 계정 정리/로그아웃은 해당 없음으로
기록하며 성공했다고 꾸미지 않는다. 부분 시험의 `subsetChecksPassed`와 전체 시험의
`fullAppLoadPassed=false`를 구분한다. 가드 예외와 구분되지 않는 500은 보호 중단 후 원본을 확인한다.

### 기존 전체 부하 실행

`run_api_load.py --execute`는 `run_api_capacity.py`로 진입한다.
이전 `capacity-v2` 결과는 보존하며 이번 정책 이름은 `app-capacity-v3`다.
기준은 [앱 API 계획 §5.3](../../docs/deploy/api-load-test-plan.md#53-2026-09-06-승인-앱-api-1회-종합-판정)을 따른다.

```text
python scripts/api/run_api_load.py --fixture scripts/api/fixtures/staging-api-load-v1.approved.json
python scripts/api/start_capacity_interactive.py --run-id <고유_run_ID> --output <새_로컬_run_경로>
```

두 번째 명령은 사용자 전용 콘솔에서 이메일·앱 비밀번호 4개를 모두 숨김 입력한다.
입력 값은 메모리에만 유지하며 `input-ready.json`에는 완료 여부만 기록한다.
그 뒤 실제 서버 가드·수집·송신 계측·만료 복구를 확인한 운영자가 같은 run 경로에
`start-authorized.json`을 작성해야 로그인과 전체 부하를 시작한다. 파일은 서버 인증서나
자동 가드 활성화 수단이 아니다.
로그인 전 gate가 취소되면 `pre-start-summary.json`에 2,100건 미실행과 보조 요청 0건을 남긴다.
이때 dispatch 지연·응답시간은 측정하지 않았으므로 null/빈 결과이며 0ms로 채우지 않는다.

gate 필드는 `runId`, `acceptancePolicy=app-capacity-v3`,
`guardPreflightPassed/metricsStarted/actualSendingVerified/expiryRecoveryReady=true`,
UTC `expiresAt`다. 시작 시 남은 유효기간이 36분 미만이면 송신하지 않는다.
실제 시험에서는 만료 장치를 가드 변경 전에 45분으로 설치한다.
fixture·schedule canonical hash는 실행기 내 승인값과 일치해야 한다.

예정 요청은 `load/schedule.json`, 실제 송신·응답·내용 검사는
`load/request-events.jsonl`에 요청 ID와 UTC 시각으로 즉시 flush한다.
`load/partial-summary.json`과 `load/api-load-summary.json`은 중단 때도 남는다.
JSONL에 URL/query/body/응답 원문·비밀값·계정 ID·좌표는 기록하지 않는다.
강제 프로세스 종료로 최종 JSON이 없으면 JSONL과 시간표를 원자료로 분석하며,
종결 결과가 없는 송신은 성공·실패로 추정하지 않고 in-flight/결과 미확인으로 남긴다.

완료는 송신 후 HTTP 응답 또는 timeout 등의 종결 결과를 얻은 요청이다.
`counts` 및 `phases/groups/apiCases`에서 예정/송신/완료/성공/실패/미실행을 구분하며
보조 요청은 `auxiliaryRequests/auxiliaryCounts`에 따로 센다.
일반 오류·늦은 송신은 재시도하거나 뒤늦게 몰아 보내지 않는다.
500은 현 가드 예외와 구별되지 않으므로 신규 송신을 보호 중단하고 가드 journal을 확인한다.
이 경우도 자원 진단 자료는 계속 수집하고 RAM 부족으로 자동 판정하지 않는다.

정상·오류·사용자 중단 모두 `load-finished.json`으로 가드 해제를 요청한다.
담당 운영자는 즉시 `staging_capacity_control.py finish <run_ID>`를 SSM으로 실행하고
실제 프로세스 가드 OFF·readiness 결과를 `guard-off.json`으로 반환한다
(`runId/guardDisabled=true/readiness=true`). launcher는 이를 확인한 뒤 정리·logout을 실행한다.
통신 단절에는 서버 45분 만료 timer와 controller finally가 복구한다.
시작 동기화를 기다리는 동안에도 가드 이벤트를 확인하며 trip/non2xx가 생기면 동기화 완료를
기다리지 않고 정리한다. 2026-09-06 preflight 실패에서 확인한 준비 대기 지연을 보완한 것이다.
로컬에서 쓰는 `stop-requested.json`은 신규 송신만 중단하며 기존 요청은 종결 결과를 보존한다.

서버에는 검증 전용 경로에 controller·수집기·분석기만 설치하며 제품 JAR·graph는 재배포하지 않는다.
40분 수집의 정상 관측 구간과 가드 해제 후 계획 재기동을 구분한다.
자원 요약기는 반드시 `--policy app-capacity-v3`로 호출한다. 기본값 `capacity-v2`는 과거 재현용이다.
Full GC 횟수·swap 증가·MemAvailable 20% 미만은 v3의 단독 중단/탈락 조건이 아니다.
최종 판정에는 API·GC/heap·swap·CPU·서비스·백업/WAL과 복귀 상태를 함께 사용한다.

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
