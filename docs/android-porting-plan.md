# 안드로이드 포팅 계획 — 웹 로직을 앱으로 옮기기

작성 2026-08-14 · 2026-08-17 서버 생성 결정 반영 · 대상 AP-04(domain 포팅) · 관련 SPEC §5 · §9.1 · §2.4 · 부록 D

> **결정-41**: P0 새 동선의 운영 생성 주체는 백엔드 `POST /itineraries/generate` 하나다. PR #22로 머지된 앱 `ItineraryEngine`은 서버 이식의 참고 구현·테스트 기준이며 운영 화면에 연결하지 않는다. 이 결정이 아래 포팅 항목의 기존 앱 엔진 계획을 대체한다.

SPEC이 폴더 구조(§9.1)와 파일 매핑(부록 D)은 이미 정해 두었다. 이 문서는 그걸
**"무슨 파일을 어떤 순서로 만드는가"** 로 바꾸고, [도메인 로직 대조](domain-logic-audit.md)에서
나온 함정을 미리 박아 둔다.

---

## 1. 지금 어디까지 와 있나

```
com/runninggu/app/
├── ui/
└── domain/      ← PR #15·#21·#22로 상수·날짜·동선 참고 엔진 등이 머지됨
```

PR #22의 `ItineraryEngine`은 구현돼 있지만 결정-41에 따라 운영 화면에는 연결하지 않는다. 앱에 남길
순수 규칙·저장 전 편집과 서버 `generate` DTO 경계를 정리하고, `data/remote`를 springdoc 계약에 맞춰
연결해야 한다. 지도 SDK와 위치 라이브러리 작업은 별도 단계다.

---

## 2. domain 에 만들 파일

`domain` 은 **순수 Kotlin** 이다. Android 클래스를 import 하지 않는다. 그래야 단위 테스트가
기기 없이 돌아간다.

| 원본 (reference-web) | 만들 파일 | 무엇 |
|---|---|---|
| `constants.js` | `domain/Recovery.kt` `Cats.kt` `Patterns.kt` | 회복 룰·취향 카테고리·일정 패턴 상수 |
| `events.js` | `domain/EventStd.kt` | 종목 표준화 (`풀`/`하프`/`10K`/`5K`) |
| `dates.js` | `domain/KstDates.kt` | KST 기준 날짜 계산 (§6.6) |
| `normalize.js` | `domain/RaceNormalizer.kt` | 크롤 레코드 → 표준 대회 |
| `engine.js` | 백엔드 `ItineraryGenerationService` | 서버 동선 조립. 앱 `ItineraryEngine.kt`는 참고 구현·테스트 기준만 유지 |
| `edits.js` | `domain/ItineraryEdits.kt` | 동선 편집 연산 (불변) |
| `courses.js` | `domain/CourseBuilder.kt` | 코스 왕복 자르기 (오프라인 폴백용) |
| `tourapi.js` · `poi.js` | **만들지 않는다** | 서버가 한다 — 3장 참고 |

### 2.1 동선 생성은 앱 포팅 대상이 아니다

원본 `buildItinerary`의 운영 이식 위치는 백엔드다. 서버는 외부 POI 어댑터와 순수 규칙 모듈을 분리하고, 앱은 `POST /itineraries/generate` 한 번으로 완성된 `days[]`·`blocks[]`를 받는다.

```text
앱 WizardViewModel
  → POST /itineraries/generate
  → 서버 POI 조회·캐시·폴백 + §5.6 조립
  → 생성 DTO 표시
  → 앱에서 USER 블록만 저장 전 편집
```

PR #22의 Kotlin `ItineraryEngine`과 단위 테스트는 서버 이식 시 동작 대조에 쓴다. 앱 `PoiSource`가 카테고리별 POI를 조회해 로컬에서 새 동선을 조립하는 런타임 경로는 만들지 않는다. 서버 계약 테스트가 같은 규칙을 고정한 뒤 앱 엔진 제거는 별도 코드 PR로 처리한다.

---

## 3. 앱이 할 일과 서버가 할 일

**앱에는 API 키가 없다.** SPEC §9.4가 확정한 내용이고, 앱에 들어가는 키는 카카오 네이티브 키
하나뿐이다(지도·로그인·공유 SDK용). KTO·카카오 REST 키는 서버에만 둔다. 앱을 뜯으면 키가
나오기 때문이다.

그래서 밖에서 데이터를 가져오는 일은 **전부 우리 서버를 거친다.**

| 하는 일 | 누가 | 앱이 부르는 곳 |
|---|---|---|
| 장소(POI)·숙소 찾기 | 서버 → 카카오 | `GET /pois` |
| **새 동선 생성** | 서버 규칙 엔진 → KTO·카카오 POI 조립 | `POST /itineraries/generate` |
| 인근 축제 | 서버 → KTO | `GET /contests/{id}/festivals` |
| 출발지 검색(지오코딩) | 서버 → 카카오 | `GET /geocode` |
| **근처에서 뛸 만한 곳** | 서버 (두루누비 GPX + 카카오 걷기 스팟을 **서버에서 합쳐** 거리순으로) | `GET /courses/near` — 아래 참고 |
| 코스 목록(지역별) | 서버 (두루누비 메타 + GPX) | `GET /courses` · `/courses/regions` |
| 대회·찜·동선·기록 | 서버 (SSOT) | `GET /contests` · `/me/**` · `/itineraries` · `/runs` |

**앱에 남는 계산은 이것뿐이다.** 서버가 생성한 동선을 저장 전에 편집하고, 회복 안내를 미리 표시하고,
종목을 표준화하는 일. 그리고 오프라인일 때 번들된 GPX 축약본으로 코스를 자르는 일. 새 동선 조립은 서버만 한다.

### 3.1 근처 코스와 걷기 스팟은 서버가 합쳐서 준다

SPEC §4.11(a)가 두 목록을 **하나로 합쳐 거리순으로** 보여주기로 바뀌었다(사용자에게 데이터
출처를 노출하지 않는다). 그러면 앱이 `/courses/near` 와 `/walk-spots` 를 따로 부르고 직접
섞는 방식은 맞지 않는다.

- 두 번 부르면 **둘 다 도착해야 정렬이 확정**된다 → 목록이 늦게 뜨거나 순서가 튄다
- 섞고 정렬하는 규칙이 앱과 서버에 두 벌로 갈라진다

그래서 `GET /courses/near` 하나가 **경로와 장소를 섞어 거리순으로** 반환한다. 항목마다
경로 유무를 구분하는 필드를 둔다.

```
GET /courses/near?lat=&lng=&targetKm=&radiusKm=
→ items[] 공통: kind(ROUTE | PLACE) · name · distanceM · lat · lng
   ROUTE 이면  courseId · sido · sigun · difficulty · fullDistanceKm · routeKm · durationMin · shortfall · pathPolyline
   PLACE 이면  category · address · placeUrl
```

앱은 받은 순서대로 그리기만 한다.

---

## 4. 화면 상태를 어떻게 들고 있을까

목업은 `S` 라는 전역 변수 하나에 모든 상태가 들어 있다. 앱은 그렇게 못 한다.
SPEC §2.4 대로 **화면마다 ViewModel + `StateFlow<UiState>`** 를 둔다.

목업의 `S` 필드가 어디로 가는지는 이렇게 나뉜다.

| 목업 `S` 필드 | 갈 곳 |
|---|---|
| `q · view · f · calY · calM · selDate` | `CalendarViewModel` |
| `detailId · festState` | `RaceDetailViewModel` |
| `wPattern · wStart · wEnd · wEvent · wPrefs · hotel · itin · activeDay · editMode` | **위저드 공유 ViewModel** (아래) |
| `cTab · courseStart · dist · course · cRegion` | `CourseViewModel` |
| `libTab · saved · ranSaved` | `LibraryViewModel` |
| `guest · returnTo` | 세션 (DataStore) + 앱 스코프 |
| `snack · confirm · poi · filterOpen` | 각 화면 ViewModel 의 UiState 안 |

**위저드만 특별하다.** 일정 → 취향 → 숙소 → 결과가 값을 공유해야 해서, 화면마다 따로 두면
값이 끊긴다. Navigation 그래프 스코프 ViewModel 하나를 세 화면이 같이 쓴다.

그리고 모든 비동기 화면은 **로딩 / 내용 / 빈 / 오류** 네 상태를 구분한다(§3-5). 목업에
이미 다 그려 놨으니 그대로 옮기면 된다.

---

## 5. 옮길 때 반드시 반영할 것

[도메인 로직 대조](domain-logic-audit.md)에서 나온 것들이다. **원본 코드를 그대로 옮기면
틀리는 자리**라 여기 다시 적는다.

| 무엇 | 어떻게 |
|---|---|
| 산책 블록 3개 | 서버 §5.6 엔진에서 **생성하지 않는다** (D-1 20:00 · D-day 20:30 · D+N 08:00) |
| 산책용 POI 적재 | 서버 생성에서 제외한다. 응답의 `sources` 에서 `walk` 키도 없다 |
| `rule.walk` | 거리 라벨이 아니라 **S7→S8 목표거리 기본값 `min(walk,5)km`** 로 새로 구현 |
| 대회 블록 | 서버가 `blockType=RACE` + `systemManaged`로 생성한다. 앱 저장 전 편집과 서버 저장 후 편집 모두 거부한다 |
| `courseRegions()` | 시도명만이 아니라 **개수도 함께** 반환한다 (칩에 숫자를 띄워야 함) |
| 걷기 스팟 개수 | 기본 **12개** (원본은 10) |
| 코스 데이터 | `API_GPX` + `GPX_ONLY` 를 **둘 다** 서비스한다. API 응답만 믿으면 261 → 144 로 줄어든다 |

특히 마지막 줄은 나중에 "API 가 최신이니 그냥 API 만 쓰자" 는 판단이 나오기 쉬운 자리다.
그렇게 하면 코스가 반토막 난다.

---

## 6. 어떤 순서로 할까

앞 단계가 끝나야 뒷 단계가 되는 순서다. 앱의 새 동선 화면 연결은 서버 springdoc 계약이 필요하지만,
상수와 저장 전 편집은 먼저 진행할 수 있다.

**1단계 · 상수와 계산기** (의존성 없음)
`Recovery` `Cats` `Patterns` `EventStd` `KstDates` `RaceNormalizer`.
값이 SPEC 표와 한 글자도 다르면 안 되므로 단위 테스트를 같이 쓴다.

**2단계 · 생성 DTO 모델과 저장 전 편집** (1단계 필요)
`ItineraryEdits`와 generate 응답을 받을 앱 도메인 모델을 만든다. RACE 블록 잠금은 앱 편집 연산에서 검증한다.
기존 `ItineraryEngine`은 운영 경로에 연결하지 않고 서버 이식 대조용으로만 둔다.

**3단계 · 서버 통신 골격** (서버 API 확정 필요)
`data/remote` 의 `POST /itineraries/generate` Retrofit 인터페이스와 DTO, 그리고 DTO→도메인 매퍼.
서버가 springdoc-openapi 로 문서를 내주면 그걸 보고 쓴다.

**4단계 · 화면 연결** (2~3단계 필요)
지금 있는 Composable 에 ViewModel 을 붙인다. 네 가지 상태(로딩/내용/빈/오류)를 다 만든다.

**5단계 · 지도와 코스** (지도 SDK 필요)
카카오맵 Android SDK 를 붙이고 `CourseBuilder` 를 연결한다.

**6단계 · GPS 기록** (5단계 필요, P1)
포그라운드 서비스 + 위치 수집. MVP 범위 밖이라 마지막이다.

---

## 7. 아직 안 정한 것

- **걷기 좋은 곳 필터를 고칠지** — 지금 규칙대로면 공원 안 시설물(방문자센터·게양대)이
  목록의 절반을 먹는다. 개선안은 실제 API 로 검증해 뒀다. SPEC §5.9 문장을 고치는 일이다.
- **수도권 기본 화면** — 두루누비 코스가 없는 지역에서 걷기 좋은 곳이 기본이 된다.
  목표 거리는 "오늘 뛸 목표" 로 유지하고 기록 화면에서 진행률로 보여주기로 했다(목업 반영 완료).
- **Hilt 를 쓸지** — SPEC 은 "선택" 으로 열어 두었다. 화면 수가 적어 수동 주입으로도 된다.

---

## 8. 백로그 — 수도권에서도 경로를 만들려면

수도권에는 따라갈 경로가 없어 "장소만" 나온다. 경로까지 만들려면 도보 길찾기가 필요한데,
조사 결과는 이렇다.

| 후보 | 상태 |
|---|---|
| 카카오모빌리티 도보 길찾기 | **제휴 파트너 전용** — 사전 계약 필요. 쓸 수 없다 |
| TMAP 보행자 경로안내 (`apis.openapi.sk.com/tmap/routes/pedestrian`) | 공개 API. 국내 보행자 길찾기는 사실상 여기뿐 |

다만 길찾기는 **A→B** 를 준다. 우리가 필요한 건 "목표 거리만큼 뛰고 제자리로 돌아오는 코스" 다.
우회 방법은 있다 — 이미 갖고 있는 걷기 스팟 목록을 쓰면 된다.

1. 출발지에서 **목표의 절반쯤 떨어진 스팟**을 고른다 (5km 목표 → 2.5km 지점)
2. 출발지 → 그 스팟 도보 경로를 받는다
3. 왕복하면 목표 거리에 근접한다 (두루누비 `buildRouteNear` 와 같은 원리)

**MVP 에는 넣지 않는다.** 외부 API 의존이 하나 늘고(키·프록시·장애 대응), 무료 한도와 상업적
이용 조건을 아직 확인하지 못했으며, 보행자 길찾기는 도로 기준이라 하천변·공원 안 산책로를
제대로 못 잡을 수 있다. 지금 폴백만으로도 "수도권에서 뛰고 기록한다" 는 충족된다.
