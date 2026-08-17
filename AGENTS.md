# 런닝구 — AI 에이전트 작업 규칙

이 파일은 **Codex · Claude Code 공용**이다. 한쪽에서 규칙을 바꾸면 여기를 고친다.
(`CLAUDE.md` 는 이 파일을 불러오는 한 줄짜리다. 내용을 거기 복사하지 말 것.)

한국어로 답한다. 커밋 메시지·PR·주석도 한국어다.

---

## 1. 문서 우선순위

충돌하면 위가 이긴다.

| 문서 | 지위 |
|---|---|
| `SPEC.md` | **단일 진실 공급원.** §5 규칙 값은 "포팅 시 변경 금지" |
| `CONVENTION.md` | Git·PR 규칙 |
| `docs/domain-logic-audit.md` | 원본 코드가 SPEC보다 낡은 지점 대조표 |
| `docs/android-porting-plan.md` | 파일 단위 포팅 순서 |
| `reference-web/` | 참조 구현(JS). **낡은 부분이 있다** — 4장 참고 |

SPEC 을 바꿔야 할 근거를 찾으면, 코드를 먼저 고치지 말고 **SPEC 을 같은 PR 에서 함께 고치고 근거를 남긴다.**

---

## 2. Git

**Git Flow.** 기본 브랜치는 `develop` 이다.

```bash
git switch develop && git pull
git switch -c feature/작업이름      # develop 에서 딴다
git push -u origin feature/작업이름
# → develop 대상 PR, 최소 1명 승인 후 Squash and merge
```

- `main` 은 릴리스 때만 건드린다. 평소 PR 대상은 항상 `develop`.
- 브랜치 접두사와 커밋 종류가 한 글자 다르다 — 브랜치는 `feature/`, 커밋은 `feat`.
  나머지(`fix` `docs` `data` `chore` `refactor` `style` `test`)는 양쪽이 같다.
- 커밋 메시지: `<종류>(<범위>): <설명>` — 한국어, 마침표 없이.
  예) `feat(calendar): 필터 모달 추가`
- 머지된 브랜치는 삭제한다.

**에이전트가 임의로 하지 않는 것** — 커밋·push·PR 생성·머지는 사용자가 요청할 때만 한다.

---

## 3. 빌드와 테스트

`domain` 은 Android 의존이 없는 순수 Kotlin이라 **기기 없이** 검증된다.

```bash
# macOS / Linux
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
cd android
./gradlew :app:testDebugUnitTest      # 단위 테스트
./gradlew :app:assembleDebug          # 디버그 빌드
```

```powershell
# Windows
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd android
.\gradlew.bat :app:testDebugUnitTest
```

- JDK **21** (`android/gradle/gradle-daemon-jvm.properties` 의 `toolchainVersion`)
- `android/local.properties` 에 `sdk.dir` 이 필요하다. gitignore 대상이라 각자 만든다.
- CI(`.github/workflows/android.yml`)가 `android/**` 변경 시 같은 명령을 돌린다.

---

## 4. 코드 규칙

```
com/runninggu/app/
├── domain/   순수 Kotlin. Android import 금지. 단위 테스트 필수
├── data/     Retrofit·Room·파서. 서버 통신은 전부 여기
└── ui/       Compose. 화면마다 ViewModel + StateFlow<UiState>
```

- **앱에는 REST 키가 없다.** 외부 API(KTO·카카오 REST)는 전부 서버를 거친다.
  앱에 들어가는 키는 카카오 **네이티브** 키 하나뿐(지도·로그인·공유 SDK용).
- 비동기 화면은 **로딩 / 내용 / 빈 / 오류** 네 상태를 모두 만든다 (SPEC §3-5).
- 날짜는 전부 **KST 기준**. `domain/KstDates.kt` 의 `today()` 를 거친다.
  기기 타임존을 쓰면 해외에서 D-day 가 하루 어긋난다.
- SPEC §5 의 규칙 값을 바꾸는 커밋은 테스트도 함께 바꿔야 한다. 테스트가 계약서다.

---

## 5. ⚠️ 원본을 그대로 옮기면 틀리는 자리

`reference-web/` 은 SPEC 보다 낡았다. 근거는 `docs/domain-logic-audit.md`.

| 항목 | 하지 말 것 → 할 것 |
|---|---|
| 산책 블록 3개 | 원본에 있지만 **빼고** 옮긴다 (§5.6) |
| `rule.walk` | 거리 라벨 아님. **S7→S8 목표거리 `min(walk,5)km`** |
| 대회 블록 | `RACE` + `systemManaged`. 편집 연산 **전부 거부** (서버는 409) |
| `courseRegions()` | 시도명만 말고 **개수도** 반환 (칩에 숫자 표시) |
| 걷기 스팟 limit | 원본 10 → **12** |
| **두루누비 동기화** | **API 응답으로 덮어쓰지 말 것.** 261 → 144 로 반토막 난다. `API_GPX`+`GPX_ONLY` 를 둘 다 서비스 (§8.4) |

서울에는 두루누비 코스가 사실상 없다(반경 8km 내 0건). 걷기 스팟은 폴백이 아니라 **수도권의 기본 경험**이다.

---

## 6. 역할 분담

| 팀원 | 영역 | 백로그 |
|---|---|---|
| 유선경 | 백엔드 (Spring Boot + PostgreSQL) | AP-02 · 07 · 23 · 24(서버) |
| 이건모 | 앱 코어 — domain/data·지도·GPX·GPS·CI | AP-01 · 03 · 04 · 05 · 12 · 14 · 22 · 24(앱) |
| 김민지 | 앱 UI — Compose·내비·화면 | AP-06 · 08 · 09 · 10 · 11 · 13 · 21 |

남의 영역 파일을 고쳐야 하면 먼저 물어본다. 특히 `ui/` ↔ `domain/` 경계는 `UiState` 를 합의하고 병렬로 간다.

---

## 7. 절대 커밋하지 않는 것

`local.properties` · `.env` · `*.jks` · `*.keystore` · API 키 · `build/` · `.gradle/` · `.idea/`

키가 필요하면 `.env.example` 에 **빈 값**으로 항목만 추가한다.
