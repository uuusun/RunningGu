# 런트립 (RunTrip)

전국 마라톤 일정 통합 + 대회 전후 여행 동선 자동 추천 + 위치 기반 러닝·산책 코스 + 러너 커뮤니티.
2026 관광데이터 활용 공모전 (팀 런닝구) — 새 출발 저장소.

> **모든 제품·데이터·API 질문의 기준은 [`SPEC.md`](./SPEC.md) (v2, 2026-07-16)** — 단일 기준 명세(SSOT).
> 이 저장소는 구 저장소(Korea_Tour_Data)에서 **명세 + 재생성 불가/고비용 자산만** 추려 시작한다.

## 구조

| 경로 | 내용 |
|---|---|
| `SPEC.md` | 최종 통합 명세서 v2 — 07-16 회의 결정 반영. **여기부터 읽기** |
| `design/` | 참조 목업 (Vite+React). SPEC의 🧩목업 태그가 가리키는 참조 구현. `src/lib/runninggu/`는 UI 비종속 도메인 로직(엔진·코스 빌더·정규화·편집)이라 새 스택에 그대로 이식 가능 |
| `design/src/data/durunubi_courses.json` | ⚠️ **두루누비 261코스 GPX 사전 파싱본 — 생성 스크립트 유실 상태. 소스 코드처럼 취급(삭제·재생성 금지), 재생성 스크립트 복원은 백로그** |
| `design/public/data/races.json` | 병합 완료 대회 153건 (파이프라인 산출물) |
| `backend/` | 데이터 파이프라인 — `build_races_json.py`(CSV→races.json), `geocode.py`(카카오 지오코딩+캐시) |
| `crawler/` | 마라톤 크롤러 (구 저장소 밖에서 편입) — 산출물은 실행 위치의 `./output/` |
| `data/races_sample.csv` | 크롤 원천 271행 (멀티라인 필드 — RFC 4180 파서 필수) |
| `submissions/` | 공모전 제안서 (기능 약속 M1~M4·차별성의 원천) |

## 빠른 시작 — 참조 목업 실행

```bash
cd design
npm install
npm run dev        # http://localhost:5173 (strictPort — 카카오 콘솔 등록 도메인)
```

키가 하나도 없어도 SVG 폴백 지도 + 샘플 데이터로 전체 플로우가 동작한다 (SPEC NFR-1).

## 키 설정

```bash
cp .env.example .env                # 루트: 파이프라인·서버용
cp design/.env.example design/.env  # 목업: 지도 + dev 프록시용
```

| 파일 | 키 | 용도 |
|---|---|---|
| `.env` | `KTO_SERVICE_KEY`(디코딩) / `KTO_SERVICE_KEY_ENC`(인코딩) | TourAPI — data.go.kr 페어 키 (SPEC §7.2·§9.4) |
| `.env` | `KAKAO_REST_KEY` | 지오코딩·로컬 검색·모빌리티 (서버 전용) |
| `.env` | `KAKAO_JS_KEY` | 지도 SDK (아래 VITE 키와 동일 값) |
| `design/.env` | `VITE_KAKAO_MAP_KEY` | 카카오맵 SDK (없으면 SVG 폴백) |
| `design/.env` | `TOUR_API_KEY` = KTO_SERVICE_KEY 값 | vite dev 프록시가 `/api/kto/*`에 주입 — 인근 축제 |
| `design/.env` | `KAKAO_REST_KEY` | vite dev 프록시가 `/api/kakao/*`에 주입 — 출발지 검색·걷기 스팟 |

`.env`는 gitignore 대상 — 절대 커밋하지 않는다. REST·KTO 키는 클라이언트 번들 포함 금지 (SPEC §9.4).

## 데이터 파이프라인

```bash
pip install -r backend/requirements.txt        # requests
python backend/build_races_json.py             # data/races_sample.csv → design/public/data/races.json
python backend/geocode.py "수원화성"            # 지오코딩 단건 테스트 (KAKAO_REST_KEY 필요)
python crawler/marathon_crawler.py             # 재크롤 → ./output/ (주 1회 — SPEC §8.2)
```

## 다음 작업

SPEC §11 백로그 P0부터: 백엔드 기반(N-01) → 인증 화면(N-02·03) → 탭 5개 개편(N-05) → 홈(N-08) → 필터 모달(N-04) → 산책 블록 제거·러닝코스 연계(N-06) → 보관함(N-07). 미결 9건은 SPEC §12 참조.
