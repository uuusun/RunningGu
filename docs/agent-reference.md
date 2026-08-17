# 공통 지침 — 상세 참고

`AGENTS.md` 는 매 작업마다 필요한 규칙만 담는다. 이 문서는 **필요할 때 찾아보는 상세**다.
규칙 자체는 `AGENTS.md` 가 기준이고, 여기는 배경과 세부 절차를 적는다.

---

## 1. 프로젝트 배경

**런닝구(區)** — "내가 뛸 동네를 가장 잘 아는 친구".
안드로이드 네이티브 앱(Kotlin · Compose · MVVM) + 백엔드(Spring Boot · PostgreSQL).
2026 관광데이터 활용 공모전 출품작이라 **한국관광공사 OpenAPI 활용이 필수 요건**이다.
타깃은 2030 러너(5K · 10K · 하프 중심).

푸는 문제 셋 — ① 마라톤 일정이 흩어져 있다 ② 종목별 회복 강도를 반영한 대회 전후 여행 정보가 없다
③ "실제로 뛰기 좋은 코스" 는 현지 러너들만 안다.

차별성은 `SPEC.md` §1.1 의 D1~D4 다. D5(커뮤니티)는 범위에서 빠졌다.

### 범위에서 뺀 것 — 되살리자는 제안을 하지 않는다

| 뺀 것 | 근거 |
|---|---|
| 커뮤니티(동선 · 코스 공유) | 🔒결정-13 · `SPEC.md` §4.12 |
| 인기 대회 조회수 집계 | 🔒결정-11 |
| 전 대회 POI 사전수집 | MVP 제외 (AP-20) |
| 이메일 주소 변경 | MVP 제외 |
| 동선의 산책 블록 | §5.6 삭제 확정 |

이번 버전 필수 기능과 백로그 ID 는 `SPEC.md` §11 P0 표를 따른다.

---

## 2. 저장소 구조

| 경로 | 역할 | 빌드 대상 |
|---|---|---|
| `android/` | 안드로이드 앱 — **제품** | ✅ |
| `android/tools/codex-orchestrator/` | 개발 보조 도구 | 별도 모듈 |
| `reference-web/` | 웹 참조 구현(JS). 로직 설계 참조용 | ❌ |
| `docs/` | 명세 · 목업 · API 문서 | ❌ |
| `data/` | 대회 원천 데이터 | ❌ |
| `scripts/` | 크롤링 · 데이터 생성 · 검증(Python) | ❌ (CI 에서 실행) |
| `tools/figma-flow-board/` | 화면 플로우 보드 Figma 플러그인 | ❌ |

### 목업 · 참조 구현 · 실제 코드

셋을 구분하지 못하면 낡은 것을 그대로 옮기게 된다.

| 구분 | 위치 | 지위 |
|---|---|---|
| **실제 코드** | `android/` | 빌드 · 배포 대상. 이것만 제품이다 |
| **참조 구현** | `reference-web/` | 빌드 대상 아님. **일부가 SPEC 보다 낡다** |
| **화면 목업** | `docs/mockup-design/` | 로직 없이 결과를 하드코딩해 보여준다 |

### 직접 고치면 안 되는 자동 생성 파일

| 파일 | 만드는 것 |
|---|---|
| `tools/figma-flow-board/code.js` | `docs/mockup-design/shots/build-layout.mjs` |
| `docs/mockup-design/shots/layout.json` · `README.md` | 위와 같음 |
| `docs/mockup-design/shots/png/**` | `capture-screens.mjs` |

생성물을 고치지 말고 **생성기와 입력을 고친 뒤 다시 돌린다.**

---

## 3. 개발 환경 설정

`android/local.properties` 에 SDK 경로를 적는다. gitignore 대상이라 각자 만든다.
Android Studio 로 프로젝트를 열면 자동 생성된다.

| 설치 방식 | `sdk.dir` |
|---|---|
| Android Studio (macOS) | `/Users/<이름>/Library/Android/sdk` |
| Android Studio (Windows) | `C\:\\Users\\<이름>\\AppData\\Local\\Android\\Sdk` |
| Homebrew 커맨드라인 툴 | `/opt/homebrew/share/android-commandlinetools` |

데이터 스크립트를 돌릴 때만:

```bash
pip install -r scripts/requirements.txt
```

### 전체 명령

```bash
# macOS / Linux
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
cd android
./gradlew :app:testDebugUnitTest              # 단위 테스트
./gradlew :app:assembleDebug                  # 디버그 빌드
./gradlew :app:connectedDebugAndroidTest      # 계측 테스트 (기기·에뮬레이터 필요)
```

```powershell
# Windows
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd android
.\gradlew.bat :app:testDebugUnitTest
```

JDK 는 **21** (`android/gradle/gradle-daemon-jvm.properties` 의 `toolchainVersion`).

**린트 · 포맷터는 아직 없다.** ktlint · detekt · spotless 어느 것도 설정돼 있지 않으므로
"린트 통과" 를 완료 조건으로 삼지 않는다. 도입은 별도 논의 사항이다.

---

## 4. 아키텍처 세부

```
android/app/src/main/java/com/runninggu/app/
├── ui/        Compose 화면 + 화면별 ViewModel(StateFlow<UiState>)
├── domain/    §5 전체. 순수 Kotlin — Android import 금지
├── data/
│   ├── remote/  Retrofit. 자체 백엔드 단일 창구 + DTO→도메인 매퍼
│   ├── local/   assets 폴백 · Room 읽기 캐시 · DataStore(세션)
│   └── model/   §6 계약 데이터 클래스
└── util/
```

불변 규칙은 `AGENTS.md` 4장에 있다. 아래는 그 배경이다.

- **`domain` 이 순수 Kotlin 인 이유** — 기기 · 에뮬레이터 없이 단위 테스트가 돌아야 한다.
  `android.*` 를 하나라도 import 하면 JVM 테스트에서 깨진다.
- **Room 이 SSOT 가 아닌 이유** — 마이 · 찜 · 동선 · 기록은 서버가 진실이다.
  Room 을 진실로 취급해 양방향 동기화하면 충돌 해소 로직이 필요해지고, 그건 MVP 범위가 아니다.
- **앱이 외부 API 를 직접 부르지 않는 이유** — 앱은 뜯으면 키가 나온다.
  KTO · 카카오 REST 키는 서버에만 둔다. 앱에 있는 키는 카카오 **네이티브** 키 하나뿐이다.
- **오프라인** — assets 의 대회 초기본과 GPX 축약본으로 폴백하고, 응답의 `source` 를 UI 까지 전달한다.

---

## 5. 코딩 규칙 세부

- **네이밍** — 화면 `XxxScreen` · 상태 `XxxUiState` · 뷰모델 `XxxViewModel` ·
  DTO `XxxDto` · 매퍼 `toDomain()` / `toDto()`
- **오류 처리** — 서버 오류는 RFC 9457 `problem+json` 의 안정적 `code` 로 분기한다.
  HTTP 상태 코드만 보고 분기하지 않는다. 화면에는 네 상태 중 `오류` 로 표현한다.
- **로깅** — 토큰 · 비밀번호 · 인증 코드 · 이메일 주소 · 사용자 좌표를 남기지 않는다.
  릴리스 빌드에 디버그 로그를 남기지 않는다.
- **주석** — 인용한 명세 절을 남긴다(`/** ... (SPEC §5.1) */`). **왜** 그렇게 했는지를 적고,
  코드를 읽으면 아는 것을 반복하지 않는다.
- **기존 패턴 우선** — 같은 일을 하는 코드가 이미 있으면 그 방식을 따른다.
  더 나은 방식이 있으면 제안하되, 요청 없이 기존 코드를 갈아엎지 않는다.

---

## 6. 계약 규칙의 배경

`AGENTS.md` 7장의 규칙이 왜 그렇게 정해졌는지.

### "먼저 만든 사람이 기준" 을 쓰지 않는 이유

한때 "연결부는 먼저 만든 사람이 기준, 나중 사람이 맞춘다" 를 검토했으나 접었다.

- 속도가 옳음을 이긴다 — 먼저 친 사람이 제대로 설계했다는 보장이 없는데,
  나중 사람은 재작성 말고는 이의 제기 수단이 없다
- 둘 다 같은 날 시작하면 "먼저" 를 판정할 수 없다
- **계약이 남의 구현 코드 속에 숨는다** — 상대 코드를 읽어야 계약을 알 수 있는 상태가
  통합 지옥의 원인 그 자체다
- 기준이 된 쪽이 나중에 계약을 바꿔야 할 때 절차가 없다

그래서 주인을 **"주는 쪽(producer)"** 으로 정하고, 받는 쪽에 **리뷰어** 권한을 줬다.

### 막지 않는 조항이 있는 이유

계약 합의를 기다리다 아무도 못 움직이면 그것대로 실패다.
계약 초안 쓰는 비용은 20분이고 통합 지옥은 며칠이다. 그래서 순서만 지키고 진행은 막지 않는다.

### 명세 드리프트가 새는 자리

코딩하다 보면 명세대로 안 되는 게 나오고, 대화하다 결정이 바뀌고, 더 나은 방법을 찾는다.
그런데 **코드만 고치고 문서는 그대로 둔다.** 다른 파트는 낡은 명세를 보고 만들고,
합칠 때 어긋난 걸 발견하고, 거기서 시간이 다 날아간다.

그래서 "다음 PR 로 미룬다" 를 금지하고 **같은 PR** 을 강제한다. 미룬 문서 작업은 안 된다.

CI 의 **Spec sync check**(`scripts/check_spec_drift.py`)가 바뀐 코드에서 `SPEC §x.y` 참조를 뽑아
확인할 절 목록을 띄운다. 막지는 않는다 — 리팩터링처럼 명세가 안 바뀌는 변경도 많기 때문이다.

---

## 7. 알려진 불일치

### 문서별 기준 시점

| 문서 | 작성·갱신 | 무엇을 반영했나 |
|---|---|---|
| `docs/files/런닝구_API_명세서.md` | 2026-08-01 (08-17 `host`→`organizer` 한 줄) | 목업 v2 이전 |
| `SPEC.md` §9.3 (API 초안) | 2026-08-01 이후 그대로 | 목업 v2 이전 |
| `docs/screen-api-matrix.md` | **작성 08-13** · 커밋 08-17 | 화면 **28장** 기준 |
| `docs/android-porting-plan.md` | 2026-08-14 | 도메인 대조 결과 |
| `SPEC.md` §4.11 (S8 화면) | **2026-08-17** | 목업 v2 러닝코스 통합 |
| 목업 v2 | 08-04 추가 · **08-17 개정** | 화면 **88장** |
| springdoc-openapi | 아직 없음 | 구현 후 최종 실행 계약 |

세 문서가 서로 다른 시점을 보고 있다. 특히 `screen-api-matrix.md` 는 **08-13 기준**이라
08-14 포팅 계획서와 08-17 목업 개정이 빠져 있다.

**S8 러닝코스 관련 API 를 만질 때는 매트릭스를 그대로 믿지 말고 `SPEC.md` §4.11 과 대조한다.**
정리되면 이 절을 지운다.

### `/walk-spots` — 확정된 불일치

| 문서 | 내용 | 시점 |
|---|---|---|
| `런닝구_API_명세서.md` 4-3 · `SPEC.md` §9.3 | `GET /walk-spots` 가 **있다** | 08-01 |
| `docs/screen-api-matrix.md` S8 | `/courses/near` 와 **따로 호출**. 상태 "확정" | 08-13 |
| `docs/android-porting-plan.md` §3.1 | 앱이 따로 부르면 **안 된다**. `/courses/near` 가 합쳐서 준다 | 08-14 |
| `SPEC.md` §4.11 · 목업 v2 | 코스와 걷기 스팟을 **한 목록**으로 합치고 거리순 정렬. 출처 비노출 | 08-17 |

가장 새로운 두 개(포팅 계획서 · 목업 개정)가 "합쳐서 준다" 쪽이고, 매트릭스는 그 전에 쓰였다.

따로 부르면 안 되는 이유 — ① 두 번 부르면 **둘 다 도착해야 정렬이 확정**돼 목록이 늦게 뜨거나
순서가 튄다 ② 섞고 정렬하는 규칙이 **앱과 서버에 두 벌로 갈라진다.**

제안된 형태:

```
GET /courses/near?lat=&lng=&targetKm=&radiusKm=
→ items[]: { kind: ROUTE | PLACE, name, distanceM, ... }
   ROUTE 이면 routeKm · minutes · level · routePoints · shortfall
   PLACE 이면 category · placeUrl
```

**결정은 서버 담당(유선경)이 한다.** 캐싱 전략과 구현 난이도가 판단에 들어가기 때문이다.
앱은 소비자로서 의견만 낸다(`AGENTS.md` 4장). 정해지면 `SPEC.md` §9.3 · API 명세서 ·
매트릭스를 함께 고치는 PR 을 올린다.
