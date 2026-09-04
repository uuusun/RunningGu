# scripts/osm — OSM 기반 러닝코스 생성 PoC

두루누비·산림청 데이터로는 **도시에서 러닝코스가 나오지 않는다**(실측: 수원·성남·청주·대전 반경
8km 내 0건). OpenStreetMap 보행로를 GraphHopper 로 라우팅해 코스를 **생성**할 수 있는지 검증한
기록이다. 결과는 [`docs/osm-routing-poc.md`](../../docs/osm-routing-poc.md).

GraphHopper 도입은 SPEC 결정-42로 확정됐지만, 이 폴더 최상위의 명령과 `graphhopper.yml`은 PoC
재현·비교 전용이다. 운영 builder·manifest·배포 계약은
[`docs/deploy/graphhopper-artifact-contract.md`](../../docs/deploy/graphhopper-artifact-contract.md)를
따르며 운영 artifact는 `import/`의 고정 builder로만 만든다.

## 운영 graph artifact builder

먼저 날짜가 고정된 한국 PBF를 별도 경로에 받고, 배포 입력으로 승인할 SHA-256을 팀 기록에
남긴다. `latest` URL이나 기존 작업 directory는 받지 않는다. 아래 두 명령은 host JDK를 쓰지 않고
같은 `linux/amd64` builder image 안에서 GraphHopper `import`와 package·verify를 실행한다.

macOS·Linux·WSL/Git Bash:

```bash
./scripts/osm/import/build-graph.sh \
  --pbf /absolute/path/south-korea-2026-09-01.osm.pbf \
  --pbf-date 2026-09-01 \
  --pbf-sha256 '<64자리_lowercase_sha256>' \
  --work-dir /absolute/path/runninggu-graph-build-20260901 \
  --created-by '<팀_식별자>' \
  --import-xms 1g \
  --import-xmx 8g
```

Windows PowerShell:

```powershell
.\scripts\osm\import\build-graph.ps1 `
  -Pbf 'C:\graph-input\south-korea-2026-09-01.osm.pbf' `
  -PbfDate '2026-09-01' `
  -PbfSha256 '<64자리_lowercase_sha256>' `
  -WorkDirectory 'C:\graph-build\runninggu-20260901' `
  -CreatedBy '<팀_식별자>' `
  -ImportXms '1g' `
  -ImportXmx '8g'
```

출력의 `<work-dir>/artifacts/<artifact-id>/` 아래 세 파일만 비공개 S3의 계약 key에 업로드한다.
`graph-release.example.json`을 복사해 실제 manifest hash와 build input hash를 채운
`backend/graphhopper/graph-release.json`은 별도 리뷰를 받아야 하며, 비어 있는 예시를 배포하지
않는다. 운영 PBF import에는 builder host가 8GiB보다 큰 메모리를 사용할 수 있지만 그 메모리는
EC2 server 상시 사양과 무관하다.

## 준비물

| | |
|---|---|
| JDK | 21 (`/usr/libexec/java_home -v 21`) |
| 디스크 | 약 1GB (OSM 272MB + 그래프 캐시 514MB + SRTM 44MB) |
| 메모리 | 빌드 시 `-Xmx6g` 권장 |
| 네트워크 | 최초 1회 다운로드 · SRTM 타일 자동 수신 |

## 실행

```bash
# 1. 작업 폴더 (저장소 밖에 두는 것을 권장 — 1GB 가 넘는다)
mkdir -p ~/osm-poc && cd ~/osm-poc

# 2. GraphHopper 와 한국 OSM 내려받기 (합쳐서 약 320MB)
curl -L -o gh.jar https://github.com/graphhopper/graphhopper/releases/download/11.0/graphhopper-web-11.0.jar
curl -L -o korea.osm.pbf https://download.geofabrik.de/asia/south-korea-latest.osm.pbf

# 3. 설정 복사
cp <저장소>/scripts/osm/graphhopper.yml config.yml

# 4. 그래프 빌드 + 서버 기동 (최초 3~4분, 이후 재기동은 30초)
export JAVA_HOME=$(/usr/libexec/java_home -v 21)      # Windows 는 JDK 21 경로를 직접 지정
"$JAVA_HOME/bin/java" -Xmx6g -jar gh.jar server config.yml

# 5. 다른 터미널에서 검증
python <저장소>/scripts/osm/roundtrip.py --preset metro
```

`latest` PBF는 PoC 편의용이다. 운영 builder는 날짜가 고정된 PBF URL과 SHA-256만 입력으로 받는다.

## 계약 상한 회귀 (AP-25 테스트 기준)

`--preset caps` 는 SPEC §5.8 품질 상한 네 개를 그대로 적용해 커버리지와 **탈락 사유**를 낸다.
통과 0건인 지점은 어느 상한이 몇 개를 걸렀는지, 가장 아까운 후보가 무엇이었는지까지 찍는다.

```bash
python <저장소>/scripts/osm/roundtrip.py --preset caps              # 수도권 20곳 (기본)
python <저장소>/scripts/osm/roundtrip.py --preset caps --zone all   # + 지방 10곳
```

계단은 계약 상한이 아니라 **회귀 기준**(선택된 경로 ≤1%)으로만 확인한다.

## 운영 사양 부하 시험

먼저 EC2와 동일한 graph artifact·server image를 로컬 운영 호환 container로 실행한다. image digest는
배포할 image의 content-addressed ID(`docker image inspect --format '{{.Id}}'`)를 사용한다. 아래 명령은
전체 caps/all의 seed별 HTTP status·실패 분류·품질 판정과 합격 셀마다 정상 직접 요청 하나를 같은
evidence JSON에 고정한다. `no valid point` 400은 실패로 기록하며 오류 원문과 경로 geometry는 남기지
않는다.

```bash
python3 scripts/osm/roundtrip.py \
  --preset caps \
  --zone all \
  --seeds 16 \
  --artifact-id '<artifact_id>' \
  --server-image-digest '<sha256:image_id>' \
  --evidence '<release-evidence.json>'
```

이 파일과 실행 명령을 EC2 첫 결과를 보기 전에 release evidence로 고정한다. EC2에서는 새 결과
파일을 만들면서 `--baseline '<release-evidence.json>'`을 추가한다. 로컬에서 성공한 직접 요청이
실패하거나 로컬 합격 셀의 품질 상한 통과 경로가 0건이면 종료 code 1이다.

8GiB·4GiB 사양 비교의 30분 부하는 `operational_load.py`로 실행한다. 이 도구는 evidence의
`normalRequests`만 순서대로 반복하고 artifact ID와 server image digest가 현재 시험값과 다르면
시작하지 않는다. 요청은 wall clock 기준 고정 도착률로 시작하며 모든 worker가 사용 중이면 기다려
부하를 낮추지 않고 `missedRequestStarts`로 기록해 시험을 실패시킨다.

아래 `<고정_request_rate>`와 `<고정_concurrency>`는 8GiB 시험 전에 팀 기록으로 확정하며 4GiB까지
같은 값을 사용한다. 결과 파일은 요청 본문이나 응답 경로를 보관하지 않고 고정 시험 지점 이름,
HTTP status, seed, 요청 소요시간과 실패 분류만 JSON Lines로 남긴다.

```bash
python3 scripts/osm/operational_load.py \
  --request-set '<release-evidence.json>' \
  --artifact-id '<artifact_id>' \
  --server-image-digest '<sha256:image_id>' \
  --duration-seconds 1800 \
  --requests-per-minute '<고정_request_rate>' \
  --concurrency '<고정_concurrency>' \
  --timeout-seconds 5 \
  --output "/opt/runninggu-validation/<시험_ID>/graphhopper-load.jsonl"
```

종료 code 0과 마지막 `summary.passed=true`가 모두 필요하다. `missedRequestStarts`,
`failedDirectRequests`, `requestsOverTimeout`이 하나라도 있으면 종료 code 1이다.
`noValidPointResponses`는 실패 요청의 부분집합으로 별도 기록되며 성공으로 바뀌지 않는다.
`requestSeconds`의 p50·p95·max를 운영 기록에 옮긴다. 단순 `while roundtrip.py ...` 반복은 느린
instance에서 자동으로 요청량이 줄어드는 closed-loop라 사양 비교에 사용하지 않는다.

## 물길 인덱스 (골목 회피 · 하천 유도 검증용)

`--preset water` 는 전국 물길 인덱스가 필요하다. PBF 에서 한 번 만들면 된다.

```bash
pip install osmium
python <저장소>/scripts/osm/build_waterways.py ~/osm-poc/korea.osm.pbf -o data/waterways.json
python <저장소>/scripts/osm/roundtrip.py --preset water --waterways data/waterways.json
```

산출물은 10.8MB(물길 16,736개 · 44,442km)이고 빌드에 18~70초 걸린다. 조회는 격자라
지점당 1.5ms 다. 운영에 채택하면 EC2에서 만들지 않고 builder 입력·manifest·artifact에 포함한다.

## 프로파일 두 개

| 프로파일 | 용도 |
|---|---|
| `foot` | GraphHopper 기본 보행 경로. **비교용** |
| `run` | 러닝 친화 가중치. 큰 도로를 강하게 회피한다 |

`foot` 은 "보행자가 A→B 가는 최적 경로"를 찾기 때문에 큰 도로 보도를 그냥 쓴다. 실측에서 여의도
5km 코스의 **28% 가 `primary`(왕복 대로)** 였다. `run` 은 아래 가중치로 이를 0% 까지 낮춘다.

```
보행로·산책로·보행자전용   ×1.00      주택가 골목        ×0.50
자전거도로·임도           ×0.95      이면도로·주차장     ×0.25
                                    일반도로(tertiary) ×0.20
                                    큰 도로(primary 등) ×0.05
```

## 알아둘 것

- **`round_trip` 은 목표 거리를 정확히 맞추지 않는다.** 요청값의 1.1~1.5배로 나온다.
  `roundtrip.py` 는 목표의 78% 로 요청한 뒤 여러 seed 중 가장 가까운 것을 고른다.
- **seed 마다 다른 경로가 나온다.** 5ms 짜리 호출이므로 16개를 돌려도 0.1초다.
  이상치(목표 대비 ±25% 초과)는 버린다.
- **고도는 OSM 에 없다.** `graph.elevation.provider: srtm` 이 위성 고도를 자동으로 받아 붙인다.
  이 값이 있어야 `build_courses.py` 와 같은 기준(m/km)으로 난이도를 매길 수 있다.
- **라이선스는 ODbL.** 출처표시(`© OpenStreetMap contributors`) 의무가 있고, share-alike 는
  파생 **데이터베이스를 배포할 때** 발동한다. 서버에서 경로만 응답하는 구조는 해당하지 않는다.
  공공누리 4유형(변경금지)인 서울둘레길과 달리 가공에 제약이 없다.
