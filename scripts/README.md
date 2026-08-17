# scripts — 데이터 생성 Python

대회 원천을 정규화하고 초기/검증용 데이터(`races.json`)를 만들 때 쓴다. 운영의 대회 SSOT는 백엔드 canonical `CONTEST`이며, 현재 `races.json` 산출물은 목업과 앱 초기 폴백에 사용한다. 서버용 대회 스냅샷은 별도 계약으로 생성한다(SPEC 결정-39·40).

| 스크립트 | 역할 |
|---|---|
| `marathon_crawler.py` | 마라톤온라인·마라톤GO 크롤 → 실행 위치의 `./output/`에 CSV·JSON (재크롤 주기: 주 1회, SPEC §8.2) |
| `add_coordinates.py` | 크롤 결과에 좌표 보정 |
| `count_races.py` | 수집 통계 확인 |
| `build_races_json.py` | `data/races_sample.csv` → 병합 153건 `races.json` |
| `geocode.py` | 장소명→좌표 단건 조회 (카카오, `geocode_cache.json` 캐시) |
| `build_courses.py` | 러닝코스 소스(두루누비 API+GPX · 로컬 GPX 폴더) → 정규화 `data/courses.json`. 어댑터는 `courses/sources/` |

## 사용법

```bash
pip install -r requirements.txt      # requests
cp .env.example .env                 # 키 입력 (커밋 금지)
set -a; source .env; set +a

# 목업 데이터 생성(기본 경로: ../reference-web/public/data/races.json)
python build_races_json.py

# 앱 초기 폴백을 갱신할 때만 출력 경로를 명시
python build_races_json.py --out ../android/app/src/main/assets/races.json
```

## 서버용 대회 스냅샷 계약 (결정-39·40 — 구현 전)

대회 정규화·region/좌표 보정·중복 병합은 Python 데이터 파이프라인이 단일 책임을 갖는다. P0에서는 Python이 서버용 JSON 스냅샷을 생성하고, 백엔드 Importer가 이를 검증해 PostgreSQL에 트랜잭션으로 멱등 적재한다. 최초 산출물은 초기 시드, 이후 주간 재수집 산출물은 갱신 스냅샷이다.

- 현재 `reference-web/public/data/races.json`은 목업용 대표 목록이며 **서버용 대회 스냅샷이 아니다**.
- 서버용 스냅샷은 canonical 대회별 표준 필드와 `events[]`, 병합 전 원천을 보존하는 `sources[]`를 포함해야 한다.
- `canonicalKey`와 `(sourceType, externalId)`는 재수집 시 upsert 가능한 안정적 유일키여야 한다.
- 같은 입력은 두 번 실행해도 같은 UTF-8 산출물을 만들어야 한다.
- 백엔드는 스키마·유일키·필수값·좌표·집계를 전부 검증하고, 하나라도 실패하면 전체 적재를 롤백해 이전 정상 canonical을 유지한다.
- 구체적인 파일 경로와 JSON 스키마는 데이터 생산자와 백엔드가 첫 계약 PR에서 확정한다. 확정 전에는 현재 `races.json`을 서버 DB에 직접 적재하지 않는다.
- Python은 `CONTEST`, `CONTEST_SOURCE`, `CONTEST_EVENT` 운영 핵심 테이블에 직접 쓰지 않는다.
- 향후 자동화는 인증된 내부 수집 API 또는 스테이징 테이블 적재 후 백엔드 승격 방식 중 하나로 전환한다. 구체적인 방식은 자동화 착수 시 별도로 확정한다.

- 입력 경로는 저장소 루트 `data/races_sample.csv`다. 모든 입출력과 콘솔은 UTF-8로 처리한다.
- `durunubi_courses.json`은 두루누비 GPX 파싱본 261코스다. 서버 경로 리소스의 원천으로 보존하고, 앱에는 축약본을 생성해 번들한다. 최신 이름·난이도 등 메타데이터는 서버가 두루누비 API에서 시작 시+하루 1회 동기화한다.
- CI는 `PYTHONUTF8=1`에서 두 번 생성한 결과가 동일한지, JSON UTF-8 디코딩, 153건, 이미지 133건을 검증한다.

## 러닝코스 데이터 (`build_courses.py`)

소스를 어댑터로 붙이는 구조다. 새 소스는 `courses/sources/` 에 파일 하나를 추가하고
`@register` 를 붙이면 되며 `build_courses.py` 는 고치지 않는다.

```bash
set -a; source scripts/.env; set +a          # KTO_SERVICE_KEY · KAKAO_REST_KEY

python scripts/build_courses.py --sources durunubi --regeocode
python scripts/build_courses.py --sources "durunubi,gpx:100대명산" --regeocode
```

| 어댑터 | 소스 | 구간 잘라내기 |
|---|---|---|
| `durunubi` | 두루누비 `courseList` + GPX (코리아둘레길) | 가능 |
| `gpx:{폴더}` | `data/{폴더}/**/*.gpx` (산림청 등산로 등) | 폴더별 확인 |
| `seoul_dulle` | 서울둘레길 SHP | **불가** — 공공누리 4유형(변경금지). 서울시 문의 필요 |

라이선스상 구간을 자를 수 없는 소스는 기본으로 제외한다(`--include-nonderivable` 로 강제 포함).

### 설계에서 지켜야 할 것

- **출력 `points` 는 `[lat, lng, ele, cumGainM]`.** 거리·상승고도는 GPX **원본 해상도**로 재고
  그 값을 축약 포인트에 실어 나른다. 축약본으로 계산하면 상승고도가 41% 과소평가된다
  (해파랑길 1코스 732m → 434m 실측).
- **`<trk>` 를 이어붙이지 않는다.** 두루누비 GPX 263개 중 16개가 같은 경로를 두 벌로 담고 있어,
  이어붙이면 트랙 사이 점프가 거리에 더해진다(해파랑길 18코스 18km → 50km).
- **두루누비는 API 만 보고 수집하지 않는다.** API 가 주는 코스는 144개뿐이고 시드
  `durunubi_courses.json` 에는 261개가 있다. 시드를 기준으로 두고 API 메타로 보강하며,
  `dataSource` 를 `API_GPX` / `GPX_ONLY` 로 남긴다 (SPEC §8.4 · `domain-logic-audit.md` §C4).
- **난이도는 상승고도(m/km)로 매긴다.** 원본 `level` 은 코스 **전체** 등급이라 조각 추천에
  그대로 쓰면 안 된다 — 해파랑길 1코스(level=2)를 2.5km 로 쪼개면 조각별 상승고도가
  36m~309m 로 9배 차이났다.
- 원본 GPX 배포본(`data/100대명산/` 등)과 `.cache/` 는 커밋하지 않는다. 합쳐서 200MB 가 넘는다.

## 대회 재수집 안전 게이트

현재 기준 데이터는 원천 CSV 271행과 병합 결과 153건 모두 `latitude/longitude` 누락이 0건이다. 다음 재수집에서도 아래 순서를 통과하기 전에는 `data/races_sample.csv`와 서버 canonical 데이터를 교체하지 않는다(SPEC 결정-31).

1. `scripts/.env`에 `KAKAO_REST_KEY`를 주입한다. 키는 저장소·로그·명령 출력에 남기지 않는다.
2. `marathon_crawler.py`를 실행해 `output/races_sample.json/csv`를 새로 만든다.
3. `add_coordinates.py`로 빈 좌표를 보정한다.
4. 원천 행의 `latitude/longitude` 빈 값·숫자 변환 실패가 0건인지 검증한다.
5. 중복·필수값·제외 경고를 검토한 뒤 승인된 CSV만 `data/races_sample.csv`로 승격한다.
6. `build_races_json.py`를 두 번 실행해 결과가 같고 병합 결과의 `lat/lng` 누락이 0건인지 확인한다.
7. 한 단계라도 실패하면 새 데이터를 배포하지 않고 이전 정상 canonical을 유지한다.

> **현재 코드 주의:** `geocode.py`는 `KAKAO_REST_KEY` 환경변수와 키 없음 fail-fast를 사용하지만, `add_coordinates.py`는 아직 빈 `KAKAO_REST_API_KEY` 상수를 사용한다. 후속 P0 코드 작업에서 환경변수 방식으로 통일하고 fail-fast를 넣기 전까지 3단계 결과를 운영 데이터로 승격하지 않는다.
