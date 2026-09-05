# GraphHopper 2026-09-01 release — 로컬 생성·검증 기록

> 상태: **8GiB 운영 검증 전**. 이 문서는 날짜 고정 입력, 고정 commit builder, artifact 계약,
> 로컬 server·회귀와 도구 검증만 기록한다. 8GiB §9.2와 hard-limit §9.3 결과로 사용하지 않는다.

## 입력과 builder

| 항목 | 값 |
|---|---|
| builder source commit | `6e0c713` |
| builder 실행 주체 | `codex-release-builder@6e0c713` |
| PBF source | `https://download.geofabrik.de/asia/south-korea-260901.osm.pbf` |
| PBF 기준일 | `2026-09-01` |
| PBF size | `286403403` bytes |
| PBF 공식 MD5 | `3f6596139dedbbe2f6b2b9a4ff486c63` — 다운로드 파일과 일치 |
| PBF SHA-256 | `848daadc56b2c2a808b30b2778f834c2802097ab382805c9fd42f248f4d6284b` |
| builder image digest | `sha256:ee2d69981cfb13fc874b642923cb70e40c3548c67e01ce3610dbfcc47403f2a6` |
| import heap | `-Xms1g -Xmx8g` — builder 전용, EC2 server 사양 근거 아님 |

builder는 commit `6e0c713`에서 실행했다. host Docker는 Linux/x86_64였고 builder가
고정한 `linux/amd64` image를 사용했다.

## release artifact

| 항목 | 값 |
|---|---|
| artifact ID | `gh11-korea-20260901-2ff6731b181a-2b8515dd29fc` |
| manifest SHA-256 | `7a529bf90f18da4bdc3fe640928a485ff859f499242e3164d2e59b19fe0b1557` |
| build input SHA-256 | `2ff6731b181ac6f6c32efa9ad84129dbd75925c7c47e21156638fd8c57240c95` |
| graph files SHA-256 | `2b8515dd29fce9afdc7aed840554f048a7d131f959c61fa788e5947919720000` |
| archive SHA-256 | `64c736e9df0b689e5e9926e9238777b817bbe84d664566b1c7e7964a3fb0c932` |
| archive size | `275308474` bytes |

`verify`를 bundle에 다시 실행했고 `backend/graphhopper/graph-release.json`의 environment·artifact ID·
manifest hash·build input hash까지 대조해 통과했다. 실제 세 파일은 공개 저장소에 넣지 않고 로컬
gitignore 경로에만 보관한다.

동일한 PBF·SRTM·설정·JAR·builder image로 독립 import한 두 graph에서 GraphHopper가 기록하는
`datareader.import.date`와 `prepare.lm.date.run`만 달라지는 결정성 결함을 발견했다. builder가 두 값을
고정 시각 `1970-01-01T00:00:00Z`로 정규화하도록 수정한 뒤 두 graph의 10개 파일과 package 결과를
대조했다. artifact ID·graph files hash·archive hash·archive size가 모두 일치했다. 정규화된 graph는
GraphHopper 11.0 server image로 로드하고 실제 round-trip 200 응답과 정상 종료 후 재검증까지 확인했다.

## 로컬 server와 회귀 — 2026-09-04 재검증

최종 artifact의 graph, 운영 `graphhopper-server.yml`, GraphHopper 11.0 server image
`sha256:53d5afeacb080784524dbccf5dbb7af58e1a23416ea2e7e66cb85b98fd5313fd`,
`-Xms1g -Xmx4g`를 사용했다. 이 heap은 로컬 스모크용이고 EC2 승인값이 아니다. Windows bind mount의
권한 차이 때문에 이 로컬 container만 UID 0으로 실행했다. EC2 UID 10001의 쓰기 권한 검증을 대체하지 않는다.

`python -X utf8 scripts/osm/roundtrip.py --preset caps --zone all --seeds 16 --evidence ...`로
[전체 기준선과 고정 정상 직접 요청 세트](graphhopper-routing-gh11-korea-20260901-2ff6731b181a-2b8515dd29fc.json)를 생성했다.
첫 실행은 Windows CP949 출력 오류로 evidence 생성 전에 실패했으며, UTF-8 모드로 다시 실행했다.

| 항목 | 결과 |
|---|---|
| 전체 직접 요청 | `1440` — HTTP 200 `1431`, `NoValidPoint` HTTP 400 `9` |
| 품질 상한 통과 셀 | `83/90` — 수도권 `58/60`, 지방 `25/30` |
| 고정 정상 직접 요청 | `83` — 각 합격 셀의 대표 seed 하나 |
| 선택 경로 계단 비율 | 최대 `0.46%`, 1% 초과 `0건` |

400 9건은 개별 라우팅 실패로 기록했으며 성공으로 바꾸지 않았다. 실패·품질 탈락 셀도 전체 기준선에
보존한다. EC2에서는 동일한 artifact와 server image로 전체 기준선을 다시 실행해 비회귀를 판정한다.

고정 정상 요청 83개 전체를 최소 한 번 포함하도록 로컬 부하 스모크를 90초, 도착률 60/min,
concurrency 2, read timeout 5초로 실행했다.

| 항목 | 결과 |
|---|---|
| 예정/완료 직접 요청 | `90/90` |
| missed start | `0` |
| 실패·400·5초 초과 | 모두 `0` |
| 직접 요청 p50/p95/max | `0.032439s` / `0.084699s` / `0.125105s` |
| 부하 도구 종료 code | `0` |

- 정상 종료 후 활성 tree hash·release descriptor 재검증: 성공, `gh.lock` 없음
- 재기동 후 SIGKILL → 활성 tree 재검증: 성공, 이 실행에서는 잔존 `gh.lock` 없음
- SIGKILL 뒤 다시 기동 → 고정 정상 직접 요청 3건: `3/3` 성공, 400·5초 초과 `0`
- 검증 전용 container 정상 종료·삭제: 완료. graph와 bundle 파일은 보존

이 수치는 로컬 Docker Desktop 결과라 EC2 사양 판정에 사용하지 않는다. 8GiB에서 Spring Boot·DB·백업을
함께 실행하는 30분 정상 부하 및 장애 격리 검증은 별도로 남아 있다.

## 운영 읽기 전용 사전 점검 — 2026-09-04

- 서울 리전 `m7i-flex.large` staging instance: 실행 중, Ubuntu 24.04, SSM Online
- Spring Boot·GraphHopper·Nginx·Docker: active, PostgreSQL container: healthy
- 활성 backend release: `bb8621f9c88a8ff7f6a1c9ebb8f3ec2f999560de`
- 활성 graph: 이전 `gh11-korea-20260901-2ff6731b181a-e8455015b02c` — 위 새 artifact는 아직 배포하지 않음
- 단일 무부하 snapshot: MemTotal 7776MiB, MemAvailable 5419MiB, swap 사용 0MiB
- `https://staging-api.runninggu.store/api/contests?size=1`: HTTP 200, TLS 인증서 검증 성공

서비스 상태와 메모리 한 시점만 확인한 결과이므로 §9.2 합격이나 4GiB 축소 근거로 사용하지 않는다.
이번 사전 점검에서 EC2 설정·서비스·S3 object는 변경하지 않았다.

## 코드 검증

- `scripts/osm/test_operational_load.py`: 현재 고정 정상 요청 계약 11개 통과
- `scripts/osm/test_roundtrip_evidence.py`: 회귀 evidence·`no valid point` 분류 3개 통과
- `scripts/osm/import/test_artifact_contract.py`: 9개 통과
- `scripts/osm/import/test_normalize_graph_metadata.py`: 2개 통과
- `backend/deploy/validation/test_summarize_runtime_metrics.py`: 6개 통과
- `backend/deploy/validation/test-collect-runtime-metrics.sh`: Linux Alpine 통과
- 수정 shell 3개: ShellCheck 0건
- EC2 Compose `config --quiet`: 성공
- GraphHopper Java 21 unified GC option 실제 기동: 성공
- `backend/gradlew.bat test bootJar --no-daemon`: 성공
- `6e0c713` GitHub CI: Spec sync·backend unit/integration/bootJar·Linux/Windows 데이터 결정성 4개 성공

## 남은 운영 증거

- 기존 비공개 S3·KMS·IAM 설정 재대조와 새 artifact 세 파일 업로드
- exact release commit의 CI backend artifact 설치
- 동일 artifact·server image로 EC2 전체 회귀를 실행하고 위 로컬 기준선과 대조
- 8GiB heap 후보와 정상 직접 요청 도착률·동시성 팀 확정
- 8GiB 30분 부하·백업·WAL·GC·재부팅 §9.2
- 실측 hard limit 확정과 격리된 §9.3
- 4GiB를 선택하려면 같은 전체 시나리오 3회 연속 통과
