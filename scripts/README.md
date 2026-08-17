# scripts — 데이터 생성 Python

대회 원천을 정규화하고 초기/검증용 데이터(`races.json`)를 만들 때 쓴다. 운영의 대회 SSOT는 백엔드 canonical `CONTEST`이며, 이 산출물은 목업과 앱 초기 폴백에 사용한다.

| 스크립트 | 역할 |
|---|---|
| `marathon_crawler.py` | 마라톤온라인·마라톤GO 크롤 → 실행 위치의 `./output/`에 CSV·JSON (재크롤 주기: 주 1회, SPEC §8.2) |
| `add_coordinates.py` | 크롤 결과에 좌표 보정 |
| `count_races.py` | 수집 통계 확인 |
| `build_races_json.py` | `data/races_sample.csv` → 병합 153건 `races.json` |
| `geocode.py` | 장소명→좌표 단건 조회 (카카오, `geocode_cache.json` 캐시) |

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

- 입력 경로는 저장소 루트 `data/races_sample.csv`다. 모든 입출력과 콘솔은 UTF-8로 처리한다.
- `durunubi_courses.json`은 두루누비 GPX 파싱본 261코스다. 서버 경로 리소스의 원천으로 보존하고, 앱에는 축약본을 생성해 번들한다. 최신 이름·난이도 등 메타데이터는 서버가 두루누비 API에서 시작 시+하루 1회 동기화한다.
- CI는 `PYTHONUTF8=1`에서 두 번 생성한 결과가 동일한지, JSON UTF-8 디코딩, 153건, 이미지 133건을 검증한다.

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
