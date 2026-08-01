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
