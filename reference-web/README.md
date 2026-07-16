# 런트립 (RunTrip)

마라톤 대회를 축으로 전후 여행 동선(D-1 / D-day / D+N)을 자동 설계하고,
**상단 지도 + 스크롤 동기화 타임라인**으로 보여주는 모바일 웹앱.
핵심 차별점: **완주 피로도(참가 종목)에 따라 동선이 회복형으로 바뀐다.**

## 실행

```bash
npm install
npm run dev      # http://localhost:5180
npm run build    # 프로덕션 빌드 (dist/)
```

카카오맵 키(선택): `cp .env.example .env` 후 `VITE_KAKAO_MAP_KEY` 입력.
키가 없으면 SVG 폴백 지도 + 샘플 데이터로 그대로 동작합니다.

## 구조

- **`src/lib/runninggu/`** — UI 비종속 도메인 로직 (프레임워크 교체에도 그대로 재사용)
  - `constants.js` 회복 룰(RECOVERY)·취향 카테고리(CATS)·일정 패턴
  - `events.js` 종목 표준화(stdEvents) · `dates.js` 날짜·패턴 계산
  - `normalize.js` 원천 snake_case → 앱 camelCase Race
  - `poi.js` POI 어댑터 — **폴백 체인: ① 카카오 실시간 → ② 사전수집(raceId별) → ③ 합성 샘플** (source 배지)
  - `engine.js` **buildItinerary** — 회복 룰 분기로 days[]/blocks[] 초안 생성
  - `edits.js` 동선 편집 연산(수정/삭제/추가/순서) — days/blocks 불변 조작
  - `sampleData.js` 대회·사전수집 POI·합성 생성기
- **`src/screens/`** — S1 홈 → S2 대회상세 → S3 일정 → S4 취향·종목 → S5 숙소 → S6 결과 (+ 내 여행·러닝코스)
- **`src/map/MapView.jsx`** — 지도 어댑터(카카오맵 / SVG 폴백 인터페이스 동일)
- **`src/store/appState.jsx`** — 상태머신 + 뒤로가기 스택 + 편집/저장
- **`src/styles/tokens.css`** — `런트립 앱 화면.dc.html` STYLE A에서 추출한 디자인 토큰(단일 출처)

## 동선 = "편집 가능한 초안"

`buildItinerary`가 만든 `days[]/blocks[]`는 읽기전용이 아니다. 결과 화면 **[편집]**에서
순서변경(드래그)·교체(↺, 후보 시트)·삭제(✕)·추가(+) 하면, 지도 핀·폴리라인·일자탭·스크롤
동기화가 파생값으로 **자동 재계산**된다. (편집 로직 = `lib/runninggu/edits.js`)

## 데이터 흐름

원천(snake_case) → `normalizeRace` → 화면. POI는 `searchPOIs()`로 추상화되어
실시간↔폴백을 코드 변경 없이 교체. 결과 화면에 `LIVE`/`샘플` 배지로 소스 표기.
