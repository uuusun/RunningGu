# KTO 교체 후 앱 부하 준비 — 2026-09-05

> 30분 부하 시험이 아니라 **외부 호출 경계 점검·키 안전 재교체·오프라인 시간표 준비** 기록이다.
> 이 준비 기록 당시에는 30분 혼합 부하·계정 간 데이터 분리·4GiB 시험을 실행하지 않았다.
>
> **후속 상태:** PR #289 머지와 `673a2f7` 정식 develop CI artifact 배포를 완료했다.
> 가드 비활성 readiness·HTTPS 스모크와 실제 KTO 송신을 포함한 가드 preflight를 통과했다.
> 사용자 숨김 입력 완료 후 새 run의 8GiB 혼합 부하를 통과했다. 2,100건 전송·완료, 본 시험 1,800건 성공,
> 자원 480개 표본, backup 1회·WAL 3회 성공과 관측 중 단발 Full GC 1회·반복 없음도 기록했다.
> 가드 해제·backend 재기동·HTTPS 재확인까지 완료했다. 4GiB는 미시험이다. 상세 상태는
> [8GiB 앱 API 실행 증거](api-load-ec2-8g-20260905.md)를 따른다. 아래 로컬 준비 기록은 당시 상태다.

## 1. 기준과 확인 범위

- 운영책임자의 `이어서 진행해` 요청으로 [승인된 계획](../api-load-test-plan.md)을 이어갔다.
- `screen-api-matrix.md` §10을 확인했다. 제품 API·재시도·캐시 계약을 새로 정하거나 변경하지 않았다.
- 로컬 `1c3f2ef`와 배포 `b9e632d768c99f05f4028545b7e265817038d9c8` 사이에서
  POI·geocode·course·itinerary·festival 및 `CacheConfig` 경로의 Git 차이가 없음을 확인했다.
  아래는 해당 구현을 읽은 결과이며 외부 호출량을 실제로 계측한 결과는 아니다.
- 시작 시점에는 이전 [KTO 교체 기록](kto-approval-check-20260904.md)의 API 소량 성공을 기준으로
  코드·콘솔만 확인했다. 이후 당일 사용량 확인 중 키 노출이 발생해 재발급·최종 재교체와 승인된
  소량 KTO/앱 API 검증을 수행했다. 상세 명령·결과·노출 대응은 같은 기록의 2026-09-05 절을 따른다.

## 2. 앱 요청 수와 외부 요청 수를 구분해야 하는 지점

| 앱 경로 | 캐시가 없을 때 확인된 실행 경로 | 수량 해석의 경계 |
|---|---|---|
| 지오코딩 | 카카오 키워드 검색. 429에서 최대 1회 재시도 | 앱 1건을 항상 카카오 1건으로 계산하지 않음 |
| 홈 축제 | KTO `searchFestival2`를 1,000개 단위로 페이지 조회 | 앞선 원천 279건은 당시 1페이지의 근거일 뿐 영구 상한이 아님 |
| POI | primary 조회, 결과 3개 미만이면 20km 확장 가능, 요청 size보다 적으면 fallback 가능 | KTO/카카오 호출이 겹칠 수 있으며 페이지·재시도 포함 필요 |
| 주변 코스 | curated 또는 OSM 선택 뒤에도 걷기 장소 검색을 실행. 검색어 6개를 각각 카카오에 조회 | 6은 논리 검색 수. 페이지·재시도까지 포함한 실제 HTTP 수가 아님 |
| 동선 생성 | FOOD·TOUR·themes·회복 규칙의 WELLNESS/CAFE를 중복 제거해 병렬 POI 조회 | 생성 1건이 여러 카테고리 조회로 이어짐. 대회·테마별로 따로 계산 |

근거: `KakaoLocalClient`, `KakaoPoiClient`, `KtoFestivalClient`, `CachedPoiSearchService`,
`CourseNearService`, `WalkingSpotService`, `ItineraryGenerator.requiredCategories`, `ItineraryPoiPoolLoader`.

지오코딩·홈 축제·POI·걷기 장소 캐시는 현재 5분이다. 다만 캐시 key·초기 상태·만료·동시 요청·
fallback에 따라 실제 호출량이 달라지므로 단순히 요청 수를 5분으로 나눈 값을 hard cap으로 쓰지 않는다.
걷기 장소 캐시는 POI/지오코딩처럼 `sync=true`가 아니므로 동시 cache miss도 별도로 고려해야 한다.

### upstream 오류 관측에서 확인된 공백

카카오 POI 및 지오코딩 클라이언트는 첫 429에서 재시도한다. 특히 첫 429 뒤 200이 오면
클라이언트가 성공 응답을 반환할 수 있다. 따라서 앱 HTTP 200·최종 오류 로그만으로
**upstream 429가 한 번도 없었다고 입증할 수 없다.**

이는 재시도 정책을 없애자는 변경이 아니다. 운영책임자는 실제 네트워크 송신 직전의 interceptor로
각 시도를 세고 최초 429·5xx·timeout에서 전체 run을 중단하는 staging 전용 방식을 승인했다.
초기 확인 시점에는 구현되지 않았고, 후속 로컬 구현·테스트 결과는 §6에 추가했다. 아직 PR 검토와
staging 배포·활성화 검증 전이므로 외부 호출 상한이 실제 서버에서 집행된다고 주장하거나 30분
혼합 부하를 시작하지 않는다.

### 승인: staging 전용 fail-closed guard

저장소의 실제 `RestClient` 구성과 재시도·페이지 처리 경로를 다시 대조한 결과, 가장 작은 후보는
새 proxy나 Actuator 의존성을 추가하지 않고 Spring 표준 `ClientHttpRequestInterceptor`와 JVM
동시성 기본 기능으로 만드는 staging 전용 guard다. 운영책임자가 2026-09-05 승인했고 §6의
로컬 구현·테스트까지 진행했다. PR 검토·staging 배포·실제 활성화 검증은 아직이다.

- 기본값 `UPSTREAM_LOAD_GUARD_ENABLED=false`; 운영 기본 동작은 바꾸지 않는다. 활성화하려면
  `RUNNINGGU_DEPLOYMENT_ENVIRONMENT=staging`이어야 하며 production에서는 활성화하지 않는다.
- 활성화할 때는 run ID와 Kakao provider 전체/endpoint별 상한, KTO 상세 기능별 상한을 모두 양수로
  제공하지 않으면 백엔드가 기동을 거부한다.
- 실제 HTTP 실행 직전에 provider+endpoint 예산을 원자적으로 예약한다. KTO는 허용 operation별
  100회, 카카오는 provider 전체 5,000회와 endpoint별 2,000회를 함께 적용한다. `N+1`번째 요청은
  네트워크로 보내기 전에 차단한다. 수동 재시도와 페이지 요청도 각각 interceptor를 다시 통과한다.
- 첫 429·예상 밖 5xx·timeout에서 전역 trip하고 해당 시도에서 고정 guard 예외를 던진다. 이후
  Kakao/KTO 호출은 차단한다. HTTP 200 안의 누락·실패 KTO `resultCode`는 interceptor만으로 알 수
  없으므로 각 KTO parser가 별도 trip·예외 신호를 보낸다. 이 시험 예외는 기존 provider 폴백에
  숨기지 않으며, guard 비활성 상태의 제품 동작은 바꾸지 않는다.
- journal에는 고정 run ID·provider·endpoint·status 분류·elapsed·누적 수만 남긴다. URI, query,
  header, body, 예외 메시지, 서비스 키, 토큰, 이메일, 사용자 좌표는 기록하지 않는다.
- guard 활성 상태에서 허용 목록에 없는 Kakao/KTO 경로는 통과시키지 않는다. 누락된 새 경로를
  실제 호출 뒤 발견하는 방식은 fail-closed가 아니다.

허용 KTO operation은 `searchFestival2`, 국문 관광 `locationBasedList2`, 웰니스
`locationBasedList`, 두루누비 `courseList` 네 개이고, 카카오는 키워드·카테고리 검색 및
`access_token_info`·`user/me` 네 endpoint만 허용한다. 이는 HTTP API·DB·제품의 재시도/캐시
계약을 바꾸지 않지만 staging 배포 환경과 시험 중 오류 동작을 추가하므로, 계획·환경변수 계약·
테스트와 구현을 같은 PR에서 검토한 뒤에만 활성화한다.

## 3. 카카오 실제 한도 확인 결과

[카카오 공식 쿼터 문서](https://developers.kakao.com/docs/ko/getting-started/quota)는
앱 관리 페이지의 `쿼터`에서 앱의 소모량을 확인하도록 안내한다. 공개 문서의 기본 제공량을
런닝구 앱의 실제 잔여량·유료 API 설정으로 간주하지 않는다.

운영책임자가 카카오디벨로퍼스에 직접 로그인한 뒤 읽기 전용으로 앱 목록과 쿼터를 확인했다.
로그인값·키를 채팅이나 문서로 받지 않았고 키 화면도 열지 않았다.

| 확인 항목 | 2026-09-05 콘솔 표시 |
|---|---|
| 접근 가능한 앱 | `비집고`(ID 1442028, Editor), `제철수집`(ID 1477983, Owner) |
| 호출 이력 판별 | `제철수집`은 이번 달 호출 없음. `비집고`만 이번 달 398건 |
| `비집고` 월간 무료 쿼터 | 398 / 3,000,000, 산술 잔여 2,999,602 |
| `비집고` 당일 사용량 | 0건 |
| `비집고` 9월 4일 | 키워드 검색 32, 카테고리 검색 3, 지도 인증 8, 합계 43건 |
| 유료 사용량 | 이번 달 유료 호출 0, 무료 호출 398 |
| 콘솔 분류 | 앱 목록에 `카카오맵 무료 쿼터` 표시 |

9월 4일의 키워드·카테고리·지도 인증 호출은 staging 서버 및 Android 점검 시각과 호출 종류가
일치하고, 다른 앱은 같은 기간 호출이 없었다. 이어서 SSM Run Command
`94564146-51b4-4fcd-88af-1ae74fbf204f`로 `/etc/runninggu/application.env`의 숫자형
`KAKAO_APP_ID` 한 항목만 허용 목록 방식으로 읽었다. 응답 코드 0과 `1442028`을 확인해 콘솔의
`비집고` 앱 ID와 직접 일치했다. 다른 환경변수·카카오 키·파일 내용은 출력하지 않았고 S3/CloudWatch
출력도 사용하지 않았다. 따라서 이 앱을 현재 staging 배포가 사용하는 카카오 앱으로 확정한다.

이번 확인으로 공개 기본값을 실제 사용량으로 오인하는 문제는 해소했다. 다만 유료 호출 0건과
무료 쿼터 표시는 과금 발생이 없었다는 증거이며, 앱의 모든 유료 API 설정이 비활성이라는 별도
설정 증거로 확대하지 않는다.

부하 시험의 승인된 안전 예산은 카카오 실제 HTTP 시도 전체 5,000회 이하이면서 개별 endpoint
2,000회 이하, KTO는 상세 기능별 실제 HTTP 시도 100회 이하이면서 최소 100회의 잔여량 보존이다.
어느 공급자든 최초 429·예상 밖 5xx·timeout 또는 예산 초과 시 즉시 중단한다. 이 값은 2026-09-05
카카오 잔여량에 비해 보수적이지만, **실행 중 실제 시도 수를 관측하고 차단하는 수단이 생기기 전에는
집행됐다고 간주하지 않는다.**

KTO 최종 재발급 키는 4개 상세 기능 직접 검사와 앱 HTTPS 5개 경로, 두루누비 재동기화를 통과했다.
포털에서 실시간 당일 사용량·잔여량 화면은 찾지 못했다. 이 값을 추정하거나 일일 제공량 1,000을
잔여량으로 읽지 않고, operation별 100회의 내부 상한을 실제 송신 전에 강제한다.

## 4. KTO 최종 키 재교체 — 소량 검증 완료

- 인증키가 보이는 포털 마이페이지와 echo-off 웹 터미널 접근성 출력에서 각각 노출이 발생해 해당
  키를 즉시 재발급했다. 최종 입력 뒤에는 그 터미널을 자동 판독하지 않았다.
- 최종 후보는 KTO 4개 상세 기능 각 1회 `HTTP 200 + resultCode 0000`을 통과했다. 첫 검사 명령은
  종료 래퍼 결함으로 전체 status가 Failed였으나, 성공 결과 파일을 별도 읽기 전용 SSM 명령으로
  재검증했다. API 호출을 재실행해 결과를 맞추지 않았다.
- `KTO_SERVICE_KEY` 한 줄만 원자 교체하고 백엔드만 재시작했다. 프로세스 env 내부 동등성,
  의존 서비스·컨테이너 불변, `NRestarts=0`, 후보 파일 0개를 확인했다.
- HTTPS 축제·TOUR·WELLNESS·코스 지역·대회 5/5가 200이었고 두 POI는 실제
  `provider=KTO`였다. 두루누비 동기화 성공 1회·실패 0회와 261개 catalog 유지도 확인했다.
- 교체 이후 journal에서 재발급 전후 키 원문·URL 인코딩 표현 출현은 0건이었다. 상세 SSM command
  ID·백업 경로·수치는 [KTO 승인·교체 기록](kto-approval-check-20260904.md)에 둔다.

이로써 키 유효성 차단은 해소됐다. 포털 실시간 사용량은 확인 불가로 기록하며, 30분 시험 전에
승인된 upstream 시도 계측·차단을 구현·테스트·배포해야 한다.

## 5. 오프라인 도착 시간표 — 구현·테스트 완료

추가 파일:

- `scripts/api/plan_api_arrivals.py`: 승인된 분당 비율의 도착 시간표·요약·hash 생성.
- `scripts/api/test_plan_api_arrivals.py`: 문서의 요청 수·위상 경계·순서·의존성·hash 변화·미완료 표시 테스트.

준비 부하 300건 + 본 시험 1,800건 = **2,100개의 예정 요청**이며 1초마다 한 건을 예약한다.
각 분에서 대회 30·코스 목록/지역 9·주변 코스 6·외부 조회 6·생성 3·인증/즐겨찾기 6을 유지한다.
즐겨찾기 추가 35건과 삭제 35건은 **실행 전에는 예약 수**다. 추가를 매분 10초, 삭제를 50초에
배치하고 삭제는 같은 분·같은 계정의 앞선 추가에 의존한다. 실행기는 추가가 실패했거나 삭제
시각까지 끝나지 않으면 삭제를 성공처럼 실행하지 않고 전체 run을 실패시킨다.

실행:

```text
python -m unittest discover -s scripts/api -p 'test_*.py' -v
python scripts/api/plan_api_arrivals.py    # 두 번 별도로 실행
```

- 시간표 구현 당시 테스트 **4개 클래스·32개 메서드 통과**(기존 24개 + 시간표 8개).
- 시간표 CLI **2회 출력 완전 동일**.
- 추가·삭제 간 정상 최대 응답시간 3초보다 큰 간격을 고정한 최종 시간표 SHA-256:
  `88bd5580cc00ca57796b6ba98cbe4e54b727e02924f773561e7b4a638abd097d`.
- 첫 요청 0ms, 본 시험 시작 300,000ms, 마지막 요청 2,099,000ms의 상대 시각을 확인했다.
- 단위 테스트가 문서의 분당 수량과 별도 상수로 비교한다. 시각·case·요청 수를 바꾸면 hash도 달라진다.
- 네트워크·인증·실제 API 쓰기는 실행하지 않는다. 시간표 hash는 실제 HTTP 입력 세트의 hash가 아니다.

이 시간표만 만든 단계에서는 서버/Android 코드를 바꾸지 않았다. 후속 서버·실행기 구현 검증은
§6에 분리한다. 실제 35분 실행의 CPU·네트워크·동시성·missed start·토큰 갱신 결과는 아직 없다.

## 6. staging 가드·승인 요청 세트·실행기 — 준비 당시 검증, 후속 #289 배포 완료

### 6.1 외부 호출 가드

- 기본 `enabled=false`, 정확한 `staging` 환경·안전한 run ID·양수이면서 승인 최대값 이하인 상한이
  있을 때만 활성화한다. 더 큰 오입력은 실행서 검증과 별개로 애플리케이션 기동에서 거부한다.
- 승인된 KTO 4개·카카오 4개 endpoint만 host/path/scheme/port까지 대조한다. 실제 송신 직전
  endpoint와 카카오 전체 counter를 원자 예약하고 N+1은 송신 전에 차단한다.
- 재시도·페이지·폴백의 각 실제 시도가 interceptor를 통과한다. 첫 429·5xx·connect/read/body
  timeout과 KTO 누락·비정상 `resultCode`는 전역 trip하며 시험 중 provider 폴백으로 숨기지 않는다.
- 가드 로그는 고정 10개 필드만 남긴다. 최종 요약기는 정확한 8개 endpoint·승인 상한·counter
  연속성·trip/block·2xx 외 결과를 독립 상수로 검사하고 원본 journal을 결과에 복사하지 않는다.
- importer는 환경파일 값과 무관하게 가드를 끈다. 제품 API·캐시·기존 재시도는 비활성 상태에서
  그대로 유지한다.

검증:

- 가드와 연결된 KTO/Kakao client 대상 **10개 클래스·94개 테스트**, 실패·오류·skip 0.
- 최신 원격 `develop`의 `e8d6c5b`까지 fast-forward한 뒤 `bootJar`와 `ec2Artifact` 성공. 배포 묶음은
  서버 JAR·Importer JAR·대회 snapshot만 포함하고 운영 가드/요약 스크립트는 동일 Git checkout에서 사용한다.
- 가드 journal 요약기 **7개 테스트를 2회** 실행해 모두 통과.
- runbook의 §15.3 shell 블록은 실제 Git Bash `bash -n`으로 통과했다.
- Docker Desktop 재시작 뒤에도 `dockerDesktopLinuxEngine` named pipe가 생성되지 않아
  Testcontainers를 포함한 전체 `test`는 아직 실행하지 못했다. 이를 성공으로 표시하지 않고
  PR CI 또는 정상 Docker 환경의 필수 재검증으로 남긴다.

### 6.2 승인된 고정 요청 세트와 혼합 부하 실행기

소량 staging 응답으로 다음 성공 입력을 확인해 후보로 고정했고, 운영책임자가 2026-09-05
입력·기대값 전체를 v1으로 명시적으로 승인했다. 최종 파일은
`scripts/api/fixtures/staging-api-load-v1.approved.json`이다.

- 대회 126: 활성·2026-10-11·HALF, 마감 임박과 2026-10 일별 집계가 비어 있지 않음.
- 울산 좌표의 curated 주변 코스: `API_GPX`/`GPX_ONLY` 경로와 장소, degraded 없음.
- 서울시청 좌표의 OSM 주변 코스: `OSM_GENERATED` 경로와 장소, degraded 없음.
- 2026-10 축제, 대회 좌표 TOUR POI(`source=LIVE`), 해운대해수욕장 지오코딩이 각각 정상.
- 대회 126·2026-10-10~12·HALF·TOUR/FOOD 동선은 3일·RACE 블록 1개.

승인 전 후보 canonical SHA-256은
`2ffc40924a284af30e2ad7850a15657bef3d4f265f11a464d8fd386eb7a5c214`였다. 승인 상태까지 hash에
포함하므로 최종 `APPROVED` canonical SHA-256은
`6811484a70d406e555c9bdce8273744ef5be597795a73186c9ed9ea42a818ef1`이다. 실행기는 여전히
`CANDIDATE` 값을 `--execute`와 함께 받으면 비공개 입력·네트워크 전에
`request_set_not_approved`로 거부한다.

`scripts/api/run_api_load.py`는 다음을 구현했다.

- 두 전용 계정 정보를 echo 없는 입력으로만 받고 토큰과 비밀번호를 결과·repr에 남기지 않는다.
- 서로 다른 회원 ID, 즐겨찾기 교차 노출 없음과 정리 상태를 실제 읽기/쓰기로 preflight한다.
- 14개 정상 API 응답의 status·필수 내용·curated/OSM·degraded 없음·RACE 블록을 검증한다.
- 부하 직전과 시작 20분 후 두 계정 토큰을 모두 갱신하고, 종료 시 두 계정을 모두 정리·로그아웃한다.
- 1초 간격 open-loop 시간표와 동시 진행 4개를 지키며 포화·쓰기 의존성 실패를 즉시 실패시킨다.
- 본 시험 1,800건의 모든 성공 여부와 실패 응답 시간까지 포함해 그룹별 p50/p95/max를 계산한다.
  결과에는 고정 ID·수량·시간·오류 class·응답 byte·실행기 CPU만 남긴다.

최종 API 도구 테스트는 **7개 클래스·46개 메서드**를 서로 다른 working directory에서 2회
실행해 모두 통과했다. 최종 코드로 승인 fixture dry run도 2회 실행해 위 요청 세트·시간표 hash와
2,100건 계획이 동일하고 실제 HTTP가 0건임을 확인했다. `--execute` 실행 거부도 별도로 확인했다.
실행기 CPU는 기록하지만
NIC 비포화는 OS 표본이 별도로 필요하고, EC2 자원·백업·WAL·가드 최종 요약도 같은 run에서
수집해야 한다.

이 기록을 포함한 변경의 PR 검토·머지와 staging 배포, 35분 실제 부하는 아직 수행하지 않았다.
Android 코드는 바꾸지 않았다.

## 7. 다음 순서

1. 전체 backend `test bootJar`를 Docker Linux engine이 가능한 환경에서 실행한다.
2. 이 로컬 변경을 리뷰 가능한 PR로 올려 승인 fixture·가드·실행기·runbook을 검토한다.
   실행 결과를 본 뒤 입력이나 기준을 바꾸지 않는다.
3. 머지된 동일 commit을 8GiB staging에 배포하고 가드 비활성 평시 스모크를 먼저 확인한다.
4. 새 run ID로 가드·EC2 5초 자원 수집·백업/WAL·부하 생성기 OS 표본을 준비한 뒤, 두 계정을
   비공개 입력해 5분 준비 + 30분 혼합 부하를 실행한다.
5. 즉시 가드를 비활성화하고 앱 결과·가드 counter·EC2 자원·백업/WAL을 함께 판정한다.
6. 8GiB 통과 뒤에만 4GiB 여부를 별도 승인받아 같은 조건을 3회 실행한다.
