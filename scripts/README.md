# scripts — 데이터 생성 Python (1회성·보관용)

앱이 번들하는 데이터(`races.json` 등)를 만들 때만 쓴다. **앱 빌드와 무관.**

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

# races.json 생성 — ⚠️ 기본 출력 경로가 구(舊) design/ 기준이라 --out 필수
python build_races_json.py --out ../app/src/main/assets/races.json
```

- 입력 경로(`../data/races_sample.csv`)는 그대로 동작한다. 출력만 `--out`으로 지정.
- ⚠️ `durunubi_courses.json`(두루누비 261코스 GPX 파싱본)은 **생성 스크립트가 유실된 산출물** — `reference-web/src/data/durunubi_courses.json`이 유일본이다. 삭제·재생성 금지, 앱에 번들할 때 그 파일을 복사한다.
