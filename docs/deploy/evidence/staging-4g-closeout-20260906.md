# 4GiB staging 기본 운영 점검 — 2026-09-06

> 상태: **4GiB 기본 운영 점검 완료. 기존 4GiB 설정을 유지한다.**
> 운영책임자의 기존 4GiB 유지와 [마무리 계획](../staging-4g-closeout-plan.md) 실행 승인에 따른다.
> 기존 `capacity-v2` 2,100건 부하는 중도 종료·미완료로 보존한다. 이번 결과로 소급 합격시키지 않는다.
> 이후 별도 승인한 [v3 앱 부하 시도](api-load-ec2-4g-20260906-v3.md)는 KTO timeout·가드 TRIP으로
> 로그인 전에 중단했다. 이 문서의 기본 운영 완료와 전체 부하 미완료를 구분한다.

## 실행 환경

- 기존 `ap-northeast-2b`의 `i-07aa483968f4daddc`, `c7i-flex.large` 4GiB를 유지했다.
- backend `673a2f796052f4553113d5cd25608fb3821222ac`, graph
  `gh11-korea-20260901-2ff6731b181a-2b8515dd29fc`를 대조했다.
- GraphHopper heap 512MiB~2GiB, reservation 2GiB·memory/memswap limit 2.5GiB.
  backend heap 256~512MiB, systemd MemoryHigh 640MiB·MemoryMax 768MiB. host swap 4GiB.
- 새 AWS 리소스와 graph artifact를 만들지 않았다. 설정·상한과 배포 버전을 유지했다.
- run ID: `ops4g-673a2f7-20260906T0550Z`.
- fixture canonical SHA-256:
  `6811484a70d406e555c9bdce8273744ef5be597795a73186c9ed9ea42a818ef1`.

## 지난 실행 기록

SSM `7714c404-a930-4ec7-8e96-f49422258594`에서 2026-09-05 13:00Z부터
2026-09-06 05:43:02Z까지 약 16시간 43분의 남아 있는 기록과 현재 상태를 확인했다.

- backend·GraphHopper·Nginx의 시작 시각과 InvocationID가 이전 정상 복귀 기록과 일치한다.
  `NRestarts=0`, PostgreSQL·GraphHopper container restart count 0·OOMKilled false.
- 조회 구간의 kernel OOM 지표와 backend·GraphHopper 완료 Full GC가 발견되지 않았다.
- backend journal은 13:48:30Z~다음 날 04:48:30Z의 50개 행을 포함한다.
  GraphHopper 주 service와 Docker daemon journal은 해당 구간에 새 행이 없었다.
  GraphHopper runtime GC는 Docker 로그에서 별도로 확인했다.
- WAL timer 실행 기록은 101회 완료, 마지막 실행은 05:40:04~05:40:05Z에 성공했다.
- 이 구간의 host 메모리·swap·응답시간 시계열은 수집하지 않았다. 과거 16시간 전체가
  5초 표본으로 관찰됐거나 요청이 계속 성공했다고 주장하지 않는다.
- 조회 당시 MemAvailable 1,108,408KiB(28.393%), swap 사용량 52KiB, 가드 OFF였다.

## 소량 API와 평시 복귀

| 단계 | UTC 시각·결과 |
|---|---|
| 가드 OFF HTTPS | 공개 7항목 통과. 공개 조회 6개 200, 미인증 `/me` 401 |
| 가드 활성화 | 06:02:38 시작, 06:02:51 readiness. KTO operation별 100·카카오 전체 5,000·endpoint별 2,000 |
| preflight·계측 | KTO 실제 3회 송신·정상 응답, counter·가드·InvocationID 정상. 자원 수집 06:02:51 시작 |
| 14종 API·계정 분리 | 06:04:39 완료. 14종 각 1회 및 로그인·소유권·정리·로그아웃을 포함해 총 34요청 |
| 가드 OFF 복귀 | 해제 명령 06:05:45 시작, backend 재기동 후 06:05:58 readiness·실제 프로세스 가드 OFF |
| 백업/WAL 검사 | 06:05:58~06:06:00 성공 |
| 외부 HTTPS 복귀 | 공개 7항목 재통과 |

SSM 가드/계측 시작: `a23986a0-6eab-46df-a261-5dc74f8bdb67`.
해제·현재 backup/WAL 검사: `42727340-10aa-4ed9-a54f-f5d6f0ea34e9`.

[요청별 증거](api-smoke-ec2-4g-20260906.json)의 34요청은 모두 기대 status와 일치한다.
14종의 필수 내용 검사, A/B 계정 분리, 전용 즐겨찾기 정리와 양쪽 로그아웃을 완료했다.
기존 전용 대상이 비어 있음을 쓰기 전에 확인했다. 추가·삭제는 별도 소유권/정리 요청으로
기록했으며 14종에 숨겨 합산하지 않았다. 예상 밖 HTTP 오류·통신 실패·목표 초과 지연은 없다.

| 주요 API | 응답시간 |
|---|---:|
| curated 주변 코스 | 628.015ms |
| OSM 주변 코스 | 681.366ms |
| 축제 | 371.077ms |
| POI | 278.615ms |
| 지오코딩 | 177.752ms |
| 동선 생성(전체 최장) | 1,602.854ms |

단일 소량 실행의 관측값이며 p95 합격이나 최대 처리량을 뜻하지 않는다. 캐시를 강제로 비우지 않았다.
숨김 입력은 메모리에서 사용한 후 폐기했고 로컬 입력/실행 프로세스 종료를 확인했다.
[가드 해제 뒤 HTTPS 증거](api-smoke-ec2-4g-https-20260906.json)를 남겼다.

가드 최종 결과는 KTO 8회·카카오 15회, 총 23회 정상 응답이다. `unsafeEvents`,
`non2xxResults`, `counterGaps`, `malformedLines`, `overLimit`은 모두 0이다.
KTO 축제·한국어 위치·웰니스 위치·두루누비가 각각 2회, 카카오 category 1회·keyword 14회다.
KTO operation별 최댓값 2/100, 카카오 전체 15/5,000·endpoint별 최댓값 14/2,000이다.

## 자동 백업·현재 WAL

- 매일 03:20 KST timer가 2026-09-05 18:20:04Z에 실행됐다.
- full backup `20260905-182005F`: 18:20:05~18:20:50Z, 오류 없음.
  service의 expire·check까지 18:20:52Z에 성공했다. timer active만 보고 성공으로 판정하지 않았다.
- 현재 `pgBackRest check` exit 0, repository status `ok`.
- WAL 검사 새 InvocationID `612e7462fcf24e5a85b471839b52bd0b`, `Result=success`, exit 0.
  해결되지 않은 archive 실패와 10분 넘은 `.ready` WAL이 없음을 기존 검사로 확인했다.
- 예약 full backup이 성공했으므로 추가 full backup은 만들지 않았다. 이전 실제 복구 증거는
  [4GiB 이전·재부팅 기록](staging-4g-validation-20260905.md)을 재사용했다.
  이번 check 성공은 새로운 DB 복구 리허설을 수행했다는 뜻이 아니다.

## 15분 자원·최종 상태

SSM `749326b8-1cd6-470e-a1ee-c42ab814450e`의 종합 명령이 성공했다.
[자원·기준선·가드·최종 상태 원자료](staging-4g-closeout-runtime-20260906.json)를 함께 보존한다.

| 항목 | 관측 결과 |
|---|---|
| 수집 | 06:02:51~06:17:51Z, 180/180개·5초 간격, 누락/미완성 표본 없음·collector exit 0 |
| MemAvailable | 시작 28.977%, 최저 **27.628% / 1,078,540KiB**, 종료 28.103% |
| swap | 시작·최대·종료 52KiB, swap-in/out 각각 0페이지 |
| 정상 API 구간 Full GC | backend 0회·GraphHopper 0회 |
| 가드 해제 재기동의 GC | backend 시작 중 `Metadata GC Threshold` 4회, 23.694/51.256/74.043/105.739ms. 전부 readiness 확인 이전이며 정상 API 구간과 분리 |
| 실제 메모리 오류 | JVM OutOfMemoryError·kernel OOM·page allocation failure 지표 0 |
| 서비스 | backend·GH 180개 표본 모두 active, NRestarts 0. GH/PG container 모두 running·OOMKilled false·restart count 0 |
| container CPU 최댓값 | GH 34.93%, PG 24.94%. 이 값으로 host CPU 전체의 최대 처리량을 주장하지 않음 |
| 최종 06:17:55Z | boot ID 유지, backend·GH·Nginx active, 가드 OFF·readiness 성공 |
| 자동/임시 작업 | backup·WAL·certbot timer active. 가드 만료 timer와 수집 service는 종료돼 not-found/inactive |

가드 활성화와 해제를 위한 backend 재기동은 계획 작업이다. `NRestarts=0`만으로 재기동이 전혀
없었다고 해석하지 않고 InvocationID와 작업 시각을 함께 남겼다. 가드 구간은
`9c71a6835ff342b5a1954330b3eaf7e5`, 평시 복귀 후는 `cfe2a95ecfcc48689e75ff74f830ea01`이다.
GH InvocationID와 host boot ID는 바뀌지 않았다.

추가 읽기 전용 SSM `1c23e0b4-1007-4151-9b00-a2d2c00d29ee`로 GC 4회의 원인·시각도 확인했다.
06:05:49.047989Z, 50.924937Z, 53.090992Z, 58.668124Z에 완료됐고 모두
06:05:58.744441Z readiness 확인 이전이다. JVM이 클래스 메타데이터 임계값에 반응한 시작 과정의
GC이며 이번 기록에서 host RAM 고갈이나 정상 요청 실패의 근거가 되지 않았다.

원시 5초 로그는 서버의 `/opt/runninggu-validation/ops4g-673a2f7-20260906T0550Z/runtime-metrics.log`에
보관했다. SHA-256은 `2c64e5638d7aa25a25e25989a836468d7794ae4d4da30ea72304e7dc3521478b`다.

이번 범위에서 해결되지 않은 서비스 중단·기능/계정 분리 오류·백업/WAL 문제는 발견되지 않았다.
**현재 구성의 4GiB 유지로 기본 운영 점검을 마무리한다.** 추가 부하·관찰은 별도 요청이 있을 때
수행하며, 작은 표본으로 전체 부하 통과나 프로덕션 전체 출시 승인을 대신하지 않는다.

## 도구 준비와 검증 한계

- `run_api_smoke.py`를 추가해 2,100건 스케줄 없이 승인된 응답 검사와 계정 분리를 재사용했다.
  중단·공급자 오류·기존 즐겨찾기 보호·쓰기 실패 시 정리·비밀 비기록을 mock으로 검증했다.
- Windows의 파일 교체 공유 잠금이 mock 검사에서 재현돼 해당 OS 오류의 파일 저장만
  최대 10회·50ms 간격으로 재시도했다. HTTP 요청은 재시도하지 않는다.
  보완 뒤 관련 검사 25개가 2회 연속 통과했다. 오프라인 실행 결과도 두 번 일치했다.
- 첫 공개 HTTPS 검사의 sandbox `transport` 실패는 실행 환경의 네트워크 제한이었다.
  승인된 외부 실행에서 같은 7항목을 확인해 통과했다. 이를 서버 HTTP 오류로 집계하지 않았다.
- 기존 서버의 임시 제어 사본은 로컬 hash와 달라 설치를 거부했다. 검토된 새 제어 파일을
  압축·hash 검증·compile 후 설치하고 활성화했다. 실패한 준비 명령은 가드를 켜지 않았다.
- 제어 파일 SHA-256: `d73fd64939c6fcd20d2504c20e67c587a1f3155d372871a1d9a5a3cce01e9d3c`.
- 마케팅 설정 #287, Android E2E, 전체 앱 출시 체크리스트는 이번 검증 범위 밖이다.
  4GiB의 전체 2,100건 부하·전체 시나리오 3회·새 24시간 관찰은 미완료/보류로 유지한다.
  하루 방문 20회 예상은 API 요청량이나 동시 사용자 수 보장으로 환산하지 않았다.
