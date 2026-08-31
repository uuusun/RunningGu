# 안드로이드 포팅 계획 — 웹 로직을 앱으로 옮기기

작성 2026-08-14 · 2026-08-19 서버 동선·OSM 코스 품질 상한 결정 반영 · 대상 AP-04(domain 포팅) · 관련 SPEC §5 · §9.1 · §2.4 · 부록 D

> **결정-41·42**: P0 새 동선은 백엔드 `POST /itineraries/generate`만 생성하고, 목표 거리와 상승 상한에 맞는 큐레이션 경로가 없는 도시 러닝코스는 서버 GraphHopper가 생성한다. P0 난이도 입력은 없고 서버가 HARD·고차도·과다 회전 경로를 제외한다. 앱 `ItineraryEngine`과 OSM 라우팅은 운영 화면에서 실행하지 않고 서버 응답 표시·저장 전 편집·GPX 오프라인 폴백만 담당한다.

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
| `normalize.js` | **만들지 않는다** | 크롤 레코드 정규화는 Python 파이프라인이 한다 — 2.1 참고 |
| `engine.js` | 백엔드 `ItineraryGenerationService` | 서버 동선 조립. 앱 `ItineraryEngine.kt`는 참고 구현·테스트 기준만 유지 |
| `edits.js` | `domain/ItineraryEdits.kt` | 동선 편집 연산 (불변) |
| `courses.js` | `domain/CourseBuilder.kt` | 코스 왕복 자르기 (오프라인 폴백용) |
| `tourapi.js` · `poi.js` | **만들지 않는다** | 서버가 한다 — 3장 참고 |

### 2.1 정규화도 앱 포팅 대상이 아니다

`normalize.js`(크롤 레코드 → 표준 대회)를 `domain/RaceNormalizer.kt` 로 옮기려던 계획을 **취소한다.**

**결정-39 가 정규화·중복 병합의 주인을 Python 데이터 파이프라인으로 못 박았기 때문이다.**
백엔드조차 "같은 병합 알고리즘을 Java 로 중복 구현하지 않는다" 고 되어 있다. 앱에 또 옮기면
같은 규칙이 **세 벌**(Python · Java · Kotlin)이 되고, 갈라졌을 때 어느 쪽이 맞는지 알 수 없다.

**앱은 크롤 레코드를 볼 일이 아예 없다.** 들어오는 경로가 둘뿐인데 둘 다 이미 정규화된 것이다.

| 경로 | 정규화한 주체 |
|---|---|
| `GET /api/contests` 등 서버 API | Python 스냅샷 → 백엔드 적재 (결정-39) |
| `assets/races.json` 번들 | `scripts/build_races_json.py` |

번들을 읽는 `RaceBundleDto` 가 하는 일(한국어 라벨 → 원천 토큰, 접수 상태 문자열 해석)은
**이미 정규화된 값의 표기를 맞추는 것**이지 크롤 레코드를 표준화하는 것이 아니다.

§5.5 접수 상태 재계산은 `domain/RegistrationStatus.kt` 로 **이미 옮겼다** — 그건 조회 시점마다
다시 계산해야 하는 값이라 앱에도 있어야 한다. 정규화와는 다른 이야기다.

> 이 결정으로 **AP-04 는 남은 항목이 없다.**

### 2.2 동선 생성은 앱 포팅 대상이 아니다

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
| **근처에서 뛸 만한 곳** | 서버 (목표 거리·HARD 제외 큐레이션 경로 우선, 적합 경로가 없으면 품질 상한 OSM/GraphHopper 생성 1건 + 카카오 걷기 스팟을 **서버에서 합쳐** 거리순으로) | `GET /courses/near` — 아래 참고 |
| 코스 목록(지역별) | 서버 (두루누비 메타 + GPX) | `GET /courses` · `/courses/regions` |
| 대회·찜·동선·저장 코스 | 서버 (SSOT) | `GET /contests` · `/me/**` · `/itineraries` · `/me/courses` |

**앱에 남는 계산은 이것뿐이다.** 서버가 생성한 동선을 저장 전에 편집하고, 회복 안내를 미리 표시하고,
종목을 표준화하는 일. 그리고 오프라인일 때 번들된 GPX 축약본으로 코스를 자르는 일. 새 동선 조립은 서버만 한다.

### 3.1 근처 큐레이션·OSM 코스와 걷기 스팟은 서버가 합쳐서 준다

SPEC §4.11(a)는 경로와 장소를 **하나로 합쳐 거리순으로** 보여준다. 항목 카드에 원천 이름을 붙이지
않되 실제 사용 원천은 응답 `attributions[]`의 검증된 문구를 변경하지 않고 목록 하단에 표시한다. 앱이 `/courses/near`·
`/walk-spots`·GraphHopper를 따로 부르고 직접 섞는 방식은 맞지 않는다.

- 두 번 부르면 **둘 다 도착해야 정렬이 확정**된다 → 목록이 늦게 뜨거나 순서가 튄다
- 섞고 정렬하는 규칙이 앱과 서버에 두 벌로 갈라진다

그래서 `GET /courses/near` 하나가 목표 거리에 맞고 HARD가 아닌 큐레이션 경로를 먼저 찾고, 0건이면 품질 상한을 통과한 OSM 경로를 최대 1건 생성한
뒤 장소와 섞어 거리순으로 반환한다. 항목마다 경로 유무와 경로 원천을 구분하는 필드를 둔다.

```
GET /courses/near?lat=&lng=&targetKm=&radiusKm=
→ items[] 공통: kind(ROUTE | PLACE) · name · distanceM · lat · lng
   ROUTE 이면  routeId · dataSource · difficulty · routeKm · durationMin · gainM · elevationProfileM · shortfall · pathPolyline
                 (큐레이션만 sourceCourseId · sido · sigun · fullDistanceKm)
   PLACE 이면  category · address · placeUrl
  degradedSources[] · attributions[]
```

앱은 받은 순서대로 그리고 응답 `difficulty`를 표시만 한다. 출발지 주변 값은 생성된 왕복 구간의 상승 기준이고 지역별 값은 전체 원본 코스 등급이므로 달라도 정상이다. P0 출발지 주변에는 난이도 칩과
`CourseLaunchContext.difficulty`가 없다. 항목이 있으면 호출 실패 `degradedSources`를 비차단 안내로,
항목 없이 원천 실패면 Error로 매핑한다. 품질 상한 통과 후보 0건은 degraded가 아닌 정상 결과다.

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
`Recovery` `Cats` `Patterns` `EventStd` `KstDates`. (`RaceNormalizer` 는 2.1 대로 만들지 않는다)
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
카카오맵 Android SDK와 `/courses/near` DTO를 연결한다. 온라인 경로는 서버 응답만 표시하고,
`CourseBuilder`는 번들 GPX 오프라인 폴백에만 사용한다. 표시용 난이도·고도 스트립·동적 출처 문구를 포함하며 P0 난이도 칩은 만들지 않는다.

**~~6단계 · GPS 기록~~** — **없앴다** 🔒확정(SPEC 결정-56 · 이슈 #215)
포그라운드 서비스도 위치 수집도 만들지 않는다. 사업자등록·위치기반서비스 신고 부담을
지지 않기 위해 자동 위치 추정을 제품에서 전부 뺐다.

---

## 7. 아직 안 정한 것

- **Hilt 를 쓸지** — SPEC 은 "선택" 으로 열어 두었다. 화면 수가 적어 수동 주입으로도 된다.

---

## 8. P0 확정 — OSM 도시 러닝코스

결정-42에 따라 수도권·평지 도시의 큐레이션 공백은 서버 GraphHopper가 OSM 순환 경로를 생성해
채운다. 카카오모빌리티·TMAP 보행자 A→B 우회안은 폐기한다.

- 앱은 출발지·목표 거리만 우리 API에 전달한다. S7 연계도 `min(RECOVERY.walk,5)` 목표 거리만 넘긴다.
- GraphHopper 주소·OSM 그래프·SRTM은 앱에 포함하지 않는다.
- 목표 거리·상승 `<50m/km`에 맞는 큐레이션이 0건일 때만 OSM 생성 경로 최대 1건을 받는다.
- OSM은 거리 75~125%·상승 <50m/km·실거리 차도 ≤10%·실제 회전 ≤6회/km를 모두 통과해야 하며 상한을 완화하지 않는다.
- 계단은 런타임 필터·정렬에 추가하지 않는다. AP-25에서 PR #32 `--preset caps`로 선택 경로의 `road_class=STEPS` 실거리 비율 `≤1%`만 회귀 검증한다.
- `sourceCourseId`가 없는 OSM 경로도 경로 snapshot으로 저장한다.
- OSM 경로의 한국어 이름은 서버가 생성하며 앱은 재조합하지 않고 같은 이름을 snapshot에 저장한다.
- `degradedSources=OSM`과 장소가 함께 오면 Content+안내, 항목도 없으면 Error다. 적격 후보 0건은 degraded가 아니다.
- `© OpenStreetMap contributors`는 서버가 준 `attributions[]`를 그대로 표시한다.
