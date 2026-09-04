# GraphHopper 그래프 artifact 계약·배포 절차

> 상태: **계약 확정·PR 2 구현 중**. PR 2가 이 문서의 builder·검증 스크립트·systemd unit·Compose
> 변경을 제공한다. 실제 운영 release descriptor와 아래 합격 기준 검증 전에는 EC2 실행 절차를
> 목표 구조로 활성화하지 않는다.
>
> 상위 제품 계약은 [`SPEC.md` §8.4](../../SPEC.md#84-m4-러닝코스--큐레이션--osmgraphhopper-생성-확정결정-42),
> 전체 EC2 절차는 [`aws-ec2-staging-runbook.md`](aws-ec2-staging-runbook.md)를 따른다.

## 1. 결정과 범위

GraphHopper의 **그래프 생성(import)** 과 **그래프 조회(server)** 를 물리적으로 분리한다.

```text
권한 있는 팀원의 PC
  └─ 저장소에 고정된 Linux builder image·스크립트
       └─ 대한민국 PBF + SRTM → run+LM 그래프
            └─ manifest + checksum + graph archive
                 └─ 서울 리전 KMS 암호화 비공개 S3
                      └─ EC2 다운로드·검증·활성화
                           └─ GraphHopper server만 실행
```

고정 결정은 다음과 같다.

1. EC2에서는 GraphHopper import를 실행하지 않는다. EC2 Compose에는 import service를 두지 않는다.
2. builder의 기준은 특정 개발 PC가 아니라 저장소에 고정된 Linux container image와 실행
   스크립트다. 권한 있는 팀원이면 누구나 같은 입력으로 만들 수 있어야 한다.
3. EC2 GraphHopper server에는 PBF와 SRTM cache를 전달하거나 mount하지 않는다.
4. 운영 프로파일은 `run` 하나이고 LM을 유지한다. `foot`은 PoC 비교 설정에만 남긴다.
5. 그래프 artifact는 비공개 S3를 통해서만 전달한다. 앱·공개 GitHub artifact·공개 URL로
   배포하지 않는다.
6. OSM graph artifact의 비공개 내부 전달과 공개 경로 결과의 attribution은 별개다. 전자는
   [조직 내부 사용](https://osmfoundation.org/wiki/Licence/Licence_and_Legal_FAQ)이고, 후자는 기존
   계약대로 `© OpenStreetMap contributors`를 표시한다.
7. GraphHopper graph import와 `runninggu-contest-import.service`가 수행하는 대회 snapshot
   import를 문서와 운영 기록에서 구분한다.

## 2. 역할과 권한

| 역할 | 책임 | 권한 |
|---|---|---|
| builder 실행자(2명 이상) | 고정 입력 확인, builder 실행, manifest·checksum 생성, S3 업로드 | graph artifact prefix `PutObject` 최소 권한 |
| 배포 운영자 | EC2 다운로드, 검증, 활성화, 스모크, 롤백 | SSM, graph artifact prefix `GetObject`, GraphHopper 제어 |
| 백엔드 담당(유선경) | builder·검증 스크립트와 GraphHopper 설정 유지 | 저장소 코드·문서 변경 |
| 운영책임자(유선경) | S3·KMS·IAM·비용·보관 정책 승인 | AWS 인프라 관리 |

개발자 PC에 AWS 정적 키를 파일이나 저장소로 남기지 않는다. SSO 또는 만료되는 자격 증명을
사용하고 업로드 권한은 graph artifact prefix로 제한한다. EC2 instance profile에는 다운로드에
필요한 `GetObject`와 KMS decrypt만 준다.

공모전 제출 전 builder 실행 권한을 가진 팀원 두 명이 서로 다른 PC에서 exact commit을 checkout해
각각 build·package·검증·회귀 시험을 끝까지 수행한다. 이 훈련의 합격 기준은 **담당자나 host JDK에
의존하지 않고 두 사람이 모두 artifact를 복구 생산할 수 있음**과 각 artifact가 검증·회귀를 통과하는
것이다. 고정 입력에서 계산되는 `buildInputSha256`의 단순 동일성은 독립적인 재현성 증거로 세지
않는다. 생성 시각과 GraphHopper 내부 기록 때문에 graph file hash가 달라질 수 있으므로 byte
동일성도 사전 계약으로 가정하지 않는다. 두 결과는 별도 artifact로 보관하고 실제 배포할 하나의
manifest hash를 release descriptor로 고정한다.

pgBackRest 백업과는 **패턴만 재사용**한다. bucket 또는 prefix, lifecycle, IAM 정책은 분리한다.
DB 백업의 7일 PITR 보존과 graph artifact의 2세대 롤백 보존을 한 lifecycle로 묶지 않는다.

## 3. builder 재현 계약

PR 2는 저장소에 다음 진입점·고정 입력·공통 구현을 제공해야 한다. 경로와 이름을 바꾸면 이 문서를
같은 PR에서 수정한다.

```text
scripts/osm/import/
├── Dockerfile
├── ImportConfigNormalizer.java
├── artifact_contract.py
├── build-graph.sh
├── build-graph.ps1
├── graphhopper-import.yml
├── normalize-import-config.sh
├── normalize_graph_metadata.py
├── package-graph.sh
├── run-builder.sh
├── test_artifact_contract.py
├── test_normalize_graph_metadata.py
└── verify-artifact.sh

scripts/osm/
├── operational_load.py
├── roundtrip.py
├── roundtrip_cases.py
├── test_operational_load.py
└── test_roundtrip_evidence.py

backend/graphhopper/
└── graphhopper-server.yml

backend/deploy/graphhopper/
├── check-server-log-privacy.py
├── install-graph-artifact.sh
├── test-start-graphhopper-compose.sh
└── verify-active-graph.sh

backend/deploy/common/
└── read-required-env.sh

backend/deploy/validation/
├── collect-runtime-metrics.sh
├── summarize-runtime-metrics.py
├── test-collect-runtime-metrics.sh
└── test_summarize_runtime_metrics.py
```

검증 로직을 세 군데에 복제하지 않는다. 책임 경계는 다음과 같다.

| 진입점 | 실행 위치 | 단일 책임 |
|---|---|---|
| `verify-artifact.sh` | builder·EC2 | 로컬 bundle 또는 압축 해제 tree의 manifest 형식·canonical hash·archive 안전성을 검증하는 순수 검증기. 네트워크·설치·symlink·서비스 제어는 하지 않음 |
| `normalize_graph_metadata.py` | builder | GraphHopper가 `properties`·`properties.txt`에 기록한 import·LM 실행 시각만 `SOURCE_DATE_EPOCH` 값으로 길이 보존 정규화. PBF data date와 graph payload는 바꾸지 않으며 필수 key가 없거나 중복이면 실패 |
| `install-graph-artifact.sh` | EC2 | release descriptor가 지정한 S3 세 파일을 임시 directory에 받고 `verify-artifact.sh`를 호출한 뒤 안전하게 압축 해제·재검증·최종 version directory rename까지만 수행 |
| `verify-active-graph.sh` | EC2 | `current` 상대 symlink, checkout의 release descriptor, 활성 manifest와 graph tree를 대조하며 공통 hash 검증은 `verify-artifact.sh`에 위임. 다운로드·symlink 변경·서비스 시작은 하지 않음 |
| `read-required-env.sh` | EC2 | dotenv를 shell로 실행하지 않고 요청받은 단일 `KEY=unquoted-value`를 정확히 한 줄만 읽음. 값 내부 `=`는 보존하고 줄 끝 CR은 제거하되 따옴표 값·누락·빈 값·중복 key는 실패하며 다른 secret을 자식 프로세스 환경으로 내보내지 않음 |
| `roundtrip.py --evidence` | 로컬·EC2 | caps/all의 모든 지점·거리·seed status와 품질 지표를 기록하고, 로컬 합격 셀마다 고정 정상 직접 요청 하나를 선택. EC2에서는 `--baseline`으로 로컬 성공 요청과 셀 커버리지 비회귀를 검사 |
| `operational_load.py` | EC2 | 로컬 evidence의 정상 직접 요청 세트를 artifact ID·server image digest와 대조한 뒤 고정 직접 요청 도착률·고정 동시성으로 실행. worker 포화는 대기 대신 missed start로 실패시키고 `no valid point`를 포함한 모든 실패 요청을 기록 |
| `collect-runtime-metrics.sh` | EC2 | 사전 지정한 시간 동안 host 메모리·swap counter·systemd 상태·container 자원을 5초 간격으로 비밀값 없이 기록. 부하 생성·합격 기준 변경·서비스 제어는 하지 않음 |
| `summarize-runtime-metrics.py` | EC2 | 수집 표본 수·최저 `MemAvailable`·swap 증분, 필수 systemd service의 표본별 `active/running`·`NRestarts`, 필수 container의 표본별 존재·상태·OOM·`restart_count`를 계약 §9.2 기준으로 요약하고 기계 판정 가능한 항목이 모두 통과할 때만 종료 code 0 반환 |

`runninggu-graphhopper-verify.service`와 GraphHopper 주 service의 `ExecStartPre`는 모두
`verify-active-graph.sh`를 호출한다. systemd unit 안에 별도 hash 판정 로직을 넣지 않는다.

`roundtrip.py --evidence`의 JSON `schemaVersion=1`은 artifact ID·server image digest·profile·seed
수, 지점·거리 셀별 좌표와 seed별 status·실패 분류·품질 상한 통과 여부·경로 품질 수치,
`normalRequests`를 가진다. 응답 geometry와 GraphHopper 오류 원문은 저장하지 않는다.
`normalRequests`의 각 항목은 지점명·권역·고정 시험 좌표·목표거리·GraphHopper 요청거리·seed를
가지며 `operational_load.py`는 이 목록만 소비한다. 소비자는 실행 전에 caps/all 고정 fixture의 모든
지점·거리 셀과 seed 0..15 결과가 남아 있는지, 합격한 각 셀에서 실제 합격 seed가 정확히 하나씩
`normalRequests`에 선택됐는지 확인한다. 따라서 결과를 본 뒤 셀이나 실패 요청을 함께 삭제한 파일도
부하 입력으로 받지 않는다. schema를 바꾸면 producer와 consumer, 이 문서와 실행서를 같은 PR에서
함께 바꾼다.
설치기와 활성 검증기는 `compose.env` 전체를 `source`하지 않고 `read-required-env.sh`로 각자 필요한
`GRAPHHOPPER_*` key만 읽는다. 운영 `compose.env`는 `KEY=unquoted-value` 형식만 사용하고 값 전체를
작은따옴표나 큰따옴표로 감싸지 않는다. parser는 Windows 편집기에서 생길 수 있는 줄 끝 CR 하나만
정규화하며 Compose의 전체 dotenv 문법을 재구현하지 않는다.
활성 검증기는 manifest·tree hash 뒤 실제 server image를 network·root filesystem read-only로
한 번 실행해 image의 비-root 사용자가 활성 version directory에 쓸 수 있는지도 확인한다. 이는
GraphHopper가 `gh.lock`을 만들 수 있는지 보는 권한 probe이며 graph file을 생성하지 않는다.

- builder base image와 GraphHopper 11.0 JAR의 SHA-256 또는 image digest를 고정한다.
- server image에는 검증기가 읽을 GraphHopper version과 JAR SHA-256을 OCI label
  `org.runninggu.graphhopper.version`, `org.runninggu.graphhopper.jar-sha256`으로 기록한다.
- host OS의 JDK·경로·locale에 의존하지 않는다. Windows·macOS에서도 Linux container 안에서
  같은 명령을 실행한다. macOS·Linux·WSL은 `build-graph.sh`, Windows PowerShell은
  `build-graph.ps1`을 사용하되 두 진입점은 같은 Dockerfile과 container entrypoint를 호출한다.
- `latest` PBF URL을 manifest 입력으로 쓰지 않는다. 날짜가 고정된 URL과 SHA-256을 먼저
  확정한다.
- SRTM tile은 builder에서만 내려받고 사용한 tile 목록과 파일별 SHA-256을 기록한다.
- 생성이 끝난 graph directory를 직접 업로드하지 않고 archive와 checksum으로 패키징한다.
- builder 실행자는 개인을 고정하지 않는다. 생성 주체는 추적을 위해 manifest에 기록만 한다.

## 4. artifact와 manifest 계약

S3의 한 artifact version은 다음 세 파일로 구성한다.

```text
graphhopper/<environment>/<artifact_id>/
├── graph.tar.gz
├── graph-manifest.json
└── SHA256SUMS
```

`artifact_id`는 build 입력 묶음을 식별할 수 있도록 다음 형식을 사용한다. PBF 기준일은
manifest의 `YYYY-MM-DD`에서 `-`를 제거한 `YYYYMMDD`다.

```text
gh11-korea-<PBF 기준일>-<build_input_sha256 앞 12자리>-<graph_files_sha256 앞 12자리>
```

`graph-manifest.json`의 최소 필드는 다음과 같다. 필드명·형식 변경은 artifact 생산자와 EC2
검증기의 계약 변경이므로 문서와 구현을 같은 PR에서 바꾼다.

```json
{
  "schemaVersion": 1,
  "artifactId": "gh11-korea-20260901-0123456789ab-abcdef012345",
  "buildInputSha256": "<JAR·PBF·SRTM 목록·import 설정 hash를 정렬 결합한 SHA-256>",
  "graphhopper": {
    "version": "11.0",
    "jarSha256": "<64자리 lowercase hex>",
    "builderImageDigest": "sha256:<64자리 lowercase hex>"
  },
  "source": {
    "pbfFileName": "south-korea-2026-09-01.osm.pbf",
    "pbfDate": "2026-09-01",
    "pbfSha256": "<64자리 lowercase hex>",
    "srtmProvider": "srtm",
    "srtmFilesSha256": "<정렬한 tile 경로+hash 목록의 SHA-256>"
  },
  "importConfig": {
    "normalizedSha256": "<64자리 lowercase hex>",
    "profiles": ["run"],
    "landmarkProfiles": ["run"]
  },
  "graph": {
    "filesSha256": "<정렬한 graph 상대경로+파일 hash 목록의 SHA-256>",
    "createdAt": "2026-09-01T00:00:00Z",
    "createdBy": "<팀 식별자>"
  },
  "archive": {
    "fileName": "graph.tar.gz",
    "sha256": "<64자리 lowercase hex>",
    "sizeBytes": 123456789
  }
}
```

### 4.1 기동 판정과 기록 필드

`일치 필수`처럼 비교 대상을 생략하지 않는다. EC2 검증기는 아래 source와 실제 값을 비교한다.

| 항목 | 기대값 source | 검증 |
|---|---|---|
| manifest schema version | 검증기가 지원하는 상수 `1` | 다른 값 거부 |
| artifact ID | 배포 인자, S3 prefix 마지막 segment, release descriptor, manifest, 최종 directory 이름 | 다섯 값이 같고 PBF 기준일·build input hash 앞 12자리·graph files hash 앞 12자리로 다시 조합한 ID와 같아야 함 |
| manifest SHA-256 | exact commit의 release descriptor | 다운로드한 `graph-manifest.json` bytes의 SHA-256과 비교 |
| build input SHA-256 | release descriptor와 아래 canonical 재계산 값 | manifest 값과 artifact ID의 해당 12자리를 함께 비교 |
| graph file 목록 SHA-256 | manifest | 압축 해제 tree와 활성 `current` tree에서 각각 재계산하고 artifact ID의 해당 12자리도 비교 |
| GraphHopper version·JAR hash | manifest, server image의 고정 version·JAR hash label | GraphHopper 11.0과 동일 JAR인지 비교 |
| builder image digest | manifest | OCI digest 형식을 검사하고 manifest SHA로 승인값을 고정. EC2에 builder image가 없으므로 로컬 image와 비교하지 않음 |
| PBF 파일명·기준일·SHA-256 | manifest | 형식 검사 후 build input을 재계산. EC2에는 PBF가 없으므로 외부 파일과 다시 비교하지 않음 |
| SRTM provider·tile 목록 hash | manifest | 허용 provider와 hash 형식 검사 후 build input을 재계산. EC2에는 tile이 없으므로 외부 파일과 다시 비교하지 않음 |
| import 영향 설정의 정규화 hash | manifest | hash 형식 검사 후 build input을 재계산. 승인 여부는 release descriptor의 manifest SHA가 고정함 |
| `profiles=[run]`, `landmarkProfiles=[run]` | 이 문서 §1의 운영 정책 | 배열의 순서·값까지 exact 비교 |
| archive SHA-256·size | manifest와 `SHA256SUMS` | 다운로드 직후 두 source 및 실제 파일을 비교 |
| 생성 시각·생성 주체 | 없음 | 형식 검증 후 감사 기록만 남기며 합격값과 비교하지 않음 |

import 영향 설정의 정규화 hash 원본은 운영 builder 입력인
`scripts/osm/import/graphhopper-import.yml`이다. 로컬 PoC 설정
`backend/graphhopper/graphhopper.yml`과 운영 조회 설정
`backend/graphhopper/graphhopper-server.yml`은 이 hash의 입력이 아니다. hash에는 최소한
`datareader.file`의 논리 입력, `import.osm.*`,
`graph.encoded_values`, `profiles`, `profiles_ch`, `profiles_lm`, `prepare.*`,
`graph.elevation.*`, 별도 custom model 파일 내용이 포함된다. `server:`의 port·`bind_host`·logging은
제외한다. allowlist key를 읽어 key 정렬·UTF-8·공백 없는 canonical JSON으로 만든 뒤 hash하며,
`datareader.file`의 host 절대경로는 manifest의 PBF 파일명으로 정규화한다. YAML 문자열 전체
hash를 사용하지 않는다. `graph.elevation.cache_dir`의 host 절대경로는 고정 문자열
`$SRTM_CACHE`로 정규화하고, 실제 tile 내용은 `source.srtmFilesSha256`으로 별도 고정한다. 그 밖의
allowlist 값에서 host 절대경로가 발견되면 임의 치환하지 않고 build를 실패시킨다.

`buildInputSha256`은 아래 네 줄을 표시 순서 그대로, 마지막 newline까지 포함한 UTF-8 bytes로
만든 뒤 계산한 SHA-256이다.

```text
graphhopperJarSha256=<lowercase_sha256>
importConfigSha256=<lowercase_sha256>
pbfSha256=<lowercase_sha256>
srtmFilesSha256=<lowercase_sha256>
```

builder와 EC2 검증기는 이 공식을 각각 구현하지 않고 `verify-artifact.sh`의 공통 구현을 사용한다.

SRTM과 graph file 목록 hash는 POSIX 상대경로의 UTF-8 byte 오름차순으로 정렬한 다음, 각 파일을
아래 한 줄 형식으로 결합한 UTF-8 bytes의 SHA-256이다. 세 필드 사이는 ASCII space 정확히 한
개이고 파일마다 newline 하나가 있으며 마지막 줄도 newline으로 끝난다.

```text
<lowercase_sha256> <decimal_size_bytes> <UTF-8_POSIX_relative_path>\n
```

절대경로·생성 PC 경로·mtime·소유자 정보는 넣지 않는다. `graph.filesSha256` 대상은 archive에
들어간 immutable graph payload만이며 설치 시 옆에 두는 `graph-manifest.json`과 GraphHopper가
런타임에 생성·삭제하는 `gh.lock`은 제외한다. 그 밖의 예상하지 못한 파일이 활성 tree에 있으면
검증을 실패시킨다.

GraphHopper 11은 같은 입력으로 import해도 `properties`와 `properties.txt`의
`datareader.import.date`·`prepare.lm.date.run`에 실제 실행 시각을 기록한다. builder는 package와
`graph.filesSha256` 계산 전에 두 값을 `1970-01-01T00:00:00Z`로 정규화한다. 두 파일의 해당 key가
각각 정확히 한 번 존재하지 않으면 artifact를 만들지 않는다. `datareader.data.date`는 PBF에 들어
있는 원천 시각이므로 바꾸지 않는다.

### 4.2 release descriptor

EC2 검증기가 S3 manifest의 자기 일관성만 확인해서는 승인되지 않은 artifact도 통과할 수 있다.
exact commit에는 비밀값이 아닌 다음 descriptor를 둔다.

```text
backend/graphhopper/graph-release.json
```

```json
{
  "schemaVersion": 1,
  "environment": "staging",
  "artifactId": "gh11-korea-20260901-0123456789ab-abcdef012345",
  "manifestSha256": "<64자리 lowercase hex>",
  "buildInputSha256": "<64자리 lowercase hex>"
}
```

install·verify 스크립트는 checkout의 descriptor, S3 manifest SHA-256, manifest 내부 artifact ID와
build input hash가 모두 일치할 때만 활성화를 허용한다. graph artifact를 독립 갱신해도 descriptor
변경은 PR로 리뷰하고 exact commit을 배포한다.

## 5. EC2 디렉터리와 mount 계약

EC2에는 PBF와 SRTM cache를 설치하지 않는다. graph artifact만 다음처럼 보관한다.

```text
/opt/runninggu-data/graph/
├── gh11-korea-20260901-0123456789ab-abcdef012345/
│   ├── graph-cache files ...
│   └── graph-manifest.json
├── gh11-korea-20261015-abcdef012345-9876543210fe/
└── current -> gh11-korea-20260901-0123456789ab-abcdef012345
```

Compose는 상위 디렉터리를 한 번 mount하고 GraphHopper가 container 안에서 상대 symlink를
해석하게 한다.

```yaml
volumes:
  - type: bind
    source: /opt/runninggu-data/graph
    target: /data/graph
```

server 설정은 `graph.location: /data/graph/current`를 사용한다. bind source로 `current` 자체를
지정하지 않는다. GraphHopper 11이 version directory에 `gh.lock`을 쓰므로 PR 2의 첫 기준은 RW
mount다. read-only 가능 여부는 별도 실측으로만 바꾸고, GraphHopper container UID가 활성 graph
directory에 lock을 만들고 지울 수 있는지 검증한다. installer는 검증을 마친 payload file을
`0444`, directory를 UID/GID `10001:10001`의 `0755`로 설치하고 manifest sidecar만 root 소유
`0444`로 둔다. server 설정의 `graph.elevation.provider: srtm`은 이미 import된 3D graph의 dimension
선언을 위해 유지하되 EC2에는 SRTM cache를 mount하지 않고 server 기동 중 tile 다운로드가 없어야
한다.

보관은 **현재 성공 세대 + 직전 성공 세대** 두 개다. 새 세대가 스모크를 통과하기 전에는 현재나
직전 세대를 지우지 않는다. 실패·미완성 directory는 `current`가 가리키지 않게 하고 별도 정리한다.

### 5.1 로컬 개발 환경

artifact 계약은 EC2 staging·production 배포에 적용한다. 로컬 기능 개발과 PoC에서는
`backend/compose.yaml`의 PBF bind mount, SRTM·graph named volume, 최초 실행 import를 유지할 수
있다. 이 경로는 빠른 개발 편의를 위한 것이며 다음 제한을 둔다.

- 로컬 `latest` PBF와 자동 import 결과를 운영 artifact로 승격하지 않는다.
- 비교용 `foot`을 포함한 `backend/graphhopper/graphhopper.yml`은 로컬 개발·PoC 전용으로 유지할 수
  있다. 운영 builder·server 설정은 이 파일을 import 입력으로 사용하지 않고 `run` profile과 `run`
  LM만 포함한다.
- 로컬 graph directory는 `/data/graph-cache`, 운영 graph는 `/data/graph/current`를 사용하고
  Compose override와 server 설정을 분리한다.
- 로컬 named volume의 존재나 로컬 기동 성공을 builder 재현·EC2 합격 근거로 사용하지 않는다.
- 운영 artifact를 로컬에서 확인할 때는 별도 Compose project와 read/write 가능한 임시 bind
  directory를 사용하고, 기본 개발 named volume을 덮어쓰지 않는다.

### 5.2 기존 EC2 named volume 전환·폐기

현재 Compose의 `runninggu-graphhopper-graph`·`runninggu-graphhopper-srtm` named volume은 manifest가
없는 구 import 결과이므로 새 artifact version directory로 복사하거나 이름만 바꿔 승격하지 않는다.
PR 2 최초 전환은 다음 순서를 지킨다.

1. `docker compose ls`, `docker volume ls`, `docker volume inspect`로 실제 Compose project와 graph·SRTM
   volume의 **정확한 이름**을 기록한다. PostgreSQL volume과 함께 glob으로 다루지 않는다.
2. 새 artifact를 §6.1로 host version directory까지 설치한다. 이 단계에서는 기존 GraphHopper와
   `current`를 건드리지 않는다.
3. 기존 GraphHopper를 중지하고 운영 `compose.ec2.yaml`에서 PBF·SRTM·graph named volume은
   제거되고, base `compose.yaml`의 로컬용 `restart: unless-stopped`는 `restart: "no"`로
   override됐는지 확인한다.
4. `current`를 새 version directory에 연결하고 §6.2의 verify → systemd start → 스모크 → 재부팅을
   통과한다. 실패하면 exact 이전 Compose 설정과 기록한 named volume으로만 롤백한다.
5. 새 artifact의 운영 승인과 직전 artifact 세대 확보가 끝날 때까지 구 named volume을 보관한다.
6. 보관 종료 뒤 운영자가 volume 이름을 다시 inspect해 1번 기록과 같고 PostgreSQL volume이 아님을
   확인한 경우에만 별도 유지보수 작업으로 graph·SRTM volume을 제거한다. PR 2나 일반 배포에서
   `docker compose down -v`로 자동 삭제하지 않는다.

구 volume을 제거하면 그 로컬 import 결과로 즉시 롤백할 수 없다. 제거 시각·대상·새 artifact ID를
운영 기록에 남기며, 복구 기준은 이후부터 현재·직전 artifact 두 세대다.

## 6. 다운로드·검증·활성화

아래 절차는 PR 2의 검증 스크립트와 systemd unit으로만 실행한다. 일부 단계를 수동 압축 해제나
직접 container 기동으로 흉내 내어 우회하지 않는다.

### 6.1 설치 — 실행 중인 서비스에 영향 없음

`backend/deploy/graphhopper/install-graph-artifact.sh`가 다음 1~6만 소유한다.

1. S3에서 manifest·`SHA256SUMS`·archive를 새 임시 directory로 내려받는다.
2. checkout의 release descriptor와 manifest SHA를 먼저 대조하고 `verify-artifact.sh`로
   `SHA256SUMS`·manifest schema·고정 버전·입력 hash·archive hash를 검증한다.
3. archive 항목에 절대경로, `..`, device, FIFO, symlink·hardlink가 없고 단일 graph root 아래의
   일반 파일·directory만 있는지 검사한다.
4. archive를 `/opt/runninggu-data/graph/.staging-<artifact_id>`에 푼다.
5. `verify-artifact.sh`로 압축 해제된 상대경로+파일 hash를 다시 계산해
   `graph.filesSha256`과 비교하고, 검증된 `graph-manifest.json`을 staging root에 read-only sidecar로
   설치한다.
6. 최종 version directory로 rename한다. 부분 생성 directory를 최종 이름으로 덮어쓰지 않는다.

install script는 GraphHopper 중지·`current` 생성/변경·systemd 실행을 하지 않는다. 따라서 artifact
다운로드 실패가 현재 서비스에 영향을 주지 않는다.

### 6.2 활성화·롤백 — systemd가 단일 소유

배포 운영자가 실행서의 명시 절차로 다음 7~10을 수행한다.

7. `runninggu-graphhopper.service`를 **중지한다.**
8. 새 상대 symlink를 임시 이름으로 만든 뒤 rename하여 `current`를 원자적으로 바꾼다.
9. `runninggu-graphhopper-verify.service`를 실행하고, 성공하면
   `runninggu-graphhopper.service`를 시작해 내부 스모크를 실행한다. 주 service도 같은 검증을
   `ExecStartPre`에서 다시 수행하므로 검증 우회 기동은 불가능해야 한다.
10. 실패하면 주 service를 중지하고 직전 symlink로 원복한 뒤 verify → start → 스모크를 반복한다.

실행 중인 GraphHopper를 둔 채 `current`부터 바꾸지 않는다. 이전 프로세스의 open file과
`gh.lock` 정리 대상이 새 symlink와 엇갈리지 않게 하기 위해서다.

활성화 때 verify oneshot과 주 service의 `ExecStartPre`가 같은 tree를 연속 검증하는 것은
의도적이다. 전자는 배포 운영자 게이트이고 후자는 재부팅·직접 기동 우회를 막는 불변 게이트다.
두 검증의 개별 소요시간과 verify 시작부터 readiness까지의 전체 시간을 모두 기록한다.

## 7. 기동 게이트와 재부팅 범위

GraphHopper lifecycle은 **systemd 단일 소유**로 확정한다. PR 2는
`runninggu-graphhopper.service`, 수동 검증용 `runninggu-graphhopper-verify.service`, GraphHopper
전용 실패 알림을 추가한다. EC2 Compose의 GraphHopper `restart`는 `"no"`로 두고 Docker daemon이
직접 복구하지 않게 한다. GraphHopper runtime stdout·stderr의 기준 저장소는 크기가 제한된 Docker
`local` logging driver다. foreground Compose의 runtime stdout 중복은 버리되, container 생성 전
Compose 보간·기동 오류와 `ExecStartPre` 실패 이유를 잃지 않도록 주 service의 stderr는 journal에
남긴다.

```ini
[Unit]
Description=런닝구 GraphHopper server
After=docker.service
Requires=docker.service
OnFailure=runninggu-graphhopper-alert@%n.service
StartLimitIntervalSec=600
StartLimitBurst=3

[Service]
Type=simple
User=root
WorkingDirectory=/opt/runninggu/repository/backend
ExecStartPre=/bin/sh /opt/runninggu/repository/backend/deploy/graphhopper/verify-active-graph.sh
ExecStart=/bin/sh /opt/runninggu/repository/backend/deploy/graphhopper/start-graphhopper-compose.sh
ExecStop=/bin/sh /opt/runninggu/repository/backend/deploy/graphhopper/stop-graphhopper-compose.sh
Restart=always
RestartSec=5
# 검증 전용 초기 후보. §9.1 cold-cache 실측 뒤 전체 120초 예산 안에서 확정한다.
TimeoutStartSec=60s
# Compose stop_grace_period 30초보다 길어야 한다.
TimeoutStopSec=45s

NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=full
UMask=0027

StandardOutput=null
StandardError=journal
SyslogIdentifier=runninggu-graphhopper

[Install]
WantedBy=multi-user.target
```

위 unit 조각은 책임과 재시작 계약을 고정한 초안이다. PR 2에서는 `systemd-analyze verify`, 실제
Compose 종료 code, 정상 stop, cgroup OOM을 검증한 exact unit을 제공한다. Docker socket 접근은
root와 동등한 권한이므로 별도 docker group 계정을 만들지 않고 기존 Docker 제어 unit처럼
`User=root`를 명시한다. `docker compose up`은
`-d` 없이 foreground로 실행하고 `--exit-code-from graphhopper`로 container 종료 code를 전달해
systemd가 실패를 관찰할 수 있어야 한다.

`Restart=always`는 GraphHopper JVM 또는 Compose가 예상하지 않게 exit 0으로 끝나는 경로도
복구한다. 명시적인 `systemctl stop`에는 restart가 적용되지 않는다. PR 2는 정상 stop, 예상하지
않은 exit 0, cgroup OOM/exit 137, 10분 start limit 소진에 더해 container 생성 전 Compose 보간
실패가 주 service journal에 원인을 남기는지 각각 검증한다.

Compose v2가 foreground container stderr를 자기 stderr로 릴레이하므로 PR 2는 `ExecStart`를
`start-graphhopper-compose.sh` wrapper로 감싼다. wrapper는 Compose stderr를 private `/tmp` 파일로
받아 runtime 중에는 journal로 릴레이하지 않는다. `StandardError=journal`은 `ExecStartPre` 오류를
위해 유지하며, wrapper는 Compose 실패 때만 종료 code와 민감정보를 제거한 마지막 stderr 일부를
`logger -t runninggu-graphhopper`로 남긴다. `stop-graphhopper-compose.sh`가 만든
`RuntimeDirectory` marker가 있을 때만 `0`·`130`(SIGINT)·`143`(SIGTERM)을 정상 stop의 `0`으로
바꾼다. marker가 있어도 `137`(OOM/SIGKILL)과 그 밖의 오류는 성공으로 바꾸지 않는다.
marker 없는 exit 0은 실패로 바꿔 bounded restart와 알림 대상이 되게 한다.
`test-start-graphhopper-compose.sh`는 실제 Docker·journal 없이 이 분기를 회귀 검사한다.

Docker 로그의 크기 제한만으로 개인정보 보호를 충족하지 않는다. 운영 server 설정은
`server.request_log.appenders: []`로 query string 접근 로그를 끄고,
`logging.loggers.com.graphhopper.resources.RouteResource: WARN`으로 GraphHopper 11이 INFO에
기록하는 좌표·User-Agent를 차단한다. JVM GC·기동·다른 오류 logger는 유지한다.
격리 container에서 `check-server-log-privacy.py`로 고정 공개 fixture의 200·의도적 400을 확인하고
두 요청의 좌표·User-Agent가 Docker 로그에 없는지 검사한다. 의도적 400은 정상 부하 입력이 아니다.
이 검사만으로 모든 로그의 비밀값 검사를 대신하지 않으며 §9.2의 journal 점검도 유지한다.

`runninggu-graphhopper-verify.service`는 `Type=oneshot`과 같은
`verify-active-graph.sh`만 가지며 enable하지 않는다. 배포·감사에서 명시 실행할 뿐 재부팅 주체가
아니다. `runninggu-graphhopper.service`는 enable하고 모든 기동에서 `ExecStartPre` 검증을 통과해야
한다. 검증·알림 oneshot은 기존 unit 패턴대로 journal과 고유 `SyslogIdentifier`를 사용하지만,
GraphHopper runtime log를 다시 출력하지 않는다. 주 service의 검증 실패 상세가 필요하면 운영자가
verify oneshot을 실행해 journal에서 확인한다.

`TimeoutStartSec=60s`는 `ExecStartPre` 검증에만 적용하는 초기 후보이며 전체 readiness 상한이
아니다. 주 service와 verify oneshot의 최종 검증 timeout은 §9.1 cold-cache 실측 최대값보다 커야
하지만 120초보다 작아야 하고, 측정된 GraphHopper graph load·Spring readiness와 합쳐 §9.2의 전체
120초 예산을 넘지 않게 확정한다.

기존 `runninggu-backup-alert@.service`는 PostgreSQL 백업·WAL 전용 문구를 사용하므로 그대로
재사용하지 않는다. SNS topic을 공유할 수는 있지만 unit과 메시지는 GraphHopper 실패로 구분한다.

호스트 재부팅 때 Docker는 PostgreSQL만 `unless-stopped`로 복구한다. systemd는 Docker 준비 뒤
활성 graph를 검증하고 GraphHopper를 시작한다. 활성 graph가 손상됐으면 server는 시작하지 않고
전용 알림을 보낸다. 명시적인 `systemctl stop`이 아닌 exit 0·실패·cgroup OOM은 5초 간격으로
재시도하되 10분 window에서 start 시도 3회를 소진하면 멈추고 알린다. Spring Boot도 §8.2에서 같은
`StartLimitIntervalSec=600`, `StartLimitBurst=3`을 사용한다.

첫 8GiB 측정에는 GraphHopper hard limit을 바로 켜지 않는다. 실제 peak를 얻은 뒤 §9.3의 별도
fault-isolation을 통과해야 hard limit을 활성화한다. Docker restart guard 같은 별도 daemon은 P0에
추가하지 않는다.

## 8. 메모리 격리와 재시작 정책

### 8.1 적용 순서

1. 8GiB에서 JVM heap을 환경변수화하고 hard cgroup 상한 없이 실제 peak를 측정한다.
2. GraphHopper·Spring Boot의 heap, process working set, PostgreSQL과 backup peak, host
   `MemAvailable`을 같은 5초 표본으로 기록한다.
3. GraphHopper Docker의 `mem_reservation`·`mem_limit`과 Spring systemd의
   `MemoryHigh`·`MemoryMax` 후보는 고정 배수나 `+256MiB` 산식으로 정하지 않는다. 정상 부하가
   각 hard limit에 닿지 않고 §9.2를 통과하는 값을 실제 표본에서 선택해 배포 증거에 기록한다.
4. instance 합격은 process별 상한의 이론 합계가 아니라 §9.2의 host 관측값과 §9.3의 고장 격리로
   판정한다. 같은 메모리를 process reserve와 OS 20%에 중복 계산하지 않는다.
5. 8GiB 정상 부하 합격 뒤에만 같은 요청률·동시성으로 4GiB를 시험한다. 4GiB는 목표 사양이
   아니라 검증 후보이며 정상 부하 합격은 §9.2만으로 판정한다. hard limit 운영 승인에는 instance
   크기 판정과 별도로 §9.3도 통과해야 한다.

GraphHopper hard limit는 Compose `mem_limit`, Spring Boot hard limit는 systemd `MemoryMax`다.
GraphHopper container는 Docker의 별도 cgroup에 있으므로 주 systemd service의 `MemoryMax`로
제한한다고 간주하지 않는다. 두 hard limit 모두 위 측정 없이 숫자부터 고정하지 않는다.
PR 2의 첫 8GiB baseline 설정은 GraphHopper `mem_reservation: 0`·`mem_limit: 0`, Spring Boot
`MemoryHigh=infinity`·`MemoryMax=infinity`로 무제한을 명시한다. §9.3 합격 뒤에만 Compose env와
exact systemd unit을 관측값으로 함께 바꾸고 다시 같은 검증을 수행한다.
PostgreSQL은 첫 격리 대상이 아니라 보호 대상이므로 최초 시험에는 hard limit을 두지 않고 peak와
백업 working set을 reserve로 잡는다. 나중에 PostgreSQL hard limit을 추가한다면 OOM 재시작 loop와
복구 정책을 같은 결정으로 검증한다.

### 8.2 Spring Boot

PR 1 이전 `runninggu-backend.service`의 `StartLimitIntervalSec=0`은 무제한 재시작을 허용했다.
PR 2는 `MemoryMax`만 먼저 추가해 cgroup OOM 뒤 5초 재시작을 무한 반복하지 않도록 다음을 한
단위로 구현·검증한다.

- PostgreSQL 준비 대기를 최대 2분의 `ExecStartPre`로 분리한다.
- `Restart=on-failure`, `RestartSec=5`는 유지하고 `StartLimitIntervalSec=600`,
  `StartLimitBurst=3`으로 10분 window의 start 시도를 3회로 제한한다.
- start limit 도달과 cgroup OOM을 운영 알림으로 구분해 남긴다.
- 측정으로 정한 `MemoryHigh`·`MemoryMax`를 함께 적용한다.

DB가 2분 안에 준비되지 않은 경우 무한 재시작으로 숨기지 않고 배포·부팅 실패로 알린다.

### 8.3 대회 snapshot Importer

4GiB·8GiB 시험 모두 이후 배포에서 다음 순서를 사용한다.

1. Spring Boot를 중지한다.
2. `runninggu-contest-import.service`를 실행한다.
3. 성공 또는 `NO_OP`를 확인한다.
4. Spring Boot를 시작하고 readiness를 확인한다.

Importer 실패 시에도 기존 Spring Boot를 다시 시작할 수 있는지 확인하되, 적용된 Flyway를
임의로 되돌리지 않는다. 계획된 짧은 중단을 허용하지 못한다면 같은 instance에서 동시 실행하지
말고 사양 또는 배포 구조를 다시 결정한다.

## 9. 사전 합격 기준

시작 heap과 hard limit 후보값은 측정하며 바꿀 수 있지만 다음 합격 기준은 측정 전에 고정한다.

### 9.1 시험 시나리오와 요청 세트

라우팅 품질 회귀와 인스턴스 메모리·처리량 판정을 같은 성공률로 섞지 않는다. GraphHopper
`/route` 한 번을 **직접 요청**, 같은 지점·목표거리에서 seed를 순회한 결과를 **지점·거리 셀**로
부른다. HTTP 400 `Could not find a valid point after ... tries`는 GraphHopper가 해당 seed의 내부
경유점을 routable edge에 snap하지 못한 **직접 요청 실패**다. 이를 성공으로 바꾸거나 실패 건수에서
빼지 않는다. 다만 응답을 정상 부하 OOM·timeout·HTTP 5xx와 같은 인프라 장애로 분류하지도 않는다.

EC2 첫 실행 전에 동일한 graph artifact·server image·profile·요청 옵션으로 로컬 운영 호환
container에서 다음 두 결과를 고정한다.

- **회귀 기준선**: `roundtrip.py --preset caps --zone all`의 전체 지점·거리·seed별 status와
  지점·거리 셀별 품질 상한 통과 후보 수를 release evidence에 남긴다. 개별 `no valid point` 400은
  실패로 집계하되, 셀 합격은 16개 seed 중 품질 상한을 통과한 경로가 하나 이상인지로 판정한다.
- **정상 직접 요청 세트**: 회귀 기준선에서 HTTP 200과 비어 있지 않은 `paths`가 확인된 고정
  `point`·`round_trip.distance`·`round_trip.seed` 조합만 부하 입력으로 사용한다. 회귀 기준선에서
  합격한 모든 지점·거리 셀을 최소 한 조합으로 포함하고, exact 목록·GraphHopper 요청 옵션·artifact
  ID·server image digest를 release evidence에 고정한다.

정상 직접 요청 세트는 첫 EC2 결과를 본 뒤 실패 조합을 빼거나 성공 조합으로 교체하지 않는다.
artifact·server image·profile·요청 옵션 중 하나가 바뀌면 로컬 기준선과 세트를 새로 고정하고 EC2
시험도 처음부터 다시 한다. 정상 직접 요청 세트는 메모리·처리량 판정용이고, 전체 회귀 기준선은
라우팅 커버리지 판정용이다. 어느 한쪽 결과를 다른 쪽의 합격 근거로 대신하지 않는다.

1. 위 로컬 회귀 기준선과 정상 직접 요청 세트를 고정한다.
2. cold boot와 GraphHopper 내부 스모크를 수행한다.
3. `roundtrip.py --preset caps --zone all`을 한 번 실행해 warm-up하고 로컬 회귀 기준선과 대조한다.
4. 정상 직접 요청 세트를 읽는 `operational_load.py`를 고정 직접 요청 도착률·고정 동시성으로 30분
   실행한다. worker 포화로 예정된 직접 요청을 시작하지 못하면 기다려 부하를 낮추지 않고 missed
   start로 기록해 실패한다. 이 부하 중 수동 전체 백업 1회와 WAL 감시 3회를 실행한다.
5. `collect-runtime-metrics.sh`로 메모리·swap counter·systemd 상태·`NRestarts`·container
   상태·`restart_count`를 5초
   간격으로 기록한다. GraphHopper Docker local 로그와 Spring journal의 JVM unified GC 로그에서
   readiness 이후 Full GC를 판정하고, verify oneshot과 주 service `ExecStartPre`의 소요시간을 각각
   기록한다.
6. GraphHopper 종료·호스트 재부팅 뒤 같은 artifact로 복구되는지 확인한다.
7. 대회 snapshot Importer는 Spring Boot를 중지한 상태로 별도 실행하고 다시 시작한다.
8. 4GiB 판정은 전체 시나리오를 3회 연속 통과해야 한다.

8GiB와 4GiB 비교에서는 정상 직접 요청 목록·직접 요청 도착률·동시성을 시험 전에 고정해 기록한다.
작은 instance에서 처리가 느려져 자동으로 요청 수가 줄어드는 closed-loop 결과를 같은 부하로
간주하지 않는다.

### 9.2 합격 조건

| 영역 | 합격 조건 |
|---|---|
| OOM | 정상 부하에서 kernel·systemd cgroup·Docker OOM kill 0건 |
| 메모리 여유 | 모든 5초 표본에서 host `MemAvailable`이 전체 RAM의 20% 이상 |
| swap | warm-up 뒤 `vmstat`의 swap-in·swap-out 지속 발생 0, swap 사용량 증가 없음 |
| GC | readiness 이후 반복 Full GC 0건 |
| GraphHopper 직접 요청 | `operational_load.py`의 missed start·실패 요청이 0이고 §9.1의 정상 직접 요청 세트가 모두 HTTP 200·비어 있지 않은 `paths`로 현재 [`application.yml`](../../backend/src/main/resources/application.yml)의 client read timeout 5초 안에 완료. `no valid point`를 포함한 4xx, 5xx, 빈 `paths`, timeout은 모두 실패. 직접 요청 p50·p95·max는 기록하되 합의되지 않은 총 3초 기준을 만들지 않음 |
| 라우팅 회귀 | `--preset caps --zone all`의 모든 직접 요청 status와 `no valid point` 건수를 기록. 로컬 기준선에서 합격한 지점·거리 셀은 EC2에서도 16개 seed 중 품질 상한 통과 경로가 하나 이상이어야 하며 기존 커버리지·거리·상승·차도·회전 상한이 비회귀. 기준선에서 성공한 동일 요청이 EC2에서 400이 되거나 합격 셀의 모든 seed가 실패하면 불합격 |
| 실패 분류 | `no valid point` 400은 직접 요청·라우팅 실패이며 성공 수에 넣지 않음. 다만 이 응답만으로 OOM·instance 부족으로 판정하지 않고 OOM·5xx·timeout·재시작 지표와 분리 기록. 그 밖의 4xx는 요청·profile·배포 설정 오류로 분류해 불합격 |
| 프로파일 | server graph에 `run`과 `run` LM만 존재하고 `foot` 없음 |
| 백업 | 수동 full backup·pgBackRest check·WAL 감시 성공, 실패 알림 0건 |
| 재부팅 | import 로그 0건, 기존 artifact 재사용. 배포는 verify oneshot 시작, 재부팅은 GraphHopper unit activation 시작부터 검증 시간을 포함해 2분 안에 GraphHopper·Spring readiness 성공 |
| 로그 | GraphHopper runtime 기준 저장소는 Docker `local`이며 `docker compose logs graphhopper`로 검사. 주 service journal에는 container runtime stdout·stderr 0건이고 Compose·`ExecStartPre` 실패 이유만 남음. §7 wrapper가 실패 때 남긴 마지막 stderr에도 PBF 내용·AWS 자격 증명·사용자 정보가 없음 |

`MemAvailable 20%`를 충족하려고 cache를 강제로 비우거나, swap을 끄거나, 시험 요청률을 결과에
맞춰 낮추지 않는다. 시험 명령·시작/종료 시각·instance type·heap·hard limit·artifact ID를 결과와
함께 남긴다. graph hash 검증 시간을 줄여 보이려고 시험 직전 page cache를 인위적으로 데우거나
비우지 않는다.

이 절은 인스턴스 사양과 GraphHopper 직접 요청의 시험 계약이다. Spring Boot가 특정 seed 실패 뒤
다음 seed를 계속 호출할지, 일부 후보로 정상·degraded 결과를 반환할지는 제품 동작 계약이므로 이
문서에서 바꾸지 않는다. 해당 동작을 변경하려면 `SPEC.md`·API 계약과 백엔드 테스트를 별도 PR에서
먼저 합의한다.

### 9.3 hard-limit fault-isolation — instance 합격과 별도

이 시험은 8GiB·4GiB 정상 부하 합격표에 섞지 않는다. 선택한 instance와 정상 부하 결과를 바꾸는
시험이 아니라, 측정 뒤 정한 GraphHopper Compose `mem_limit`를 운영에 켜도 장애가 host 전체로 번지지
않는지 확인하는 안전 시험이다. hard limit 활성화 전 다음을 별도 기록으로 모두 통과한다.

1. 격리된 시험 요청으로 GraphHopper만 의도적으로 cgroup OOM에 도달시킨다.
2. `runninggu-graphhopper.service`의 start 시도가 10분 window에서 3회로 제한되어 무한 반복하지
   않고 최종 failed 상태와 GraphHopper 전용 알림을 남긴다.
3. 같은 동안 PostgreSQL이 생존하고 `pgBackRest check`와 WAL 감시가 성공한다.
4. 손상되지 않은 같은 graph artifact로 운영자가 service를 복구할 수 있다.

의도적 OOM은 §9.2의 정상 부하 OOM 0건에 포함하거나 정상 시험 로그와 합치지 않는다. 이 별도
시험이 실패하면 instance 크기와 무관하게 hard limit 배포를 승인하지 않는다.

## 10. 갱신·보관·롤백

- P0 PBF 갱신은 릴리스 일정에 맞춘 수동 작업이다. 자동 최신화하지 않는다.
- 새 PBF·GraphHopper 버전·import 영향 설정 변경은 모두 새 artifact ID를 만든다.
- 현재와 직전 성공 세대를 보관한다. 직전 세대 삭제는 새 세대가 §9를 통과한 뒤에만 한다.
- 스모크 실패는 즉시 직전 symlink로 롤백한다.
- 공개 GitHub release·Actions artifact에 graph archive를 올리지 않는다.
- 비공개 S3 lifecycle과 KMS·전송 비용을 월 예산에 포함한다.

## 11. PR 분리

### 11.1 머지 전 staging 검증용 백엔드 묶음

PR 2의 8GiB 완료 조건을 머지 전에 검증하기 위해, 같은 저장소에서 `develop`을 대상으로 연 PR은
GitHub Actions에 `pr-validation` 백엔드 묶음을 보관할 수 있다. 기존 PR synthetic merge 검사는
유지하며, 그 검사와 SPEC 검사가 성공한 뒤 별도 job이 event에 고정된 PR head SHA를 checkout해
동일한 공통 검사·테스트·빌드·Importer 스모크를 다시 수행한다. 두 job은
`.github/actions/backend-verify/action.yml`의 공통 단계를 사용한다. fork PR은 묶음을 만들지 않는다.

이 묶음의 이름은 `runninggu-backend-pr<PR번호>-<head SHA>-<run ID>-<attempt>`다. 기존 서버 JAR,
Importer JAR, 대회 snapshot, `SHA256SUMS`, `release-manifest.txt`만 포함하며 graph archive는 넣지 않는다.
보관 기간은 기존 통합 묶음과 같은 30일이다. CI는 AWS 자격 증명이나 배포 권한을 갖지 않으며
묶음 생성은 EC2 배포·PR 승인·머지·정식 릴리스가 아니다.

`release-manifest.txt`는 UTF-8 `key=value` 행과 마지막 LF를 사용한다. 검증용 필드는 다음 순서다.

1. `git_commit`: 실제 checkout HEAD이며 event의 PR head SHA와 같아야 함
2. `workflow_run_id`, `workflow_run_attempt`: 양의 정수
3. `artifact_kind=pr-validation`, `allowed_environment=staging`
4. `pull_request_number`: 양의 정수
5. `head_commit`: `git_commit`과 같은 40자리 lowercase SHA
6. `base_commit`: 해당 event의 PR base SHA
7. `integration_test_commit`: 앞선 통합 검사 job이 실제로 checkout한 synthetic merge SHA

생성기는 checkout SHA 불일치, 잘못된 식별자, 필수 payload 누락·예상하지 않은 파일·symlink를 거부하고 세 payload와
manifest의 실제 bytes에 대한 SHA-256을 기록한다. `github.sha`를 PR head로 간주해 라벨만 바꾸지 않는다.
배포 담당자는 성공한 CI run·attempt·PR head를 확인하고 `SHA256SUMS`, manifest, 배포 요청 SHA,
EC2 checkout HEAD가 일치할 때만 staging에 설치한다. PR head가 바뀌었다면 이전 묶음을 새 검증에 쓰지 않는다.

production에는 `pr-validation` 묶음을 사용하지 않는다. 머지 후에는 통합된 commit의 기존 push CI
묶음을 새로 생성한다. 검증용 묶음을 이름만 바꿔 승격하지 않으며, 테스트한 코드·설정·graph가 변경되면
해당 운영 검증을 다시 수행한다. `main`은 기존 Git 컨벤션대로 정식 릴리스 때만 변경한다.

### 11.2 PR별 완료 조건

| PR | 범위 | 완료 조건 |
|---|---|---|
| PR 1 | 이 계약, SPEC §8.4, 상위 배포 가이드, EC2 실행서 동기화 | 팀 결정과 문서 충돌 없음 |
| PR 2 | builder·manifest·설치·검증·GraphHopper systemd 단일 소유·Docker local 로그 단일화·exit 0/137 재시작·로컬/EC2 Compose 분리·구 named volume 전환·heap 환경변수화·Spring 재시작 정책·라우팅 evidence와 고정 정상 요청 부하 도구 구현 | 검증 시간 포함 8GiB §9.2 정상 부하와 §9.3 별도 안전 시험 통과 |
| PR 3(선택) | LM 제거 실험 | 독립 benchmark에서 시간·커버리지 계약 통과할 때만 채택 |

`run` LM 제거는 PR 2에 넣지 않는다. 운영 builder·server 설정에서 `foot`을 제외하고 `run` LM을
유지하는 것은 PR 2 범위다. §5.1의 로컬 개발·PoC 비교 설정에서 `foot`을 제거한다는 뜻이 아니다.
