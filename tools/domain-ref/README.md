# tools/domain-ref — 도메인 로직 레퍼런스 + 골든 픽스처

AP-04(domain 포팅)용. **Kotlin `domain/` 패키지가 맞춰야 할 기대값**을 실데이터로 생성한다.

## 왜 있나

`reference-web/src/lib/runninggu/`는 UX 프로토타입이라 SPEC v2와 어긋난 부분이 있다(아래 참조). 그대로 포팅하면 확정된 회의 결정이 되돌아간다. 이 폴더가 **SPEC 기준 정본**이고, Kotlin은 여기를 보고 옮긴다.

```
reference-web/src/lib/  →  ❌ 포팅 기준 아님 (v1 · UI 결합 · 산책 블록 잔존)
tools/domain-ref/       →  ✅ 포팅 기준 (SPEC v2 · 순수 함수 · 픽스처 생성)
app/.../domain/         →  포팅 결과 (Kotlin)
```

## 구성

| 파일 | SPEC | 비고 |
|---|---|---|
| `constants.mjs` | §5.1~5.3 | RECOVERY·CATS·PATTERNS. **값 변경 금지** |
| `dates.mjs` | §6.6 | UTC 순수 날짜 연산 → Kotlin `LocalDate` 1:1 |
| `events.mjs` | §5.4 | 종목 표준화 (원본과 동일) |
| `normalize.mjs` | §5.5·§6.2 | Race 정규화·중복 병합·접수상태 재계산 |
| `engineV2.mjs` | **§5.6 v2** | 동선 엔진 — **산책 블록 제거판** |
| `courses.mjs` | §5.8·§5.9 | 코스 브라우징·왕복 경로·걷기 스팟 필터 |
| `poiSynth.mjs` | §8.1 ③ | 결정적 합성 POI (난수 없음) |
| `generate.mjs` | — | 픽스처 생성기 |

## 원본과 다른 점 (의도적)

1. **산책 블록 제거** — `engine.js`의 `'가벼운 저녁 산책'`·`'숙소 주변 저녁 산책'`·`'아침 산책'`과 nature 산책 풀(`pools.walk`)을 없앴다. 2026-07-16 회의 결정(부록 C-8): 산책·러닝은 S8이 담당. **원본을 보고 포팅하면 이 결정이 되돌아간다.**
2. **데이터 주입** — `courses.js`가 JSON을 직접 `import`하던 것을 인자로 받게 바꿔 순수 함수화(테스트 용이).
3. **POI 공급자 주입** — `buildItinerary(plan, {searchPOIs})`. 엔진이 네트워크를 모른다.
4. **날짜 TZ 무관** — 로컬 `Date` → UTC 연산.
5. **`regStatusOf(race, today)`** — `today` 필수 인자(픽스처 결정성).
6. **블록 id 카운터가 호출 단위** — 원본은 모듈 전역이라 호출 순서에 따라 id가 달라졌다.

## 사용법

```bash
node tools/domain-ref/generate.mjs
```

전부 결정적(난수·현재시각 미사용)이라 **재실행 시 산출물이 바이트 단위로 같아야 한다.** 달라지면 로직이 변한 것이므로 `git diff fixtures/`로 의도한 변경인지 확인한다.

생성기는 자체 가드도 돈다 — 산책 블록 잔존 시, 왕복 경로가 닫히지 않을 시 **throw**.

## 픽스처

| 파일 | 내용 |
|---|---|
| `normalize.json` | 대회 152건 전량 정규화 결과 + 종목·접수상태 분포 |
| `events.json` | `stdEvent`·`stdEventKm`·`stdEvents` 경계값 |
| `dates.json` | `offLabel`·`shortKo`·`diffDays`·`dateRange`·`patternRange` |
| `itinerary.json` | **종목 4 × 패턴 4 = 16 케이스** 전체 블록 |
| `courses-browse.json` | 261코스 지역·난이도 분포 + 필터 4케이스 |
| `courses-route.json` | 출발지 5 × 목표거리 3 = 15 케이스 (폴리라인은 `routeHash`로 고정) |
| `walk-spots.json` | §5.9 포함/제외 필터 |

Kotlin 테스트는 이 JSON을 `app/src/test/resources/`로 복사해 읽고 assert 한다.
`routeHash`는 FNV-1a — 좌표를 소수점 6자리로 찍어 이은 문자열 기준이라 Kotlin에서 동일하게 재현된다.

## ⚠ 미해결 — 대회 건수 152 vs 153

정규화 규칙이 코드베이스에 **두 벌** 있고 결과가 1건 다르다.

| 규칙 | 건수 | `제N회`·연도 제거 |
|---|---|---|
| `scripts/build_races_json.py` `norm_name` | **153** | ✕ |
| SPEC §6.2 = `normalize.js` = 이 폴더 | **152** | ○ |

차이 원인은 정확히 이 한 쌍이다.

```
2026-06-28  제1회 송도 이봉주 마라톤   (마라톤GO)
2026-06-28  송도 이봉주 마라톤        (마라톤온라인)
```

같은 날짜·같은 대회를 두 소스가 다르게 표기한 것이라 **병합이 맞고 152가 옳다.** SPEC §6.2도 "'제8회'·연도·공백/기호 제거로 소스 간 표기차 흡수"라고 규정한다. README·SPEC §6.1·§8.2의 "153건"과 `build_races_json.py`가 이 규칙을 아직 안 따른다.

→ 결정 필요: `build_races_json.py`의 `norm_name`을 SPEC §6.2에 맞추고 문서의 153을 152로 정정할 것인가.

## ⚠ 버그 — `build_races_json.py` Windows 실행 실패

경고 출력에 em dash(`—`)가 있어 콘솔 기본 코드페이지(cp949)에서 `UnicodeEncodeError`로 **죽는다.** 산출 파일은 쓰였지만 프로세스는 비정상 종료.

```bash
PYTHONIOENCODING=utf-8 python build_races_json.py --out ../app/src/main/assets/races.json
```

당장은 위 회피책으로 쓰고, `print` 쪽에 인코딩 처리를 넣는 게 맞다.
