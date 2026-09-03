# GraphHopper 2026-09-01 release — 로컬 생성·검증 기록

> 상태: **EC2 사양 미승인**. 이 문서는 날짜 고정 입력, clean commit builder, artifact 계약,
> 로컬 server·회귀와 도구 검증만 기록한다. 8GiB §9.2와 hard-limit §9.3 결과로 사용하지 않는다.

## 입력과 builder

| 항목 | 값 |
|---|---|
| builder source commit | `5534bdf` |
| builder 실행 주체 | `codex-release-builder@5534bdf` |
| PBF source | `https://download.geofabrik.de/asia/south-korea-260901.osm.pbf` |
| PBF 기준일 | `2026-09-01` |
| PBF size | `286403403` bytes |
| PBF 공식 MD5 | `3f6596139dedbbe2f6b2b9a4ff486c63` — 다운로드 파일과 일치 |
| PBF SHA-256 | `848daadc56b2c2a808b30b2778f834c2802097ab382805c9fd42f248f4d6284b` |
| builder image digest | `sha256:840afb43d7508bf45e7aef24ec27e3e4c6d67ca2ad36bd77852e0b09c43f842d` |
| import heap | `-Xms1g -Xmx8g` — builder 전용, EC2 server 사양 근거 아님 |

builder는 detached clean worktree `5534bdf`에서 실행했다. host Docker는 Linux/x86_64였고 builder가
고정한 `linux/amd64` image를 사용했다.

## release artifact

| 항목 | 값 |
|---|---|
| artifact ID | `gh11-korea-20260901-2ff6731b181a-e8455015b02c` |
| manifest SHA-256 | `17fbee518da5a5e59d463fa3a09331c0c19c5ae0d7f6043fd8c60efaa236c69a` |
| build input SHA-256 | `2ff6731b181ac6f6c32efa9ad84129dbd75925c7c47e21156638fd8c57240c95` |
| graph files SHA-256 | `e8455015b02c1c8cd98d4ad54b96a615f54ddb2a764d2a4916425960d547db74` |
| archive SHA-256 | `03bfefb440f8f2febbd771f2bcf245a6759014c3a471f3333c9c1b28eaab7d7d` |
| archive size | `275308482` bytes |

`verify`를 bundle에 다시 실행했고 `backend/graphhopper/graph-release.json`의 environment·artifact ID·
manifest hash·build input hash까지 대조해 통과했다. 실제 세 파일은 공개 저장소에 넣지 않고 로컬
gitignore 경로에만 보관한다.

## 로컬 server와 회귀

운영 `graphhopper-server.yml`, GraphHopper 11.0 server image, `-Xms1g -Xmx4g`를 사용했다. 이 heap은
로컬 스모크용이고 EC2 승인값이 아니다.

- 내부 round-trip readiness: 성공
- `roundtrip.py --preset caps --zone all`: `83/90`
  - 수도권 `58/60`
  - 지방 `25/30`
  - 선택 경로 계단 비율 최대 `0.46%`, 1% 초과 `0건`
- 정상 종료 후 활성 tree 재검증: 성공
- SIGKILL 후 활성 tree 재검증: 성공

아래 15초 결과는 계약 #282 반영 전의 구 batch 도구를 도착률 60/min, concurrency 2, seed 16으로
실행한 기록이다. 실제 graph에 고정 도착률을 주고 실패 종료 code를 확인한 과거 근거로만 보존하며,
현재 §9.1의 고정 정상 직접 요청 세트나 §9.2 합격 증거로 재사용하지 않는다.

| 항목 | 결과 |
|---|---|
| 예정/완료 batch | `15/15` |
| missed start | `0` |
| 직접 요청 | `240` |
| 실패·5초 초과 | `0` |
| 직접 요청 p50/p95/max | `0.024315s` / `0.055737s` / `0.079454s` |
| seed batch p50/p95/max | `0.369715s` / `0.655574s` / `0.655574s` |

이 수치는 로컬 16GiB Docker Desktop 결과라 EC2 사양 판정에 사용하지 않는다. 도구가 고정률 실행,
지연시간 집계, 실패 종료 code를 실제 graph에 대해 수행함을 확인한 결과다.

## 코드 검증

- `scripts/osm/test_operational_load.py`: 현재 고정 정상 요청 계약 11개 통과
- `scripts/osm/test_roundtrip_evidence.py`: 회귀 evidence·`no valid point` 분류 3개 통과
- `scripts/osm/import/test_artifact_contract.py`: 9개 통과
- `backend/deploy/validation/test_summarize_runtime_metrics.py`: 6개 통과
- `backend/deploy/validation/test-collect-runtime-metrics.sh`: Linux Alpine 통과
- 수정 shell 3개: ShellCheck 0건
- EC2 Compose `config --quiet`: 성공
- GraphHopper Java 21 unified GC option 실제 기동: 성공
- `backend/gradlew.bat test bootJar --no-daemon`: 성공

## 남은 운영 증거

- 비공개 S3·KMS·IAM 생성과 세 파일 업로드
- exact release commit의 CI backend artifact 설치
- 동일 artifact·server image로 현재 `roundtrip.py --evidence` 로컬 기준선 생성
- 8GiB heap 후보와 정상 직접 요청 도착률·동시성 팀 확정
- 8GiB 30분 부하·백업·WAL·GC·재부팅 §9.2
- 실측 hard limit 확정과 격리된 §9.3
- 4GiB를 선택하려면 같은 전체 시나리오 3회 연속 통과
