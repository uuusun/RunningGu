# 런닝구(區) 🏃

> 전국 마라톤 일정 통합 + 대회 전후 **여행 동선 자동 추천** + 위치 기반 **러닝·산책 코스** + 러너 **커뮤니티**를 갖춘 러닝 관광 **안드로이드 앱**
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

핵심 기능(M1~M4·C·B): 종목·상황별 맞춤 동선 추천 · 전국 마라톤 통합 캘린더 · 대회 인근 축제 · 러닝/산책 코스 · 러너 커뮤니티 · 보관함.

---

## 🛠 기술 스택

| 영역 | 스택 |
|---|---|
| 언어 | Kotlin |
| UI | Jetpack Compose |
| 아키텍처 | MVVM |
| 네트워크 | Retrofit + OkHttp — 자체 백엔드 API 경유 (KTO·카카오 REST는 백엔드에서만 호출) |
| 로컬 저장 | Room · DataStore |
| 지도 | 카카오맵 **Android SDK** |
| 로그인 | 카카오 로그인 **Android SDK** |
| 백엔드 | **Spring Boot + PostgreSQL** — 인증·커뮤니티·보관함·외부 API 프록시 |
| 데이터 생성 | Python 스크립트 (1회성·보관용) |

> ⚠️ 지도·로그인은 웹 JS SDK가 아니라 **안드로이드 SDK**를 써야 한다. 웹 프로토타입의 카카오맵 JS 연동은 참고만 하고 재구현한다.

---

## 📂 폴더 구조

```
runninggu/
├── README.md            # 이 문서
├── CONVENTION.md        # Git 브랜치·커밋·PR 규칙 (팀 필독)
├── SPEC.md              # 최종 통합 명세 (단일 기준, SSOT)
├── .gitignore
│
├── app/                 # 안드로이드 앱 모듈
│   └── src/main/
│       ├── java/com/runninggu/app/
│       │   ├── ui/          # 화면 (Compose)
│       │   │   ├── auth/     # A1 로그인 · A2 회원가입 · A3 비번찾기
│       │   │   ├── home/     # S1 홈
│       │   │   ├── calendar/ # S2 캘린더
│       │   │   ├── course/   # S8 러닝코스
│       │   │   ├── community/# S9 커뮤니티
│       │   │   ├── archive/  # S10 보관함
│       │   │   └── wizard/   # S4~S7 동선 만들기
│       │   ├── data/        # 리포지토리·API·모델
│       │   │   ├── remote/  # Retrofit (KTO·카카오)·Supabase
│       │   │   ├── local/   # Room·DataStore
│       │   │   └── model/
│       │   ├── domain/      # 추천 엔진·코스 빌더 로직 (웹 engine.js 재구현)
│       │   └── util/
│       ├── res/            # 레이아웃·문자열·아이콘·테마
│       └── assets/         # races.json·durunubi_courses.json 번들
│
├── build.gradle.kts     # 프로젝트 빌드 설정
├── settings.gradle.kts
│
├── docs/                # 기획·API 매뉴얼
├── scripts/             # 데이터 생성 Python (1회성·보관용, 크롤 CSV→races.json)
├── data/                # 원천 데이터 (CSV 등)
└── reference-web/       # 구 design/ (React 목업 — UX 참조용, 빌드 대상 아님)
```

> 참고: 기존 repo의 `design/`(React 웹 프로토타입)은 안드로이드 앱으로 이관되지 않으며 `reference-web/`으로 옮겨 **UX·화면흐름·로직 설계 참조**로만 쓴다. 옛 `web/`(Next.js 데모)는 사용하지 않는다. 앱이 쓰는 데이터(`races.json` 등)는 `app/src/main/assets/`에 번들한다.

---

## 🚀 시작하기

### 사전 준비
- Android Studio (최신 안정판)
- JDK 17
- 카카오 · 한국관광공사 API 키

### 실행
1. Android Studio에서 프로젝트 열기 → Gradle Sync
2. `local.properties`에 API 키 입력 (아래) — **커밋 금지**
3. 에뮬레이터 또는 실기기에서 Run ▶

### API 키 (`local.properties`)

`local.properties`는 기본적으로 `.gitignore`에 포함되어 커밋되지 않는다. 각자 로컬에 아래 키를 채운다.

```
KAKAO_NATIVE_APP_KEY=    # 카카오 네이티브 앱 키 (지도·로그인) — 앱에 포함되는 유일한 키
```

> 키는 코드에 하드코딩하지 않고 `BuildConfig`로 주입한다.
> **KTO·카카오 REST 키는 앱에 넣지 않는다** — 백엔드(Spring Boot) 환경변수로만 사용한다. (SPEC §9.4)

---

## 🌱 협업 규칙 (요약)

전체 규칙은 [`CONVENTION.md`](./CONVENTION.md) 참고. 핵심만:

- **브랜치 전략: GitHub Flow** — `main`에 직접 push 금지. `main`에서 브랜치를 따서 작업 후 PR.
- **브랜치 이름**: `feat/home-screen`, `fix/calendar-filter` 처럼 `<종류>/<설명>`.
- **커밋 메시지**: `feat(home): 홈 화면 구성` 처럼 `<종류>(<범위>): <설명>`.
- **PR**: 기능 하나 = 브랜치 하나 = PR 하나. 최소 1명 리뷰 승인 후 머지, 머지된 브랜치는 삭제.
- **금지**: `local.properties`·API 키·`/build`·`.gradle`·`.idea` 커밋.

---

## 📖 주요 문서

| 문서 | 내용 |
|---|---|
| [SPEC.md](./SPEC.md) | 서비스 최종 명세 (화면·기능·데이터 계약) — **작업 전 필독** |
| [CONVENTION.md](./CONVENTION.md) | Git 브랜치·커밋·PR 규칙 |

---

## 👥 팀

런닝구(區) — 3인 (역할 유동, GitHub Flow 기반 협업)
