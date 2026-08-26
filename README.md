# 런닝구(區) 🏃

> 전국 마라톤 일정 통합 + 대회 전후 **여행 동선 자동 추천** + 위치 기반 **러닝·산책 코스**(GPS 기록)를 갖춘 러닝 관광 **안드로이드 앱**
>
> 2026 관광데이터 활용 공모전 (웹/앱 개발 · 예비심사 통과)

---

## 📌 프로젝트 개요

| 항목 | 내용 |
|---|---|
| 서비스명 | 런닝구(區) |
| 타깃 | 2030 러너 (5K·10K·하프 중심) |
| 필수요건 | 한국관광공사 OpenAPI 활용 (국문관광정보·두루누비·웰니스) |
| 형태 | **안드로이드 네이티브 앱** |

핵심 기능(M1~M4·B·R): 종목·상황별 맞춤 동선 추천 · 전국 마라톤 통합 캘린더 · 대회 인근 축제 · 러닝/산책 코스 · GPS 러닝 기록 · 찜·마이.

---

## 🛠 기술 스택

| 영역 | 스택 |
|---|---|
| 언어 | Kotlin(Android) · Java 21(백엔드) |
| UI | Jetpack Compose |
| 아키텍처 | MVVM |
| 네트워크 | Retrofit + OkHttp — 자체 백엔드 API 경유 (KTO·카카오 REST는 백엔드에서만 호출) |
| 로컬 저장 | Room 읽기 캐시·GPS 임시 기록 · DataStore |
| 지도 | 카카오맵 **Android SDK** |
| 로그인 | 카카오 로그인 **Android SDK** |
| 백엔드 | **Spring Boot + PostgreSQL** — `com.runninggu.server`, 인증·canonical 대회·마이·외부 API 프록시·두루누비 동기화 |
| 데이터 생성 | Python 스크립트 (1회성·보관용) |

> ⚠️ 지도·로그인은 웹 JS SDK가 아니라 **안드로이드 SDK**를 써야 한다. 웹 프로토타입의 카카오맵 JS 연동은 참고만 하고 재구현한다.

---

## 📂 폴더 구조

```
runninggu/
├── README.md            # 이 문서
├── AGENTS.md            # Codex·Claude Code 공통 작업 지침
├── CONVENTION.md        # Git 브랜치·커밋·PR 규칙 (팀 필독)
├── SPEC.md              # 최종 통합 명세 (단일 기준, SSOT)
├── .gitignore
│
├── android/             # Android Studio 프로젝트
│   └── app/src/main/    # 안드로이드 앱 모듈
│       ├── java/com/runninggu/app/
│       │   ├── ui/          # 화면 (Compose)
│       │   │   ├── auth/     # A1 로그인 · A2 회원가입 · A3 비번찾기
│       │   │   ├── home/     # S1 홈
│       │   │   ├── calendar/ # S2 캘린더
│       │   │   ├── course/   # S8 러닝코스 (+GPS 기록)
│       │   │   ├── my/      # S10 마이 (프로필·정보수정·동선·코스·찜)
│       │   │   └── wizard/   # S4~S7 동선 만들기
│       │   ├── data/        # 리포지토리·API·모델
│       │   │   ├── remote/  # Retrofit (자체 백엔드 단일 창구)
│       │   │   ├── local/   # Room 읽기 캐시·DataStore
│       │   │   └── model/
│       │   ├── domain/      # 앱 순수 로직·저장 전 편집·코스 빌더 (동선 생성 엔진은 서버)
│       │   └── util/
│       ├── res/            # 레이아웃·문자열·아이콘·테마
│       └── assets/         # races.json·durunubi_courses.json 번들
│
│   ├── build.gradle.kts # 프로젝트 빌드 설정
│   └── settings.gradle.kts
│
├── backend/             # Java 21 Spring Boot 백엔드 (`com.runninggu.server`)
│   ├── src/main/java/com/runninggu/server/
│   │   └── common/      # 오류·보안·OpenAPI·KST/UTC·JPA 공통 기반
│   ├── compose.yaml     # 로컬 PostgreSQL 17
│   └── build.gradle.kts # 백엔드 독립 Gradle 빌드
│
├── docs/                # 기획·API 매뉴얼
├── scripts/             # 데이터 생성 Python (1회성·보관용, 크롤 CSV→races.json)
├── data/                # 원천 데이터 (CSV 등)
└── reference-web/       # 구 design/ (React 목업 — UX 참조용, 빌드 대상 아님)
```

> 참고: `reference-web/`은 **UX·화면흐름·로직 설계 참조**로만 쓴다. 앱의 초기 대회본과 GPX 축약 폴백은 `android/app/src/main/assets/`에 두되, 온라인 데이터의 SSOT는 백엔드다.

---

## 🚀 시작하기

### 사전 준비
- Android Studio (최신 안정판)
- JDK 21
- Docker Desktop (백엔드 PostgreSQL·통합 테스트)
- 카카오 · 한국관광공사 API 키

### Android 실행
1. Android Studio에서 `android/` 열기 → Gradle Sync
2. `local.properties`에 API 키 입력 (아래) — **커밋 금지**
3. 에뮬레이터 또는 실기기에서 Run ▶

### 백엔드 실행

백엔드 환경변수·PostgreSQL·Swagger 실행 방법은 [`backend/README.md`](./backend/README.md)를 따른다.

```powershell
cd backend
.\gradlew.bat clean test bootJar --console=plain
```

### API 키 (`local.properties`)

`local.properties`는 기본적으로 `.gitignore`에 포함되어 커밋되지 않는다. 각자 로컬에 아래 키를 채운다.

```
KAKAO_NATIVE_APP_KEY=    # 카카오 네이티브 앱 키 (지도·로그인) — 앱에 포함되는 유일한 키
```

> 키는 코드에 하드코딩하지 않고 `BuildConfig`로 주입한다.
> **KTO·카카오 REST 키는 앱에 넣지 않는다** — 백엔드(Spring Boot) 환경변수로만 사용한다. (SPEC §9.4)

### 릴리스 빌드 (스토어 제출용)

디버그 빌드는 위 한 줄이면 되지만, **스토어에 올릴 산출물은 두 가지가 더 필요하다.**

```
API_BASE_URL=https://<배포 호스트>/api/   # 없으면 자리표시자가 들어가 서버를 못 찾는다

RELEASE_STORE_FILE=C:/keys/runninggu.jks  # keystore 는 저장소 밖에 둔다
RELEASE_STORE_PASSWORD=
RELEASE_KEY_ALIAS=
RELEASE_KEY_PASSWORD=
```

**둘 다 없어도 빌드는 된다** — CI 에는 keystore 가 없기 때문이다. 대신 `assembleRelease`·
`bundleRelease` 가 경고를 띄운다.

```
[release] BASE_URL 이 자리표시자입니다 — … 이대로 만든 산출물은 서버를 못 찾습니다.
[release] 서명 keystore 가 없습니다 — 서명 없는 산출물이 나옵니다.
```

**이 경고가 뜬 산출물은 스토어에 못 올린다.** 서명 없는 APK 는 스토어가 받지 않고,
자리표시자 주소로 만든 APK 는 설치는 되지만 화면이 전부 네트워크 오류다.

**`API_BASE_URL` 을 적었는데 모양이 틀리면 경고가 아니라 빌드가 멈춘다.** `https://` 로 시작하고
`/` 로 끝나야 한다 — 끝 `/` 가 없으면 앱이 켜지자마자 Retrofit 초기화에서 죽는다. 값 없이
`API_BASE_URL=` 만 적은 것은 안 적은 것과 같게 보고 위 자리표시자 경고를 띄운다.

```
[release] local.properties 의 API_BASE_URL 을 쓸 수 없습니다 — Retrofit baseUrl 은 / 로 끝나야 합니다. …
```

> keystore 는 **잃어버리면 같은 앱을 다시 올릴 수 없다.** 팀에서 한 벌만 만들어
> 안전한 곳에 보관하고, **저장소에는 절대 넣지 않는다**(AGENTS 8장).

---

## 🌱 협업 규칙 (요약)

공통 작업 지침은 [`AGENTS.md`](./AGENTS.md), Git 상세 규칙은 [`CONVENTION.md`](./CONVENTION.md) 참고. 핵심만:

- **브랜치 전략: Git Flow** — 최신 `develop`에서 작업 브랜치를 만들고 `develop` 대상으로 PR. `main`은 릴리스 전용.
- **직접 push 금지**: `main`·`develop`에는 직접 push하지 않고 작업 브랜치 → PR → 리뷰 → 머지.
- **브랜치 이름**: `feature/home-screen`, `fix/calendar-filter`처럼 `<종류>/<설명>`.
- **커밋 메시지**: `feat(home): 홈 화면 구성` 처럼 `<종류>(<범위>): <설명>`.
- **PR**: 기능 하나 = 브랜치 하나 = PR 하나. 최소 1명 리뷰 승인 후 머지, 머지된 브랜치는 삭제.
- **금지**: `local.properties`·API 키·`/build`·`.gradle`·`.idea` 커밋.

---

## 📖 주요 문서

| 문서 | 내용 |
|---|---|
| [SPEC.md](./SPEC.md) | 서비스 최종 명세 (화면·기능·데이터 계약) — **작업 전 필독** |
| [AGENTS.md](./AGENTS.md) | Codex·Claude Code 공통 작업 지침 |
| [CONVENTION.md](./CONVENTION.md) | Git 브랜치·커밋·PR 규칙 |
| [개발·배포·출시·공모전 제출 지침](./docs/development-release-contest-guide.md) | 운영 배포, Google Play 출시, 심사자료 제출 체크리스트 |
| [P0·P1·출시·공모전 제출 WBS](./docs/contest-release-wbs.md) | 2026-08-21부터 공모전 마감까지 담당·선행조건·완료 정의·출시 핵심 경로 |

---

## 👥 팀 · 역할 분담

| 팀원 | 담당 |
|---|---|
| **유선경** | **백엔드 전담** — USER+LOGIN_IDENTITY 인증·canonical 대회/원천·마이/찜/기록·외부 API 프록시·두루누비 동기화·springdoc·배포 |
| **이건모** | **앱 UI** — Compose 테마·4탭 내비게이션·인증/홈/캘린더/위저드/결과/마이 정보수정·찜 UI·Loading/Content/Empty/Error |
| **김민지** | **앱 코어** — 프로젝트 셋업·Retrofit/Room·공통 ProblemDetail/Enum/페이징 계약·카카오맵·두루누비+GPX 코스·GPS 기록·UTF-8 데이터 CI |

> 앱 담당 2인은 2026-08-17에 UI ↔ 코어를 맞바꿨다. 진행 상황은 [SPEC.md §11](./SPEC.md) 참고.

상세 백로그 매핑은 [SPEC.md §11](./SPEC.md), 공통 작업 지침은 [AGENTS.md](./AGENTS.md), Git 협업 규칙은 [CONVENTION.md](./CONVENTION.md) (Git Flow · PR 상호 리뷰).
