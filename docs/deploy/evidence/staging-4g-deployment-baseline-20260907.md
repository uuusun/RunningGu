# 4GiB 배포 기준 정리 — 2026-09-07

사용자는 기존 EC2를 **4GiB로 고정**하고 배포 설정·운영 문서도 같은 값으로 맞추도록 확정했다.
실제 서버에서 이미 검증한 값을 저장소에 반영해 다음 배포 때 재현하도록 정리했다.
현재 운영 기준은 [메모리 계약 §8.1](../graphhopper-artifact-contract.md#81-적용-순서)이다.

## 결정 근거와 적용 범위

- [1,855건 부분 부하](api-load-ec2-4g-20260907-no-kto.md): 전건 성공, 부하 구간 최저 가용 RAM
  26.493%, OOM·비정상 재시작 없음, 백업·WAL·종료 복귀 확인.
- [245건 보완 부하](api-load-ec2-4g-20260907-kto-supplement.md): 전건 성공, 최저 가용 RAM
  26.922%, 실제 KTO 송신 38회 정상, OOM·비정상 재시작·swap 증가 없음, 백업·WAL·종료 복귀 확인.
- 두 시험은 다른 시각에 실행했다. **2,100건 혼합 부하 합격, 최소 RAM·최대 사용자 수 또는
  프로덕션 전체 출시 승인을 뜻하지 않는다.** 과거 중단·미완료 증거는 보존한다.

| 항목 | 확정값 |
|---|---|
| EC2 | `i-07aa483968f4daddc`, `ap-northeast-2b`, `c7i-flex.large`, 4GiB |
| GraphHopper heap | Xms512m / Xmx2g |
| GraphHopper container | reservation 2g, memory·memory+swap 모두 2560m |
| backend heap | Xms256m / Xmx512m |
| backend systemd | MemoryHigh640M / MemoryMax768M |
| 호스트 | swap 4GiB, swappiness10 |
| PostgreSQL | 추가 hard limit 없음, 기존 백업·WAL 정책 유지 |

## 반영 파일과 이유

- `backend/deploy/env/compose.env.example`: 비어 있던 GraphHopper heap과 과거 무제한 상한을
  승인된 4GiB 값으로 채웠다.
- `backend/deploy/env/application.env.example`, `backend/deploy/systemd/runninggu-backend.service`:
  backend heap과 실측한 systemd 상한을 함께 고정했다.
- `backend/compose.ec2.yaml`: 메모리 변수의 누락·빈 값을 무제한으로 대체하던 동작을 제거했다.
  RAM과 RAM+swap 상한은 같은 변수로 보간한다.
- `backend/deploy/ci/test_compose_memory_policy.py`, `.github/actions/backend-verify/action.yml`:
  실제 배포 예시를 보간해 4GiB 프로필을 검사한다. PC·CI의 임시 heap 값이 예시의 결함을 가리지
  않게 했다. 과거 명시적 무제한 측정·상한 변경·swap 제한 회귀 검사도 유지했다.
- [배포 실행서 §10.1](../aws-ec2-staging-runbook.md#101-4gib-운영-메모리-확인): 기존 비밀값을
  예시 파일로 덮어쓰지 않는 재배포 절차, drop-in 우선순위와 실효 값 확인을 추가했다.
- 메모리 계약·부하 시험 계획·기본 운영 점검 계획·출시 지침에서 현재 4GiB 운영 결정과 과거
  8GiB 후보 선정·미완료 시험을 구분했다.

HTTP API·DB 스키마·Android·제품 로직·SPEC 변경은 없다. EC2 밖의 graph import용 로컬
Compose·builder heap은 유지한다. 대회 snapshot Importer는 Spring을 중지하고 순차 실행한다.

## 실제 서버 적용 여부

[2026-09-07 01:59:45Z 종료 확인](api-load-ec2-4g-20260907-kto-supplement-postflight.json)에
기록된 JVM 인자·Docker 상한·systemd 실효 값이 이번 배포 예시와 일치함을 로컬 대조로 확인했다.
서버는 이미 같은 4GiB 설정이므로 이번 정리에서 EC2 재시작·사양 변경·앱 재배포를 수행하지 않았다.
현재 backend `673a2f796052f4553113d5cd25608fb3821222ac`와 graph
`gh11-korea-20260901-2ff6731b181a-2b8515dd29fc`를 기준으로 한 증거다.

다음 정식 배포에서는 같은 commit의 env 예시·unit을 사용하고 기존 서버 파일의 실효 값을
실행서대로 확인한다. 기존 `memory.conf`가 같은 값을 지정하면 보존한다. 최초 마무리는 로컬
수정·검증까지였고, 이후 사용자의 저장소 반영 요청에 따라 이 문서와 관련 설정·도구·증거를
커밋·push하고 `develop` 대상 PR로 제출한다. 머지는 리뷰 승인 후의 별도 단계다.

## 로컬 검증

JDK 21.0.11, 로컬 Docker Engine 29.7.2·Compose 5.4.0, Codex 번들 Python을 사용했다.

| 검사 | 실제 실행 결과 |
|---|---|
| `python backend/deploy/ci/test_compose_memory_policy.py` | 1개 클래스·테스트 실행 9개, 2회 모두 통과 |
| `python -m unittest discover -s scripts/api -p 'test_*.py'` | 14개 클래스·테스트 실행 86개, 2회 모두 통과 |
| `python backend/deploy/validation/test_summarize_runtime_metrics.py` | 14개 테스트, 2회 모두 통과 |
| `python backend/deploy/validation/test_summarize_upstream_load_guard.py` | 7개 테스트, 2회 모두 통과 |
| `sh backend/deploy/validation/test-collect-runtime-metrics.sh` | Ubuntu 22.04 WSL에서 2회 모두 통과 |
| `sh backend/deploy/graphhopper/test-start-graphhopper-compose.sh` | Ubuntu 22.04 WSL에서 종료 분류 10개, 2회 모두 통과 |
| 수정한 운영 문서 5개의 상대 파일 링크·실제 EC2 기록 대조 | 누락 없음, JVM·메모리 값 일치 |
| backend 디렉터리에서 `gradlew.bat test bootJar --console=plain` | Docker 복구 후 **BUILD SUCCESSFUL**, 1분 54초. 83개 클래스·테스트 실행 441개, 실패·오류·건너뜀 모두 0. `bootJar`는 변경 없는 최신 산출물로 확인(`UP-TO-DATE`) |
| `git diff --check` | 통과 |

배포 검사에는 heap·상한 변수를 하나씩 누락/빈 값으로 만드는 실패 사례와 GH heap 확대,
backend heap 변경·상한 무제한 복귀, GraphHopper swap 상한 분리를 거부하는 사례가 포함된다.
단순히 정상 예시만 읽고 통과시키지 않는다.

첫 Compose 테스트는 Windows 샌드박스의 임시 디렉터리 접근 제한으로 실패했고, 같은 테스트를
정상 임시 경로 권한으로 2회 실행해 통과했다. 셸 수집기 검사는 Linux `/proc/vmstat`를 사용하므로
Git Bash에서는 실행할 수 없어 Ubuntu WSL에서 2회 검증했다.

첫 backend 테스트는 로컬 Docker 엔진이 시작되지 않아 429개 중 173개가 실패했다.
Docker 로그에서 오래된 0바이트 런타임 소켓 접근 오류를 확인했다. 해당 임시 폴더를 같은 PC에
백업 이름으로 보존하고 새 폴더에서 Docker를 시작해 엔진 29.7.2 응답을 확인했다.
Docker 데이터·이미지·볼륨·설정은 삭제하지 않았고, 테스트를 제외하거나 코드를 바꿔 실패를 숨기지 않았다.
복구 후에는 초기화 단계에서 막혔던 브라우저 검사를 포함한 441개 실행이 모두 통과했다.

위 표는 로컬 검증 결과다. 후속 원격 CI 결과는 이 변경을 제출한 PR의 checks를 따른다.
새 commit을 EC2에 배포한 기록은 아니며, 실제 서버의 부하 검증은 위 두 실행 증거를 사용했다.
이 문서 작성과 PR 준비 중 추가 부하 요청은 보내지 않았다.
