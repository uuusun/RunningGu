# GraphHopper EC2 8GiB 검증 — 2026-09-04

> **수정본 `d884d36`의 8GiB baseline과 별도 장애 격리 재검증 통과.**
> 실제 운영 hard limit은 아직 미적용이다. swap 정책 확정·상한 적용 후 재시험이 남았으므로
> 최종 운영 제한 승인이나 4GiB 합격을 뜻하지 않는다. 앞부분은 이전 `24efc8c` 시험에서 발견한
> 결함을 보존한 기록이며, 최신 결과는 아래 「수정본 `d884d36`의 별도 재검증」에 있다.
> 기준은 [artifact 계약 §9](../graphhopper-artifact-contract.md#9-사전-합격-기준)와
> [EC2 실행서 §15](../aws-ec2-staging-runbook.md#15-외부-스모크재부팅-검증)다.

## 실행 대상

- 환경: 서울 리전 staging, Ubuntu 24.04, `m7i-flex.large`, RAM 7776MiB
- PR #255 head: `24efc8cc5816040aa12b9e3cc9409159995c0fa8`
- CI: [33817464180](https://github.com/uuusun/RunningGu/actions/runs/33817464180), attempt 1
- candidate는 `artifact_kind=pr-validation`, `allowed_environment=staging`이며 정식 릴리스가 아니다.
- graph: `gh11-korea-20260901-2ff6731b181a-2b8515dd29fc`
- GraphHopper image: `sha256:53d5afeacb080784524dbccf5dbb7af58e1a23416ea2e7e66cb85b98fd5313fd`
- 로컬 기준선과 동일한 Docker image를 `docker save`/`load`로 전달했다. 별도 rebuild 결과의 다른 image ID로 대체하지 않았다.
- graph·CI 묶음·소스·server image는 private S3의 새 경로로 전달했다. 새 객체에 SSE-KMS를 확인했다.
- EC2에서 다운로드 파일과 내부 SHA256SUMS를 대조했고, graph installer의 최초 설치·재실행이 모두 성공했다.

## 보존한 기존 변경

EC2와 로컬에 기존 `backend/postgres/Dockerfile`의 `ca-certificates` 추가 1줄이 있었다.
이전·새 commit의 해당 파일 Git blob이 같음을 확인하고, 수정본을 별도 백업한 뒤 전환 후 복원·대조했다.
이번 작업에서 그 변경을 임의로 commit하거나 제거하지 않았다. PostgreSQL container/image도 재빌드하지 않았다.
따라서 이 기록은 **기존 PostgreSQL 이미지가 함께 실행된 실측**이며, 미커밋 수정까지 포함한
완전한 clean-checkout 배포 재현성을 증명하지 않는다.

## 배포와 전체 회귀

- 이전 backend release와 graph 세대를 삭제하지 않고 보관했다.
- 기존 systemd unit 4개는 저장소와 CRLF/LF 차이만 있었고 정규화 후 설정이 동일했다.
- `systemd-analyze verify` 성공
- Spring Boot 중지 → 대회 snapshot Importer → `status=NO_OP`, `ExecMainStatus=0` → 재기동
- graph verify oneshot: **682ms**
- 주 service start job(`ExecStartPre` 포함): **709ms**
- verify 시작부터 GraphHopper route 준비 완료: **5745ms**
- verify 시작부터 GraphHopper·Spring Boot 모두 준비 완료: **18437ms** — 120초 이내
- backend·GraphHopper 모두 `NRestarts=0`, `Result=success`
- `roundtrip.py --preset caps --zone all --seeds 16 --baseline ...`: exit 0, **비회귀 PASS**
- 선택된 83개 셀의 경로 계단 비율 최대 0.46%, 1% 초과 0건

## 30분 정상 부하 — 요청·자원 항목 통과

- 시작: `2026-09-04T00:14:50Z`
- 고정 정상 요청 83개, 60 requests/min, concurrency 2, timeout 5초, 1800초
- GraphHopper: Xms 1g, Xmx 4g, memory reservation/limit 0
- Spring Boot: Xms 256m, Xmx 1g, MemoryHigh/MemoryMax infinity
- 5초 자원 수집과 전체 백업 1회, 5·15·25분 WAL 검사 3회를 동시 실행
- 원본 결과 디렉터리: `/opt/runninggu-validation/8g-baseline-24efc8c-20260904`
- 종료: `2026-09-04T00:44:50Z` (마지막 직접 요청 완료 `00:44:49.861240Z`)
- 예정/완료 요청 **1800/1800**, missed·실패·no valid point·5초 초과 모두 0
- p50 **0.0093초**, p95 **0.0524초**, 최대 **0.3554초**
- 표본 **360/360**, 최저 MemAvailable **46.817%** (기준 20% 이상)
- swap 사용 증가·swap-in/out 증가 모두 0, 정상 OOM·재시작·비정상 상태 표본 0
- readiness 후 정상 부하 Full GC 완료 0건. GraphHopper GC 로그 800줄, backend GC 발생 없음
- cgroup peak: GraphHopper **3,102,842,880 bytes**, PostgreSQL **226,918,400 bytes**
- Spring Boot MemoryPeak: **457,031,680 bytes**
- full backup `00:16:50–00:17:39Z` 성공, WAL 검사 `00:19:50/00:29:50/00:39:50Z` 모두 성공
- SSM 정상 부하 `70f17290-af44-4eb5-9942-97a9822c8c64`, 최종 감사 `492c5be1-d1ea-4893-964f-250af496892f` 모두 exit 0
- 백엔드·DB가 함께 실행된 상태에서의 **GraphHopper 직접 요청 60/min** 검증이다.
  앱 전체 동작의 최대 동시 사용자 수나 다른 API의 처리량을 증명하지 않는다.

## 재부팅 — 통과

- boot ID 변경을 확인했다.
- GraphHopper `ExecStartPre` 포함 job: **4714ms**
- GraphHopper activation부터 두 readiness를 모두 관측하기까지 **21089ms** (120초 이내)
- 동일 artifact·image 유지, graph hash 재검증 성공, import 로그 0건
- GraphHopper Docker restart=`no`, PostgreSQL=`unless-stopped`, 두 주 service 재시작 0
- SSM `d8a14ada-9227-4414-a531-5dd99b68e911`: 성공

## 발견한 결함과 로컬 수정

### 좌표 로그 — §9.2 로그 조건 미충족

Docker local 로그에서 정상 요청의 `point=` query가 **1800줄** 발견됐다. 주 service journal의
runtime GC/요청 중복은 0줄이었다. 시험은 고정 공개 fixture 좌표를 사용했지만, 같은 설정으로
사용자 요청을 받으면 좌표·User-Agent가 기록되므로 “사용자 정보 없음”을 보장하지 못한다.

GraphHopper 11의 기본 access log와 `RouteResource` INFO가 각각 노출 경로다.
[공식 11.0 설정 예제](https://github.com/graphhopper/graphhopper/blob/11.0/config-example.yml)와
[RouteResource 구현](https://github.com/graphhopper/graphhopper/blob/11.0/web-bundle/src/main/java/com/graphhopper/resources/RouteResource.java)을 대조했다.
운영 YAML에서 `request_log.appenders: []`, `RouteResource: WARN`을 지정했다.

- 검사: `check-server-log-privacy.py`, HTTP 200·의도적 잘못된 profile의 400
- 기존 image: 노출 3줄, exit 1 — 실제 결함 검출
- 수정 image: 노출 0줄, exit 0 — 2회 반복 성공
- 수정 image ID: `sha256:16b3618f3e69a094990e6c9360ee92972beec643ca26bc5d9b61404172910753`
- 당시 새 image는 EC2에 미설치 상태였다. 이후 아래 `d884d36` 재배포에서 설치했다.
  기존 30분 결과를 새 image의 합격으로 재사용하지 않는다.
- 새 image로 로컬 caps/all 1440개를 다시 실행했다: 200=1431, NoValidPoint 400=9, 합격 셀 83/90.
  시간 필드를 제외한 모든 셀의 status·오류 분류·품질 수치와 83개 정상 요청 목록이 기존과 정확히 같다.
- 전체 회귀 로그에서도 시험 좌표/요청 URL/User-Agent 표식 0줄, JVM GC 로그 1371줄을 확인했다.
- 동일 artifact ID의 로컬 기준선 JSON을 실제 새 실행 결과로 갱신했다. 24efc8c 당시의 기준선은
  해당 Git commit에 그대로 있으며, 새 digest만 바꿔 과거 결과를 재사용하지 않았다.

### 정상 stop — exit 130 오탐

최초 격리 시험 `10ebe9d6-72c7-4296-a0d6-a8bebc30b4d3`에서 `systemctl stop` 뒤 Compose가
130으로 종료해 unit이 `failed/exit-code`가 되고 OnFailure를 호출했다. 정상 종료 단계에서
시험을 중단했으며 기존 unit·동일 graph의 실제 서비스 복원은 성공했다.

실행 wrapper가 stop marker를 확인한 경우에만 0·130·143을 0으로 정규화하도록 고쳤다.
marker 없는 종료와 137은 성공으로 바꾸지 않는다. 10개 종료 분기 테스트를 두 번 통과했고,
정규화 코드를 제거하면 `requested=yes, compose=130`에서 실제 실패했다.

격리 복사본에만 같은 수정을 적용한 뒤 정상 종료·hash 재검증은 EC2에서도 통과했다.
후속 stderr probe는 newline 없는 표식이 flush되지 않아 실패했으므로 fixture를 보완했다.
이는 정상 부하 실패가 아니라 시험 fixture의 관측 문제이며 이전 실패 결과도 보존했다.

로컬 `test bootJar`는 성공했지만 Java 변경이 없어 7개 Gradle task 모두 `UP-TO-DATE`였다.
배포 도구 Python 단위 테스트 38개, shell 종료 분기 10개(2회), ShellCheck·actionlint·Python
문법 검사도 통과했다. 종료 분기 테스트를 공통 CI 검사에 연결했다.

## 장애 격리 — 수정된 격리 복사본에서 확인

실제 `runninggu-graphhopper.service`를 백업한 뒤 별도 Compose project·loopback 18989·별도 graph
복사본을 가리키도록 잠시 교체했다. PostgreSQL service/volume은 시험 Compose에 포함하지 않았다.
성공·실패 모두 기존 unit을 복원하고 실제 8989 route·graph hash를 확인하는 EXIT 복구 절차를 사용했다.

- 정상 중지 뒤 `inactive`, `Result=success`, `NRestarts=0`, graph 재검증 성공
- container stderr 표식은 Docker local에만 존재하고 주 service journal에는 없음
- 예상하지 않은 exit 0: 1로 승격, 실제 기동 3회 뒤 `NRestarts=3`, failed 유지, 추가 재시도 없음
- 설정 누락: container 생성 전 원인이 journal에 남고 3회 제한·최종 SNS 발행 성공
- 고의 OOM: **EC2는 계속 8GiB**. 시험 container의 `mem_limit=4GiB`,
  `memswap_limit=4GiB`(시험 container swap 불허), JVM Xms/Xmx=5g·AlwaysPreTouch로 고의 유발
- `OOMKilled=true`, exit 137, 10분 window/3회 제한, 최종 `exit_status=137 restarts=3` 알림과
  알림 service의 `ExecMainStatus=0`을 확인했다. SNS 발행 성공이며 이메일 수신 자체를 확인한 것은 아니다.
- PostgreSQL 시작 시각 불변·running 유지, backend 재시작 증가 없음
- OOM 뒤 `pgbackrest check`와 WAL 검사 성공
- 같은 graph artifact로 복구 성공, 실제 unit 원복·실제 route·verify 모두 성공

정상 종료·stderr는 `3e2cf222-6ca2-40f0-908d-c10d8f764374`, exit 0의 횟수 제한은
`8c05b5c0-107f-406d-a7d6-8c4ed5fbb170` 결과에서 확인했다. 이 두 시험 묶음은 뒤의 관측 조건 때문에
전체 exit 1이므로 전체 성공으로 바꿔 기록하지 않는다. 설정 누락·OOM·복구의 최종 묶음
`c21563d3-70fa-4d35-8074-13ddc962bf8b`는 exit 0이다.

관측 보완: 이 systemd는 start limit에 도달해도 `Result=exit-code`를 유지한다. 실제 횟수·제한
journal·failed 유지로 확인했다. 알림 본문은 `journalctl -u`에 없지만
`journalctl -t runninggu-graphhopper-alert`에 있었고, 실행 시각과 SNS publish 성공을 대조했다.
시험 도중 조건을 약화해 정상 부하 합격을 만든 것이 아니라, 사전 계약의 실제 동작을 직접 관측한 것이다.

위 수치는 **시험용 제한**이며 실제 운영 Compose/백엔드 unit의 memory limit을 아직 바꾸지 않았다.
수정된 exact commit·새 image·선정한 memory/swap 제한으로 전체 시나리오를 다시 통과해야 운영 승인이다.

최종 원복 감사 `2026-09-04T01:19:46Z`: backend·GraphHopper·Docker·Nginx 모두 active,
두 주 service `Result=success/NRestarts=0`, 실제 graph image는 기존 `53d5afeacb08…`,
프로파일 `run` 하나, swap 사용 0. 실제 메모리 제한은 기존 무제한 상태다. 격리 project의
container는 모두 제거됐고 실제 unit·기존 PostgreSQL Dockerfile 수정본은 백업과 일치했다.
외부 HTTPS도 HTTP 200·TLS 검증 성공을 다시 확인했다. **EC2는 중지하지 않고 실행 상태로 두었다.**

## 외부 스모크

2026-09-04 00:19 UTC에 외부에서 다음을 확인했다.

- HTTPS `/api/contests?size=1`: HTTP 200, TLS 인증서 검증 성공
- HTTP 동일 경로: HTTPS로 301 redirect
- `/v3/api-docs`, `/swagger-ui/index.html`: 둘 다 404

## 첫 시험에서 이어진 작업

- 수정 commit·CI candidate·새 server image의 EC2 배포와 동일 30분/재부팅 재시험 — 아래에서 완료
- 실측 hard limit 선정과 적용 후 동일 시나리오 재시험
- 4GiB는 선택 시 같은 전체 시나리오 3회. 현재 결과로 4GiB 합격을 주장하지 않는다.

## 수정본 `d884d36`의 별도 재검증

### CI·전송·배포 — 통과

- PR #255 commit: `d884d36aefd5a5bb6adcdd61cc5b1f544c659a39`
- [CI 33828194554](https://github.com/uuusun/RunningGu/actions/runs/33828194554), attempt 1:
  SPEC, synthetic merge 단위·통합·bootJar, exact PR head 검사·패키징 모두 성공
- candidate base: `839df983ccfa425214d3103e6fd59bc507aff48d`
- integration test commit: `4fb065ff4660d400f067586f7ddf6eadf5a36790`
- `gh run download`로 받은 내부 파일의 SHA256SUMS를 확인한 뒤 전송용 ZIP으로 재포장했다.
  ZIP의 압축 메타데이터는 GitHub 원본 archive와 다를 수 있으나 내부 payload·manifest는 바꾸지 않았다.
- 전송 ZIP: 119,473,934 bytes,
  SHA-256 `6748814aad6e1ed254f7a9f6d07824c449be508459ad40099e1f84b853047ed6`
- exact `git archive`: 22,332,413 bytes,
  SHA-256 `f57b4cf00f4667329ae1c9308b4af989d2a16e5704030d8c957cb89b2281913f`
- 검증된 새 server image tar: 158,233,600 bytes,
  SHA-256 `a1a7b0c1d557590ad4198d2dd987bc0ba5bb4039bd82a8557ea6f32f4b6229e0`
- 세 파일은 private S3 `backend/staging/<commit>/` 새 경로에 보관하고 기존 staging KMS 키의
  `aws:kms` 암호화를 각각 확인했다. EC2에서 다운로드 후 외부·내부 checksum과 출처를 재확인했다.
- 이전 release·graph·image(`rollback-24efc8c`)와 PostgreSQL 기존 미커밋 수정본은 보존했다.
- 실제 repo HEAD와 `/opt/runninggu/current`가 새 commit을 가리키며, 실제 GraphHopper image는
  로컬 재검증 image `sha256:16b3618f3e69a094990e6c9360ee92972beec643ca26bc5d9b61404172910753`이다.
- Spring 중지 뒤 Importer는 `2026-09-04T02:20:53Z`에 `NO_OP`, exit 0.
- verify **655ms**, `ExecStartPre` 포함 job **639ms**, GraphHopper 준비 **5637ms**,
  양쪽 readiness **18270ms**. 두 service `Result=success`, `NRestarts=0`.
- 외부 HTTPS `/api/contests?size=1`: HTTP 200, TLS 인증서 검증 성공.
- SSM 사전 배치 `4217efcc-9da2-4de8-b5e1-8ac40cb07a3e`, 설치
  `bd2a37e7-2e45-4984-b17c-4bebae8beadc`, 활성화
  `846d88fe-b449-4d68-a43c-d3b808194c95`: 모두 성공.

### 전체 회귀 — 통과

- 로컬의 새 image 기준선으로 `caps/all`, seed 16개를 다시 실행했다.
- 선택 셀 83개, 최대 계단 비율 0.46%, 로컬 기준선 대비 비회귀 PASS.
- 전체 직접 요청 1440개 중 HTTP 200은 1431개, `NoValidPoint` HTTP 400 실패는 9개다.
  9개를 성공으로 바꾸지 않았으며, 별도로 고정한 정상 직접 요청 83개만 30분 부하에 사용했다.
- SSM `19f98666-cab5-4737-b652-58647eab8c86`: exit 0.

### 30분 정상 부하 — 요청·자원·백업 항목 통과

- 시작 `2026-09-04T02:24:48Z`, 마지막 직접 요청 완료 `02:54:47.755329Z`
- 60 requests/min · concurrency 2 · timeout 5초 · 같은 정상 요청 83개
- heap·hard limit은 앞선 무제한 baseline과 동일하다. 실행 중 설정을 바꾸지 않는다.
- SSM `064ca1d7-e95b-4ef3-b6e8-de179a2f21f0`, 원본 결과 디렉터리
  `/opt/runninggu-validation/8g-baseline-d884d36-20260904`
- 예정/완료 요청 **1800/1800**, missed·실패·no valid point·5초 초과 모두 **0**
- p50 **0.00885초**, p95 **0.05276초**, 최대 **0.38170초**
- 자원 표본 **360/360**, 최소 MemAvailable **45.589%**, swap 사용 증가·swap-in/out 증가 **0**
- 정상 OOM·service/container 재시작·비정상 상태 표본 **0**
- full backup `02:26:48–02:27:37Z` 성공, WAL 검사 `02:29:48/02:39:48/02:49:48Z` 모두 성공
- load·metrics·backup·summary exit code가 모두 0이며 SSM `Success`를 재로그인 후 확인했다.
- CloudShell 로그인 만료는 제어 콘솔 접근 문제였으며 이미 실행 중인 EC2 시험은 끝까지 완료됐다.
- 로그 감사 SSM `14c3ed37-2e42-4c8e-a4ca-30475985e29c`도 exit 0.
  Docker의 요청 query·고정 fixture 좌표·AWS access key·email·import 표식은 모두 0이었다.
  GraphHopper GC 746줄은 Docker에만 있고 주 service journal에는 0줄, Full GC 완료는 0건이다.
- cgroup memory peak: GraphHopper **3,209,244,672 bytes**, PostgreSQL **223,494,144 bytes**.
  Spring Boot `MemoryPeak`는 **469,712,896 bytes**. 두 container의 cgroup OOM counter는 모두 0.
- 아래 별도 장애 격리도 통과했다. 실제 hard limit 적용 후의 운영 승인은 여전히 별도다.

### 재부팅 — 통과

- `2026-09-04T03:14:42Z` 재부팅 요청 후 boot ID 변경 확인
- `ExecStartPre` 포함 job **4775.983ms**, GraphHopper activation부터 양쪽 readiness 관측
  **20723.663ms** — 120초 이내
- 동일 graph·image 유지, import 로그 0건, 활성 graph hash 검증 성공
- GraphHopper Docker restart=`no`, PostgreSQL=`unless-stopped`, 두 service `NRestarts=0`
- SSM `da3cf1ed-a263-4a0d-9e0a-50fb341b2a3b`: exit 0

### 장애 격리 — 수정 commit의 helper로 전체 통과

- fixture: `/opt/runninggu-validation/isolation-d884d36-20260904`
- 주 service를 백업하고 별도 graph 복사본·Compose project·loopback 18989를 사용하는 시험 unit으로
  잠시 교체했다. helper는 저장소 구현의 경로만 fixture로 바꿨으며 stop 판정 코드를 별도로 고치지 않았다.
- 정상 경로: 200·의도적 잘못된 profile의 400을 사용하는 privacy 검사 **2회 모두 노출 0줄**.
  정상 stop 뒤 `inactive/Result=success/NRestarts=0`, 불필요한 실패 알림 0건, graph 재검증 성공.
- container stderr 표식: Docker local에만 기록, 주 service journal 중복 0건.
- 예상하지 않은 exit 0: 오류로 분류, 10분 window/3회 제한 뒤 failed 유지, 전용 SNS 발행 성공.
- Compose required 설정 누락: container 생성 전 이유가 journal에 남고 3회 제한·SNS 발행 성공.
- 고의 OOM: 시험 container에 `mem_limit=4GiB`, `memswap_limit=4GiB`, Xms/Xmx=5g·AlwaysPreTouch를
  적용했다. **EC2는 계속 8GiB**였으며 `OOMKilled=true/exit=137`, `NRestarts=3` 뒤 중단을 확인했다.
- 최종 알림 `exit_status=137 restarts=3`과 해당 시험 시각 이후 알림 unit의 `Result=success`,
  `ExecMainStatus=0`을 확인했다. SNS 발행을 확인한 것이며 이메일 수신을 직접 확인한 것은 아니다.
- PostgreSQL 시작 시각 불변·running 유지, 백엔드 재시작 증가 0.
  OOM 이후 `pgBackRest check`와 WAL 검사가 모두 성공했다.
- 같은 artifact로 격리 service 복구 성공. 이후 원래 unit·실제 8989 서비스로 복구했고
  `restore_verify/start/ready/unit`의 종료 코드는 모두 0이었다.
- 시험 container와 network만 제거했다. graph 복사본·로그·이전 release는 보관했다.
- SSM `b8396f98-48d1-4dca-bb83-0f7806a2533c`: 전체 exit 0.

### 최종 실제 상태와 남은 결정

- `2026-09-04T03:20:37Z` 원복 감사: backend·GraphHopper·Docker·Nginx active,
  두 service `Result=success/NRestarts=0`, 실제 image `16b3618f…`, `run` profile 하나, swap 사용 0.
- 최종 SSM `50b42e1d-2b90-4d43-825d-63d9b3125504`: `Success`, exit 0.
- 실제 unit과 PostgreSQL 기존 수정본이 백업과 일치하고 격리 container가 남지 않았음을 확인했다.
  외부 HTTPS도 다시 HTTP 200·TLS 검증 성공이었다. **EC2는 실행 상태로 유지했다.**
- 고의 OOM을 정상 부하에 섞지 않도록 저장된 정상 부하 시작·종료 시각으로 Docker/journal의
  `--since`·`--until` 양쪽을 고정해 다시 감사했다. `log-audit-normal-window.json`을 별도 보관했으며
  좌표·query·키·email·import·OOM·Full GC 표식은 0, Docker GC 746줄·주 service journal GC 0줄로 통과했다.
- **실제 설정은 아직 baseline**: GraphHopper Xms1g/Xmx4g, Docker memory reservation/limit 0;
  Spring Boot Xms256m/Xmx1g, MemoryHigh/MemoryMax infinity. 위 격리 시험 상한을 운영 설정으로 간주하지 않는다.
- 현행 Compose는 `mem_limit`만 지정할 수 있다. Docker는 `memswap_limit`을 생략한 채 memory를
  제한하면 같은 양의 swap을 추가 허용하므로, swap을 불허한 격리 시험과 자동으로 같아지지 않는다.
  [Docker 공식 memory-swap 설명](https://docs.docker.com/engine/containers/resource_constraints/#--memory-swap-details).
- GraphHopper만 swap을 불허하는 설정을 계약·Compose에 추가할지 사용자에게 확인 요청했다.
  승인 전에는 해당 정책과 실제 memory 제한을 임의로 바꾸지 않는다. 호스트 전체 swap은 유지한다.
- 정책 확정 뒤 측정 기반 상한 적용과 동일 시나리오 재시험이 남았다. 4GiB 인스턴스 시험은 하지 않았다.

## 사용자 승인 후 측정 기반 제한 후보 — 재시험 전

2026-09-04 사용자가 GraphHopper만 swap을 금지하는 계약·Compose 수정과 제한 적용 재시험을 승인했다.
호스트 swap 4GiB와 EC2 8GiB는 유지한다. 다음은 합격값이 아니라 **재시험 후보**다.

| 대상 | baseline 관측 peak | 기존값 | 재시험 후보 |
|---|---|---|---|
| GraphHopper | 3,209,244,672 bytes | Xms1g/Xmx4g, reservation/limit 0 | Xms1g/Xmx3g, reservation 3g, memory 4g, memory+swap 4g |
| Spring Boot | 469,712,896 bytes | Xms256m/Xmx1g, High/Max infinity | heap 유지, MemoryHigh=1G, MemoryMax=1536M |
| PostgreSQL | 223,494,144 bytes | hard limit 없음 | 그대로 보호, 백업 포함 관측 |

GraphHopper는 측정된 약 2.99GiB cgroup peak에 native·file cache 여유를 두는 4GiB 상한을
시험하며 heap은 3GiB로 낮춘다. reservation 3GiB는 예약 할당이나 보장 RAM이 아니라 메모리 압력 시
회수 기준이다. Spring은 기존 1GiB heap을 유지하면서 native 여유를 포함한 1.5GiB 상한을 시험한다.
이론 합계로 host 합격을 판정하지 않으며 실제 표본·GC·OOM·응답·재부팅·고장 격리로 판단한다.
image·graph·profile·고정 정상 요청 목록은 그대로이므로 기존 로컬 기준선을 사용한다.
