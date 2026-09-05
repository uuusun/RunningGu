# 런닝구 화면–API 매핑표 v1.12

> 갱신일: 2026-08-24
> 목적: 화면 플로우, Android Navigation, 백엔드 API, 데이터 원천과 저장 위치를 하나의 추적표로 연결한다.
> 화면 기준: `docs/mockup-design/shots/README.md`의 기본 화면·상태·오버레이 89개와 화면 간 커넥터
> 제품 기준: `SPEC.md` v4(SSOT)
> API 기준: `docs/files/런닝구_API_명세서.md` v3.2(시드 계약)

이 문서에서 **화면 커버리지 완료**는 플로우의 모든 화면·상태·행동에 API 또는 로컬 처리 주체가 연결됐다는 뜻이다. API 응답이나 정책이 아직 합의되지 않은 항목은 임의로 확정하지 않고 10장의 결정 목록에 남긴다.

## 0. 읽는 법과 기준

Android 화면은 PostgreSQL이나 한국관광공사 API에 직접 접근하지 않는다.

```text
Compose 화면
  → ViewModel / Repository
  → 우리 백엔드 /api (JSON)
     ├─ PostgreSQL 서버 SSOT
     ├─ 한국관광공사·두루누비 REST API
     ├─ 카카오 REST API
     └─ GraphHopper server-only 내부 프로세스 ← 검증된 버전별 graph artifact

기기 내부
  ├─ DataStore: 세션 토큰·게스트 여부·설정
  ├─ Room: 마지막 성공 응답의 읽기 캐시
  └─ 임시 상태: 필터·위저드·저장 전 동선
```

- KTO·카카오 REST 키는 서버에만 둔다. 앱에는 카카오 Android 네이티브 키만 둔다.
- Room은 읽기 캐시이며 서버와 양방향 동기화하지 않는다.
- 외부 API 응답은 운영 마스터로 영구 복제하지 않는다. TTL 캐시나 마지막 성공 동기화 데이터만 보관한다.
- 화면 route와 HTTP API path는 서로 다른 계약이다.
- 코드·목업·API 문서가 `SPEC.md`와 다르면 `SPEC.md`가 우선한다.
- HTTP API는 백엔드가 계약의 주인이고 Android는 소비자·리뷰어다.

### 상태 표기

| 표기 | 뜻 |
|---|---|
| **확정** | SPEC 또는 API 명세에 계약이 있음 |
| **플로우 확정** | 화면 플로우의 화면·화살표·상태로 행동이 확인됨 |
| **현재 구현** | 최신 `develop` Android 코드에 존재함 |
| **결정 필요** | API·DTO·정책 소유자 합의가 필요함 |
| **P1/P2** | MVP P0 이후 범위 |

### 데이터 원천 표기

| 원천 | 의미 | 영구 저장 |
|---|---|---|
| `SERVER_DB` | 우리 서버의 canonical·사용자 데이터 | PostgreSQL에 저장 |
| `KTO_LIVE` | 한국관광공사 REST API를 런타임에 서버가 프록시 | 원천 영구 저장 없음, TTL 캐시만 |
| `KAKAO_LIVE` | 카카오 REST API를 런타임에 서버가 프록시 | 원천 영구 저장 없음, TTL 캐시만 |
| `KTO_SYNC_GPX` | 두루누비 최신 메타 동기화 + GPX 경로 결합 | 버전 번들에서 시작한 서버 메모리 불변 snapshot. 성공한 전체 동기화만 원자 교체, PostgreSQL 복제 없음 |
| `OSM_GRAPH` | 서버 내부 GraphHopper가 외부 builder의 OSM+SRTM 기반 graph artifact로 순환 경로 생성 | 원천 graph는 EC2 version directory artifact, 응답은 임시 DTO·사용자 저장 시 snapshot만 보관 |
| `LOCAL_STATE` | 화면·ViewModel 메모리 상태 | 저장하지 않음 |
| `LOCAL_CACHE` | Room·DataStore | 제한적 로컬 보관 |
| `ANDROID_SDK` | 지도·카카오 로그인 등 Android SDK | 기능별 최소 상태만 보관 |. **위치 SDK 는 없다**(결정-56)

### 공통 API 규약

| 항목 | 계약 |
|---|---|
| Base URL | `/api` |
| 서버 DB | PostgreSQL, 서버 데이터의 SSOT |
| 인증 | `Authorization: Bearer {accessToken}` |
| 날짜 | 비즈니스 날짜 `YYYY-MM-DD`, 판정 기준 `Asia/Seoul` |
| timestamp | ISO-8601 UTC `Z`, PostgreSQL `timestamptz` |
| 좌표 | `lat`, `lng` Double/WGS84, DB `DECIMAL(10,7)` |
| 명명 | API JSON camelCase, DB snake_case |
| 오류 | RFC 9457 `application/problem+json` + `code`, `traceId`, 선택 `errors[]` |
| 대회 목록 | 커서 페이징 `items`, `nextCursor`, `hasNext` |
| 개인 목록 | 페이지 페이징 `content`, `page{number,size,totalElements,hasNext}` |
| 공개 범위 | 대회·축제·POI·코스 조회, 무상태 동선 생성 |
| 로그인 필요 | 프로필·찜·동선/코스 저장 및 보관함 조회 |
| 화면 상태 | Loading / Content / Empty / Error 구분, 부분 실패는 영역별 분리 |

---

## 1. 화면별 데이터 원천과 저장 위치

| 화면 | 주요 데이터 | 가져오는 형태 | 원천 | 서버 DB 저장 | 기기 저장 |
|---|---|---|---|---|---|
| A0 앱 시작 | access/refresh token, guest 여부 | DataStore 값 + `GET /api/me` | `LOCAL_CACHE` + `SERVER_DB` | 리프레시 토큰 해시 | DataStore 세션 |
| A1 로그인 | token, user, loginProvider | JSON DTO | `SERVER_DB` / 카카오 SDK | USER·LOGIN_IDENTITY(1:1)·토큰 해시 | DataStore token |
| A2 회원가입 | 입력값·인증 상태·가입 결과 | 로컬 폼 + JSON DTO | `LOCAL_STATE` + `SERVER_DB` | USER·LOGIN_IDENTITY·AGREEMENT | 가입 완료 전 폼 상태만 |
| A3 비밀번호 재설정 | 이메일·reset token | JSON + 이메일 링크 웹 | `SERVER_DB` | reset token 해시 | 영구 저장 없음 |
| S1 홈 | 히어로·마감 임박 대회 | 대회 카드 DTO | `SERVER_DB` | canonical CONTEST | Room 읽기 캐시 |
| S1 홈 축제 | 이번 달 추천 축제 | 축제 카드 DTO | `KTO_LIVE` | 영구 저장 없음 | Room/서버 TTL 캐시 |
| S2 캘린더 | 대회 목록·날짜별 건수·찜 | 커서 목록 + counts | `SERVER_DB` | CONTEST·FAVORITE | Room 읽기 캐시 |
| S3 대회 상세 | 대회·주최자·좌표·공식 URL | Contest detail DTO | `SERVER_DB` | canonical CONTEST | Room 읽기 캐시 |
| S3 인근 축제 | 대회일 전후·반경 40km 축제 | `items[]` | `KTO_LIVE` | 영구 저장 없음 | 서버 1일 캐시 |
| S4 일정 | pattern, startDate, endDate | WizardUiState | `LOCAL_STATE` | 없음 | 화면 그래프 메모리 |
| S5 종목·취향 | event, themes | WizardUiState | `LOCAL_STATE` | 없음 | 화면 그래프 메모리 |
| S6 숙소 | 주변 숙소·검색 결과 | POI DTO | `KAKAO_LIVE`, KTO 숙박 폴백 | 영구 저장 없음 | 서버 5분 캐시 |
| S7 새 동선 | recovery, days, blocks | 생성 DTO | 서버 규칙 엔진 + KTO/카카오 POI | 저장 전 없음 | Result ViewModel 임시 DTO |
| S7 저장 동선 | itinerary, day, block | 상세 DTO | `SERVER_DB` | ITINERARY 트리 | Room 읽기 캐시 |
| S8 출발지 주변 통합 목록 | 큐레이션/OSM 코스 경로 또는 주변 공원·산책 장소 | `kind=ROUTE\|PLACE` items + degradedSources + attributions | `KTO_SYNC_GPX` + `OSM_GRAPH` + `KAKAO_LIVE` | 큐레이션 메타·경로와 검증된 GraphHopper graph artifact, OSM 응답은 저장 전 임시 | GPX 축약 폴백·Room·서버 TTL 캐시 |
| S8 지역 코스 | 지역·코스 수·목록·출처 | regions + page + attributions | `KTO_SYNC_GPX` | 버전 번들에서 시작해 최신 전체 KTO 메타를 결합한 서버 메모리 snapshot | Room 읽기 캐시 |
| S10 보관함 | 동선·저장 코스·찜 | Pageable 목록 | `SERVER_DB` | 사용자 소유 데이터 | Room 읽기 캐시 |
| M1 계정 관리 | 프로필·약관·가입 로그인 방식 | JSON DTO | `SERVER_DB` | USER·IDENTITY(1:1)·AGREEMENT | 세션만 DataStore |

---

## 2. 화면 route와 기본 흐름

### route 표

| ID | 플로우 화면 | 목표 route | 최신 Android | 전달값 | 로그인 | 상태 |
|---|---|---|---|---|---|---|
| A0 | 앱 시작·세션 확인 | 별도 route 없음(Startup Gate) | 시작 화면 `home`, 인증 그래프 없음 | 세션 | 선택 | 시스템 Splash + `core-splashscreen`, 세션에 따라 로그인/홈. **`GET /api/me` 검증이 끝나야 시작 화면을 고른다**(#99) — 그동안은 로딩만 보인다(제한 3초). `Expired`는 홈을 열지 않고 곧장 로그인, `Unknown`(네트워크·5xx)은 세션을 지킨 채 홈을 열고 첫 `401`을 `TokenAuthenticator`가 정리 |
| A1 | 로그인 | `login` | `login` | 복귀 목적 route | 불필요 | 현재 구현 · 서버 연결. **카카오 로그인 포함**(§1-7 · #216) — SDK 가 받은 액세스 토큰을 서버에 넘기면 기존 가입자는 홈, 미가입자는 A2 로 간다. 톡이 없거나 톡 로그인이 실패하면 웹 계정 로그인으로 넘어가고, **취소는 실패가 아니라 아무 일도 하지 않는다**. 릴리스 키 해시 등록은 AP-02 · #108 |
| A2 | 회원가입 4단계 | `signup` 내부 step | `signup` 내부 step | 카카오 신규면 SDK token/profile | 불필요 | 현재 구현 · 서버 연결(중복 확인 D-30 · 이메일 인증). **카카오 신규 가입은 닉네임만 받고 인증 단계를 건너뛴다**(§1-8 · #216) — 카카오가 이미 확인한 계정이라 이메일·비밀번호가 없다 |
| A3 | 비밀번호 찾기 | `reset` | `reset` | 없음 | 불필요 | 현재 구현 · 서버 연결. 새 비밀번호 설정은 앱이 아니라 웹(WEB-R1)이다 🔒 |
| WEB-R1 | 새 비밀번호 설정 | 웹 `/reset-password?token=` | Android route 아님 | reset token | 불필요 | SPEC 확정 |
| S1 | 홈 | `home` | `home` | 없음 | 선택 | 현재 구현 |
| S2 | 캘린더 | `calendar?q={q}` | `calendar?q={q}` | 선택 `q` | 선택 | 현재 구현 |
| S3 | 대회 상세 | `raceDetail/{raceId}` | 동일 | `raceId` | 선택 | 현재 구현 |
| S4 | 일정 선택 | `wizard/{raceId}` 그래프의 `plan` | 동일 | `raceId` | 불필요 | 현재 구현 |
| S5 | 종목·취향 | wizard 그래프의 `taste` | 미구현 | 공유 WizardUiState | 불필요 | 플로우 확정 |
| S6 | 숙소 선택 | wizard 그래프의 `stay` | 미구현 | 공유 WizardUiState | 불필요 | 플로우 확정 |
| S7 | 새 동선 결과 | wizard 그래프의 `result` | 동일 | 생성 DTO | 저장 시 필요 | 현재 구현 · 생성·저장 서버 연결(§5-1 · §5-2). 게스트는 저장에서 modal |
| S7-R | 저장 동선 상세 | `itinerary/{itineraryId}` | 현재 구현 | `itineraryId` | 필요 | 🔒확정(#213) — route 를 따로 두고 **S7 본문(`ResultScreen`)을 재사용**한다. 위저드 그래프 밖이다: 복원은 S4 를 지나오지 않아 그래프 스코프 ViewModel 이 기본값으로 살아나면 `planConfirmed` 가드가 S4 로 되돌린다(#192). **P0 에서는 읽기 전용** — 저장 후 편집은 아래 4행(§5-7~5-10)이 맡고 아직 앱에 없다 |
| S8 | 러닝코스 | `courses` | `courses` | 선택 출발지·목표 거리 | 선택 | 현재 구현 placeholder |
| S8-D | 코스 상세 | `courseDetail/near`, `courseDetail/saved/{savedCourseId}` | 미구현 | sealed `CourseDetailKey` | 조회별 상이 | near snapshot은 SavedStateHandle/그래프 상태. `ran` route 는 없다(결정-56) |
| S9 | 커뮤니티 | 만들지 않음 | 없음 | 해당 없음 | 해당 없음 | 범위 제외 |
| S10 | 보관함 | 내부 `my`, UI 라벨 `보관함` | `my` placeholder | 선택 segment | 필요 | route 현재 구현 |
| M1 | 내 정보·계정 관리 | `account` 제안 | 미구현 | 없음 | 필요 | 플로우 확정 |

최신 `develop`의 S1~S4는 서버를 본다(#140) — `SampleData` 참조는 주석에만 남아 있다. 이 표의 API 는 Repository/Retrofit 연결 계약이며, `미구현` 으로 적힌 행은 아직 목표다.

### 기본 흐름

```text
앱 시작 → 세션 유효: 홈
       ├─ 세션 없음: 로그인
       └─ 게스트: 홈 탐색 허용

로그인 ─┬─ 회원가입 4단계 → 홈
        ├─ 비밀번호 찾기 → 이메일 링크의 웹 재설정 → 로그인
        └─ 게스트가 저장 시도 후 로그인 → 원래 화면 복귀 → 사용자가 다시 동작

홈 → 캘린더 → 대회 상세 → 일정 → 종목·취향 → 숙소 → 동선 결과
                                                        ├─ 저장 → 보관함[동선]
                                                        └─ 편집·POI 추가/교체 → 결과

홈 → 러닝코스 → 코스 상세 → [저장] → 보관함[러닝코스]
보관함 ─┬─ 동선 카드 → 저장 동선 상세
        ├─ saved 카드 → 코스 상세
        ├─ 찜 카드 → 대회 상세
        └─ 설정 → 내 정보·계정 관리
```

---

## 3. 공통·인증 매핑

### 공통 동작

| 행동 | 처리 | 요청/데이터 | 결과 | 실패·상태 | 저장 |
|---|---|---|---|---|---|
| 세션 읽기 | DataStore | token, guest | 세션 분기 | token 없음→로그인 | 기기 |
| 세션 검증 | `GET /api/me` | Bearer token | user profile | 401→refresh | 서버 DB 조회 |
| 토큰 재발급 | `POST /api/auth/refresh` | refreshToken | 같은 기기 family에서 회전된 token pair | 실패→세션 삭제·로그인, 과거 토큰 재사용이면 해당 family 폐기 | 서버 SHA-256 hash + DataStore |
| 게스트 저장·찜 차단 | Android guard | 원래 route와 동작 종류 | 로그인 모달 | 로그인 후 원래 화면 복귀, **자동 실행하지 않음** | route 임시 상태 |
| 공통 API 오류 | Problem Details parser | status, code | 화면별 Error | Empty로 강등 금지 | 저장 없음 |
| 오프라인 읽기 | Room | 마지막 성공 DTO, cachedAt | 읽기 전용 표시 + **출처·시각 표기**(`LOCAL_CACHE` · `cachedAt`) | 쓰기 비활성. `ApiException.Network` 일 때만 폴백 — 서버가 답한 4xx·5xx 에는 쓰지 않는다 | 기기 캐시 |

### A1 로그인

| 행동 | API/SDK | 요청 | 응답 | 성공 | 실패 |
|---|---|---|---|---|---|
| 이메일 로그인 | `POST /api/auth/login` | email, password | token pair + user | pending route가 있으면 복귀, 없으면 홈 | `LOGIN_FAILED` 인라인 오류, `RATE_LIMITED` 일반 재시도 안내 |
| 카카오 시작 | Kakao Android SDK | 없음 | kakaoAccessToken | 다음 API 호출 | SDK 취소/오류 |
| 카카오 계정 확인 | `POST /api/auth/kakao` | kakaoAccessToken | 서버가 토큰 `app_id=KAKAO_APP_ID` 검증 후 기존 token+user / 신규 `isNewUser=true`+nullable nickname/email profile | 기존 로그인 / 신규 회원가입 | 다른 앱 토큰·무효 토큰은 `INVALID_KAKAO_TOKEN`, 외부 502/504 |
| 게스트 둘러보기 | 로컬 | guest=true | 없음 | 홈 | 없음 |
| 회원가입·비밀번호 찾기 | Navigation | 없음 | 없음 | A2/A3 | 없음 |

### A2 회원가입

| 단계/행동 | 처리/API | 입력 | 응답·다음 상태 | 저장 |
|---|---|---|---|---|
| 약관 동의 | 로컬 | tos, privacy, marketing | 필수 2종 동의 시 다음 활성화. 활성 카피 버전 `TOS/PRIVACY/MARKETING=1.0` | 가입 완료 전 로컬, 가입 시 서버가 버전 포함 3행 저장 |
| 연령 확인 | 로컬 | `ageOver14` | `(필수) 만 14세 이상입니다`. 전체 동의 그룹 밖에 두고 전체 동의로 자동 선택하지 않음 | 가입 요청 최상위 필드로만 전달. 생년월일·별도 연령 컬럼 없음, TOS 1.1 동의 이력이 확인 근거 |
| 정보 입력·검증 | 로컬 + 중복 API | email, password, confirm, nickname | exists/validation | 가입 완료 전 로컬 |
| 이메일·닉네임 중복 | `GET /api/auth/email/exists`, `GET /api/auth/nickname/exists` | query | `Unchecked/Checking/Available/Duplicate/Error`; 입력 변경 시 이전 응답 무효화 | 없음 |
| 코드 발송 | `POST /api/auth/email/send-code` | email | 204, 60초 타이머 | 서버 검증 상태 |
| 코드 확인 | `POST /api/auth/email/verify` | email, code | verified | 서버 검증 상태 |
| 이메일 가입 | `POST /api/auth/signup` | email, password, nickname, **ageOver14**, agreements | token pair + user. 누락 `VALIDATION_FAILED`, false `AGE_REQUIREMENT_NOT_MET`, 인증 후 30분 만료·이력 없음 `CODE_EXPIRED` | PostgreSQL |
| 카카오 신규 가입 | `POST /api/auth/kakao/signup` | kakaoAccessToken, nickname, **ageOver14**, agreements | token pair + user. 이미 가입된 카카오 회원번호는 `409 KAKAO_ACCOUNT_DUPLICATED` | PostgreSQL |
| 가입 완료 | Navigation | 없음 | 홈 | token은 DataStore |

### A3 비밀번호 찾기·웹 재설정

| 행동 | API | 요청 | 성공 | 실패 |
|---|---|---|---|---|
| 재설정 메일 요청 | `POST /api/auth/password/reset-request` | email | 가입 여부와 무관한 202 | 429 cooldown |
| 이메일 링크 열기 | 웹 `GET /reset-password?token=` | reset token | 비밀번호 폼 | 잘못된/만료 token 안내 |
| 새 비밀번호 저장 | `POST /api/auth/password/reset` | token, newPassword | 204, 모든 refresh token 무효화 | `INVALID_RESET_TOKEN`, `INVALID_PASSWORD` |

플로우의 `07-newpw`는 Android 화면이 아니라 이메일 링크로 열린 웹 화면을 시각화한 것으로 해석한다.

---

## 4. 대회 탐색 매핑

### S1 홈

| UI/행동 | API/로컬 | 요청 | 응답에서 쓰는 값 | 원천·저장 | 상태 |
|---|---|---|---|---|---|
| 검색 제출 | S2 이동 | route q | 없음 | LOCAL_STATE | 빈 검색은 캘린더 기본 목록 |
| 달력·지도·코스·관광 아이콘 | Navigation/scroll | 지도=`courses` · 코스=`courses?tab=region` | 없음 | LOCAL_STATE | **지도와 코스는 같은 S8 의 다른 탭**(SPEC §4.4-2) — 지도는 [출발지 주변], 코스는 [지역별]. 탭은 좌표와 달리 route 인자로 넘긴다(감출 값이 아니다 · D-15 대비). 관광은 축제 영역 스크롤 |
| 히어로·대회 카드 | 로컬 선택 | contestId | 카드 DTO | SERVER_DB/Room | 선택→S3, CTA→S4 |
| 마감 임박 | `GET /api/contests/closing-soon` | limit=4 | 카드 필드(`regStatus`, nullable `applyStart/applyEnd` 포함), dDayApply, favorite | SERVER_DB / Room `cached_closing_soon` | 영역별 Loading/Empty/Error. 오프라인이면 24시간 미만 snapshot 으로 그린다(아래 행) |
| 홈 축제 | `GET /api/festivals` | yearMonth(`YYYY-MM`, 기본 KST 이번 달), size(기본 6·1~20) | contentId, name, 기간, region(17개 시도 단축명 또는 `""`), imageUrl, inProgress | KTO_LIVE/5분 TTL cache | 전국 월간, 위치 권한 없음, `addr1` 지역 판별 불가 항목도 `region: ""`으로 유지, 영역별 Loading/Empty/502/504. **P0 제자리 확대만 — 상세 route 없음**(D-05 · #247). 카드를 누르면 그 카드의 사진이 커지고 다시 누르면 접힌다. 화면 이동이 아니므로 D-05 가 막은 "상세 화면과 그 route" 에 걸리지 않는다. 펼쳐도 보여줄 것은 응답의 일곱 필드뿐이다. 추적 메타데이터(fetchedAt/cachedAt)는 응답에 없다(서버 내부 운영 정보) |
| 축제 카드 탭 | 로컬 상태 | 없음 | 없음 | LOCAL_STATE | **제자리 확대**(카드 200→300dp · 사진 116→186dp) + 고른 카드를 가운데로 스크롤. 화면 이동 없음 |
| 오프라인 | Room `cached_closing_soon` | cachedAt | 마지막 성공 마감임박 snapshot — 서버가 준 `rank` 순서 보존, `dDayApply` 는 저장하지 않고 `applyEnd` + 조회 시점 KST 로 재계산 | LOCAL_CACHE + cachedAt 표기 | `cachedAt` 24시간 미만만 유효. 접수 종료(`applyEnd < 오늘`) 항목 제외, 제외 후 0건이면 정상 Empty. cache 없음·24시간 초과는 **Empty 가 아니라** 네트워크 Error + [다시 시도]. 새로고침/쓰기 제한 |

홈 마감 임박은 4건으로 확정했다(D-03). 홈 축제는 사용자 위치를 받지 않는 전국 월간 목록이다(D-04).

### S2 캘린더

| UI/행동 | API/로컬 | 요청 | 응답 | 상태·부분 실패 |
|---|---|---|---|---|
| 목록·검색·필터·선택일 | `GET /api/contests` | q, events[], openOnly, regions[], date?, cursor?, size | active items(`regStatus`, nullable `applyStart/applyEnd` 포함), nextCursor, hasNext | 비활성 제외. 정상 0건은 원인별 Empty, 오류는 Error. Room 목록은 두 날짜로 오늘(KST) 기준 상태 재계산 |
| 검색 입력 | 서버 q 검색 | q | 대회 목록 | Android 300ms debounce(조정 가능한 내부값) |
| 리스트/월간 토글 | 로컬 ViewModel | list/calendar | 없음 | route 변경 없음 |
| 월간 건수 | `GET /api/contests/daily-counts` | year, month + 같은 filter | counts[date,count] | 실패 시 날짜 점만 숨기고 목록 유지 |
| 날짜 선택·해제 | 목록 재조회 | date/null | 대회 목록 | day/month empty 문구 구분 |
| 필터 draft | 로컬 | events/openOnly/regions | 없음 | 취소 시 폐기, 완료 시 두 API 재조회 |
| 다음 페이지 | 같은 목록 API | opaque cursor | 추가 items | 실패 시 기존 items 유지 + 재시도 |
| 찜 | PUT/DELETE `/api/me/favorites/{contestId}` | contestId | 204 | 게스트 로그인 모달, 실패 시 원복 |
| 카드 선택 | S3 이동 | contestId | 없음 | 없음 |

### S3 대회 상세

| UI/행동 | API/로컬 | 응답에서 쓰는 값 | 원천·저장 | 상태 |
|---|---|---|---|---|
| 상세 본문 | `GET /api/contests/{contestId}` | 카드 필드, nullable imageUrl, organizer, officialUrl, nullable lat/lng, dDay, favorite, active | SERVER_DB/Room | 비활성도 404가 아닌 Content: 흐림+"정보 제공 종료". 이미지 null은 placeholder |
| 찜 | S2와 같은 PUT/DELETE | 204 | SERVER_DB | 게스트 modal, 실패 원복 |
| 인근 축제 | `GET /api/contests/{contestId}/festivals` | contentId, name, 기간, distanceKm, imageUrl, address | KTO_LIVE/서버 1일 cache | active일 때만 호출. 본문과 독립 Loading/Empty/502/504. `409 CONTEST_LOCATION_UNAVAILABLE` = **재시도 버튼 없는 별도 오류**("인근 축제를 확인할 수 없어요") — 좌표는 재시도로 생기지 않는다. 추적 메타데이터(fetchedAt/cachedAt)는 응답에 없다(서버 내부 운영 정보) |
| 공식 페이지 | Custom Tabs | officialUrl | 외부 웹 | null이면 버튼 숨김 |
| 공유 | Android 공유 시트(`ACTION_SEND`) | `EXTRA_TEXT`=대회명·`MM.dd 요일 HH:mm`·장소·열리는 공식 주소, `EXTRA_SUBJECT`=대회명 | 저장 없음 | **P0**(#279). `createChooser` 로 매번 고르게 한다. `state.race == null` 이면 비활성. 링크는 `openableWebUrl` 을 통과한 것만 — 화면 [공식 페이지 ↗] 와 같은 기준. 카톡 전용 카드(썸네일·버튼)는 P1/AP-17 |
| 동선 만들기 | S4 이동 | contestId | WizardUiState | 좌표 null 또는 active=false면 CTA 비활성, 좌표 전용 안내 UX는 P1 |

---

## 5. 여행 동선 위저드·결과 매핑

### S4 일정 선택

| 행동 | 처리 | 데이터 | 검증·상태 |
|---|---|---|---|
| 대회 복원 | 공유 WizardViewModel 이 진입 시 `GET /contests/{id}` | contestId, name, contestDate, 좌표 | `WizardUiState.contestPhase` LOADING/LOADED/ERROR/NOT_FOUND (D-33) |
| 패턴 선택 | 순수 도메인 규칙 | PRE/POST/AROUND/DAY/CUSTOM | SPEC §5.2 값 변경 금지 |
| 직접 날짜 선택 | 로컬 | startDate, endDate | 역순 자동 정렬, 대회일 포함·최대 7일 |
| 다음 | 로컬 검증 | 완성된 날짜 범위 | 미완성이면 비활성 |

### S5 종목·취향

| 행동 | 처리 | 데이터 | 검증·상태 |
|---|---|---|---|
| 종목 선택 | 로컬 | K5/K10/HALF/FULL | 기본: 이전 선택→HALF→첫 종목 |
| 취향 선택 | 로컬 | TOUR/FOOD/CAFE/WELLNESS/NATURE/HISTORY | 1개 이상이어야 다음 활성화 |
| 회복강도 | 도메인 규칙 | event→Recovery | API 호출 없음 |

### S6 숙소 선택·동선 생성

| 행동 | API/로컬 | 요청 | 응답·원천 | 상태 |
|---|---|---|---|---|
| 숙소 최초 조회 | `GET /api/pois` | category=LODGING, 대회 lat/lng, radius, size=8 | POI items(`provider=KAKAO|KTO`, `(name,lat,lng)` 조합 유일), 카카오 AD5 우선·KTO 32 폴백 | Loading/Empty/502/504 |
| 숙소 검색 | 같은 API query | 2자 이상 query + 기준 좌표 | 같은 POI item 계약 | Android 500ms debounce, 2자 미만은 호출 안 함 |
| 숙소 선택/해제 | 로컬 | hotel DTO/null | WizardUiState | picked 상태 |
| 동선 생성(서버 단일 주체) | `POST /api/itineraries/generate` | contestId, start/end(대회일 포함·최대 7일), event, themes, hotel? | 지역 없는 기간 `title`, recovery, days[](dayIndex=대회일 상대 오프셋), blocks[] | 비활성은 `409 CONTEST_INACTIVE`. HALF/FULL 회복일은 D+, D+가 없으면 D-day. 200 `days=[]`은 S7 Empty, 네트워크·timeout·4xx/5xx는 Error |

S6의 POI 목록 `key`는 서버가 응답 안에서 유일성을 보장하는 `(name, lat, lng)` 조합을 사용한다.
주소는 원천에 없으면 빈 문자열일 수 있으므로 `key`에 사용하지 않는다.

`generate` 응답은 DB에 저장하지 않는 임시 DTO다. KTO·카카오 POI 실패는 해당 place를 null로 낮추되 전체 동선 생성은 성공시키는 것이 SPEC 계약이다. 앱은 카테고리별 POI를 모아 자체 엔진으로 새 동선을 조립하지 않으며, 서버 응답 표시와 저장 전 USER 블록 편집만 담당한다(SPEC 결정-41).

### S7 결과·편집·저장

| 행동 | API/로컬 | 요청/응답 | 저장 | 상태 |
|---|---|---|---|---|
| 새 결과 표시 | 로컬 | generate DTO | 저장 전 없음 | 날짜 탭, 지도 핀, 회복 배지 |
| POI 후보 | `GET /api/pois` | category, 기준 좌표, query?, size | `provider` 포함 장소 snapshot 후보, `placeId/fetchedAt/cachedAt` 없음 | 시트 Loading/Empty/Error |
| 저장 전 편집 | 로컬 immutable 연산 | USER 블록 추가/교체/삭제/순서 | ResultUiState | RACE 편집 UI 미노출 |
| 새 동선 저장 | `POST /api/itineraries` | 편집된 전체 DTO→201 id 또는 200 replaced | PostgreSQL | 게스트 modal, 성공→보관함 |
| 저장 동선 복원 | `GET /api/itineraries/{id}` | snapshot region/recovery/tree + 최신 contest 메타·active + needsRegeneration | Room cache | RACE는 저장 당시 값 유지. 변경 시 안내(P0) · 재생성 CTA(후속 §5-7~5-10), 403/404/Error |
| 변경 대회 재생성 | `POST /api/itineraries/generate` | 최신 canonical 기준 입력 | 저장 전 임시 DTO | "직접 고친 장소는 사라져요" 확인 뒤 호출, 기존 저장본 유지 |
| 재생성 최종 교체 | `PUT /api/itineraries/{id}` | 새 편집 DTO→200 same id/replaced | PostgreSQL | 저장 성공 시에만 기존 트리 교체, USER 편집 자동 병합 없음 |
| 저장 후 추가 | POST `/itineraries/{id}/days/{dayId}/blocks` | block body→blockId/orderNo | PostgreSQL | 실패 시 기존 UI 유지 |
| 저장 후 수정 | PATCH `.../blocks/{blockId}` | 변경 필드→200 갱신 block 전체 | PostgreSQL | 응답 block으로 해당 항목 교체 |
| 저장 후 삭제 | DELETE `.../blocks/{blockId}` | 204 | PostgreSQL | RACE는 409 |
| 저장 후 순서 | PUT `.../blocks/order` | 전체 USER blockIds→200 해당 일자 전체 blocks | PostgreSQL | 응답 blocks로 일자 상태 교체, set mismatch/409 |
| Empty 조건 수정 | 위저드 복귀 | 기존 WizardUiState | 없음 | 입력 유지 후 조건 수정 |
| Error 재시도 | 같은 generate 재호출 | 기존 요청 | 없음 | 기존 결과·입력 유지 |
| 숙소 주변에서 뛰기 | S8 이동 | `CourseLaunchContext(startLat,startLng,startName,targetKm=min(walk,5))` | LOCAL_STATE | 종목·난이도는 전달하지 않음; SavedStateHandle/그래프 상태, 좌표를 route 문자열에 넣지 않음 |

**저장 후 편집 4행은 서버에 구현돼 있고 앱은 아직 안 쓴다** 🔒확정(#213). S7-R 은 P0 에서 읽기 전용이다.

한때 앱이 로컬 편집 후 `POST /api/itineraries` 로 통째 저장하는 안(A)을 검토했으나 **폐기했다.** 그 API 는 저장된 트리를 덮어쓰는 것이 아니라 **호출할 때마다 현재 canonical 대회로 RACE 블록을 재구성**한다(§5-2). 그래서 A 로 가면 USER 장소 하나만 고쳐도 저장 snapshot 의 대회 정보가 말없이 바뀌고, `needsRegeneration` → 명시적 재생성 → `PUT` 흐름을 우회하며, 대회 날짜가 여행 기간 밖으로 옮겨진 경우에는 단순 편집 저장도 `INVALID_TRAVEL_PERIOD` 로 실패한다. §5-7~5-10 은 **저장 snapshot 의 RACE 를 지키면서 USER 블록만 바꾸려고** 둔 계약이므로 저장 후 편집은 이쪽이 맞다.

---

## 6. 러닝코스 매핑

### S8 출발지 주변·지역별

| 행동 | API/로컬 | 요청 | 응답·원천 | 상태 |
|---|---|---|---|---|
| 출발지 검색 | `GET /api/geocode` | query | name,address,lat,lng / KAKAO_LIVE | `NO_RESULT` |
| 프리셋 | 앱 상수 | 5개 좌표 | start point | API 없음 |
| 거리 슬라이더 | 로컬 | 1~21km, 0.5 단위 | targetKm | 드래그 종료 후 조회 권장 |
| 난이도 표시 | 서버 응답 | 입력 없음 | ROUTE `difficulty`, `gainM`, 고도 스트립 (`difficultyBasis` 없음) | 출발지 주변 카드는 생성 왕복 구간 기준 `EASY\|NORMAL` 배지 옆에 **"이 구간 기준"** 표시. 지역별 목록은 전체 원본 코스 등급 배지만 기존대로 표시하고 보조 문구를 붙이지 않으며 `HARD` 허용. P0 출발지 주변 난이도 칩 없음 |
| 근처 경로·장소 통합 목록 | `GET /api/courses/near` | lat,lng,targetKm,radiusKm=8,size=12 | ROUTE `routeId,dataSource,difficulty,routeKm,durationMin,gainM,elevationProfileM,pathPolyline` + PLACE + degradedSources + attributions | 목표 거리·상승 상한에 맞는 큐레이션 0건이면 거리·상승·차도·회전 상한을 통과한 OSM 최대 1건 생성 후 거리순 통합 |
| OSM 품질 상한 | 서버 내부 | seed 0~15 | 거리 75~125%·상승 <50m/km·실거리 차도 ≤10%·실제 회전 ≤6회/km | 하나라도 초과하면 후보 제외, 상한 완화 금지; AP-25 전 차도 거리 가중 PoC 재검증 |
| 부분 실패 | 같은 near 응답 | 없음 | items 비어 있지 않음 + degradedSources | 호출 실패만 Content+비차단 안내; 품질 상한 통과 후보 0건은 정상 결과 |
| 전체 Empty/Error | 같은 near 응답 | 없음 | 모든 원천 정상+items=[] / 원천 실패+표시 항목 없음 | 전자는 Empty, 후자는 `503 COURSE_SOURCES_UNAVAILABLE` Error |
| 지역 칩 | `GET /api/courses/regions` | 없음 | `count DESC, region ASC`의 region,count | KTO 동기화 실패는 번들/마지막 정상 snapshot으로 200 유지. catalog 자체가 없을 때만 Error |
| 지역 목록 | `GET /api/courses` | region?,page,size | `distanceKm ASC, courseId ASC` 큐레이션 page + nullable syncedAt + 현재 `content[]`의 `attributions[]`(OSM 미포함) | 지역 0건 Empty. 번들 fallback·GPX_ONLY의 syncedAt=null, 출처는 완성 문구를 `" · "`로 연결 |
| 코스 저장 | `POST /api/me/courses` | sourceCourseId?,dataSource,경로·고도 snapshot | 신규 201 / fingerprint 중복 200 기존 id | OSM도 저장 가능, 서버 생성 `name`을 snapshot에 보존하고 routeFingerprint 재계산, 게스트 modal |
| 코스 선택 | 상세 이동 | sealed `CourseDetailKey.Near/Saved` | LOCAL_STATE | near snapshot은 route 문자열에 넣지 않음 |
| 걷기 스팟 선택 | 로컬 상태 | 없음 | LOCAL_STATE | **P0 는 출발지로 삼지 않는다** 🔒확정(#269). 선택 표시·지도 포커스만 바뀌고 재조회하지 않으며, `[저장]` 아래에 `걷기 스팟은 저장할 수 없어요. 지도에서 위치만 확인해 주세요.` 표시. 새 `OriginState` 갈래·출발지 이력·확인창 없음. 출발지 승격은 P1 별도 계약 |
| 지역별 코스 선택 | `GET /api/courses/{courseId}` | courseId | 목록 필드 + pathPolyline + elevationProfileM + attributions | `courseDetail/curated/{courseId}` 로 이동(#280). **courseId 는 catalog 공개 안정키라 route 에 실어도 된다** — near snapshot 과 다른 점이다. 없는 id 는 `404 COURSE_NOT_FOUND` |

### S8-D 코스 상세

| 종류 | 조회 | 필요한 필드 | 행동 |
|---|---|---|---|
| near `ROUTE` 항목 | `courseDetail/near` + 이전 통합 목록 snapshot | routeId,dataSource,pathPolyline,routeKm,durationMin,difficulty,gainM,elevationProfileM,lat,lng | 저장 |
| saved 저장 코스 | `courseDetail/saved/{savedCourseId}` + `GET /api/me/courses/{id}` | 목록 필드 + pathPolyline + attributions[] | 출처 완성 문구를 `" · "`로 연결, 삭제 확인 |
| curated 큐레이션 코스 | `courseDetail/curated/{courseId}` + `GET /api/courses/{courseId}` | 목록 필드 + pathPolyline + elevationProfileM + attributions[] | **읽기 전용** — 삭제도 저장도 없다(#280). `pathPolyline` 은 원본 코스 **전체** points 라 near 의 왕복 구간과 거리·시간·고도가 달라도 정상. `difficulty` 는 전체 등급이라 `HARD` 도 온다 |

`CourseDetailKey`는 `Near(snapshot)`, `Saved(savedCourseId)`, `Curated(courseId)`의 sealed 타입이다. `Ran` 은 두지 않는다(결정-56). NEAR snapshot은 URL에 직렬화하지 않고 `SavedStateHandle` 또는 내비게이션 그래프 범위 상태로 전달한다.

### 러닝 기록 — **제품에 없다** 🔒확정(결정-56)

R1 기록·R2 요약·`ran` 상세와 `/api/runs/**` 를 두지 않는다. 화면·route·API·DB 어디에도
예약을 남기지 않는다 — 자리만 남기면 곧 붙을 것처럼 읽힌다(이슈 #215).

**출발지는 사용자가 직접 고른 장소뿐이다.** 기기 위치·Wi-Fi·기지국·IP 로 현재 위치를
추정하는 경로를 두지 않으므로, `/api/courses/near` 의 `lat/lng` 에는 검색·프리셋·S7 숙소
좌표만 들어간다.

---

## 7. 보관함·계정 관리 매핑

### S10 보관함

| segment/행동 | API | 응답에서 쓰는 값 | 상태 |
|---|---|---|---|
| 프로필 | `GET /api/me` | id,email(`string|null`),nickname,loginProvider,agreements | `email=null`이면 이메일 행·placeholder 없이 숨김. 401/Error |
| 동선 목록 | `GET /api/itineraries` | id,title,region(snapshot),contestName(current),event,recovery,기간,placeCount,active,needsRegeneration,createdAt | 변경 시 "대회 변경" 배지, 비활성도 유지, Loading/Empty/Error/offline |
| 동선 열기 | `GET /api/itineraries/{id}` | snapshot 트리 + current contest + needsRegeneration | S7-R, 변경 안내(P0) · 재생성 CTA(후속 §5-7~5-10) |
| 동선 삭제 | `DELETE /api/itineraries/{id}` | 204 | 확인 modal, 실패 시 유지 |
| 저장 코스 | `GET /api/me/courses` | saved projection | Loading/Empty/Error |
| 코스 삭제 | `DELETE /api/me/courses/{id}` | 204 | 확인 modal |
| 찜 목록 | `GET /api/me/favorites` | 대회 카드 page, favorite=true, active | 비활성 흐림+"정보 제공 종료", Empty/Error |
| 찜 해제 | DELETE `/api/me/favorites/{contestId}` | 204 | 실패 시 카드 유지 |
| 설정 | account 이동 | 없음 | M1 |
| 오프라인 | Room | 마지막 성공 segment | 읽기만, 수정 비활성 |

### M1 내 정보·계정 관리

| 행동 | API/SDK | 요청 | 성공·실패 |
|---|---|---|---|
| 닉네임 변경 | `PATCH /api/me` | nickname | `200` 현재 프로필 전체, duplicated 처리 |
| 마케팅 동의 | `PATCH /api/me/agreements` | marketing | `200` 현재 프로필 전체. 같은 값은 이력 추가 없는 멱등 성공 |
| 마케팅 메일 | P0에서 호출 없음 | 없음 | SES는 가입 인증·비밀번호 재설정 거래성 메일만 발송. 찜 지역으로 관심 지역을 추정하지 않음 | 실제 발송 기능을 열 때 공개 수신거부 계약과 MARKETING 1.1 선행 |
| 비밀번호 변경 | `PUT /api/me/password` | currentPassword,newPassword | EMAIL 수단에만 메뉴 노출, 200 새 token pair로 원자 교체 |
| 가입 로그인 방식 | `GET /api/me` | 없음 | `loginProvider`. EMAIL만 비밀번호 메뉴 노출, P0 연결·해제·전환 없음 |
| 로그아웃 | Authenticator 없는 클라이언트로 `POST /api/auth/logout` | refreshToken, Access 불필요 | 활성·revoked·만료·unknown 모두 204 → 로컬 세션 삭제→로그인 |
| 탈퇴 재인증 | `POST /api/me/reauth` | EMAIL password 또는 KAKAO SDK token | 5분 reauthToken |
| 회원 탈퇴 | 확인 modal→`DELETE /api/me` | `X-Reauth-Token` | 204→모든 세션·사용자 캐시 삭제→로그인 |

---

## 8. 화면 상태·오버레이 커버리지

아래 상태는 별도 API가 아니라 부모 화면의 같은 요청을 다른 결과로 렌더링한다. 기준 캡처 파일은 `docs/mockup-design/shots/png/`에 있다.

### 기본 화면 캡처

| 그룹 | 캡처 | 상세 매핑 |
|---|---|---|
| 인증·온보딩 | 01~07 | 2장 route, 3장 인증 API |
| 대회 탐색 | 10~15 | 4장 홈·캘린더·상세 |
| 계정·저장 코스 상세 | 16~17 | 6장 saved 상세, 7장 계정 관리 |
| 동선 위저드·결과 | 20~25 | 5장 위저드·생성·편집·저장 |
| 러닝코스 | 30~34 | 6장 코스 (기록·요약 캡처는 제외 — 결정-56) |
| 보관함 | 40~42 | 7장 동선·코스·찜 segment |
| 디자인 시스템 | 50 | API·DB 없음, Compose theme/token 기준표 |

### 상태·오버레이 캡처

| 그룹 | 캡처 | 데이터·처리 매핑 |
|---|---|---|
| 게스트 로그인 유도 | 72,73,74,75 | 찜·동선·코스 저장을 API 전에 차단, 로그인 후 원래 route 복귀, 자동 재실행 없음 |
| 삭제·탈퇴 확인 | 100~104 | modal은 로컬, 확인 시에만 DELETE |
| 회원가입·재설정 조작 | 80~83 | 로컬 폼 상태 + 인증/재설정 API 결과 |
| 캘린더 조작 | 84~88 | 같은 contests/daily-counts API에 date/month/filter/q 적용 |
| 위저드 조작 | 89~92 | WizardUiState + POI 조회 + generate 진행 상태 |
| 동선 결과 변형 | 93~95 | 같은 generate DTO의 day 선택·회복일·로컬 편집 |
| 코스 조작 | 96~97 | region API 또는 targetKm 변경 후 near 재조회 |
| 대회 상태 | 98~99 | 서버 파생 regStatus에 따른 CTA·배지 변화 |
| 기본 예외 상태 | 60~71 | 로그인 오류, 축제/숙소 로딩, 동선 Empty, POI 교체, 보관함 Empty, 코스 출발지 미선택/Empty |
| 홈 영역별 상태 | 110~116 | closing-soon과 festivals를 독립 Loading/Empty/Error로 관리, offline은 Room |
| 캘린더 부분 실패 | 120~125 | 목록 실패와 daily-counts 실패를 분리, 검색/day/month Empty 구분 |
| 보관함 부분 실패 | 130~133 | 동선·코스·찜 segment 오류 분리, offline은 읽기 전용 |
| 서울 코스 기본 상태 | 140 교체 필요 | 목표 거리·상승 상한 적격 큐레이션 0건 → 품질 상한 OSM 생성 경로+Kakao 장소가 정상 Content. GraphHopper 실패 변형은 장소 Content+비차단 안내 |

### 확인된 플로우 연결

- 보관함 동선→S7-R, saved→코스 상세, 찜→S3가 연결돼 있다.
- 코스 저장→보관함 saved가 연결돼 있다.
- 보관함 설정→계정 관리, 계정 관리→비밀번호 변경·로그아웃·탈퇴가 연결돼 있다.
- 홈·캘린더·보관함은 로딩·빈·오류·부분 실패 상태를 가진다.
- 삭제·탈퇴에는 확인 modal이 있다.
- 게스트 로그인 후에는 원래 화면만 복원하고 사용자가 저장·찜을 다시 누른다.

---

## 9. 한국관광공사 실시간 API 연결

여기서 실시간은 스트리밍이 아니라 **운영 중 백엔드가 KTO REST API를 호출해 최신 응답을 가져오는 것**을 뜻한다. 쿼터 보호용 TTL 캐시는 허용하지만 운영 장애를 SAMPLE/SYNTH 데이터로 숨기지 않는다.

| 화면 기능 | 우리 API | KTO 기능 | 저장 정책 | 화면 증빙 |
|---|---|---|---|---|
| 홈 축제 추천 | `GET /api/festivals` | 축제 조회 | 영구 저장 없음, 단기 캐시 | 축제 카드 + 한국관광공사 출처 |
| 대회 상세 인근 축제 | `GET /api/contests/{id}/festivals` | `searchFestival2` 후 날짜 겹침·40km 필터 | 대회별 1일 캐시 | Loading/Empty/Error 포함 독립 영역 |
| 동선 관광·역사 POI | `GET /api/pois?category=TOUR/HISTORY` | `locationBasedList2` | 5분 캐시 | 결과·POI 추가/교체 시트 |
| 동선 웰니스 POI | `GET /api/pois?category=WELLNESS` | WellnessTursmService | 5분 캐시 | 위저드 취향→동선 결과 |
| 러닝코스 큐레이션 | `GET /api/courses/**` | Durunubi `courseList` | 버전 번들 즉시 로드→준비 후 1회+완료 기준 24시간 동기화, 전체 성공 snapshot만 원자 교체 | 실패 시 261 GPX 번들/마지막 정상본 유지. 지역 목록·near 우선 경로·한국관광공사 출처. OSM fallback은 KTO 증빙을 대체하지 않음 |

숙소는 카카오 AD5가 1순위이고 KTO 32는 폴백이므로 숙소 화면 하나만으로 KTO 사용을 증명하지 않는다. 공모전 실시간 API 증빙의 주 기능은 **S3 인근 축제**, 보조 기능은 홈 축제와 TOUR/HISTORY/WELLNESS POI로 삼는다.

### 구현 수용 기준

- 앱은 우리 백엔드만 호출하고 KTO 키는 서버 환경변수에 둔다.
- 캐시 미스 시 실제 KTO API 호출이 서버 로그·Swagger 테스트로 확인돼야 한다.
- 정상 0건은 Empty, KTO 오류·timeout은 Error로 구분한다.
- 운영에서 외부 실패를 샘플 데이터로 대체하지 않는다.
- 화면에 한국관광공사 출처와 이미지 저작권 기준을 표시한다.
- API 응답에서 데이터 원천과 갱신 시각을 추적할 계약을 11장에 보완한다.

---

## 10. 결정 목록

### 확정된 항목

| 기존 ID | 결론 |
|---|---|
| F-01~03 | 보관함의 동선·saved·찜 카드에서 각 상세로 이동 |
| F-04 | 코스 상세 저장 후 보관함 saved에서 확인 |
| F-05 | 보관함 설정 아이콘에서 계정 관리 진입 |
| D-27 / F-06 | 로그인 후 원래 화면 복귀, 저장·찜 자동 실행 없음 |
| F-07 | 홈·캘린더·보관함의 Loading/Empty/Error와 부분 실패 캡처 추가 |
| D-05 | 홈 축제 카드는 P0 표시 전용, 플로우에 상세 이동 없음 |
| D-06 | Android S3 route는 `raceDetail/{raceId}` |
| D-07 | 캘린더 month는 route가 아니라 ViewModel 상태 |
| D-03 | 홈 마감 임박은 4건, API `limit=4` |
| D-04 | 홈 축제는 위치 권한·좌표 없이 전국 월간 목록 |
| D-08 | 캘린더 q 검색 300ms debounce(앱 내부 조정값) |
| D-09 | 현재 153건 좌표 누락 0. 재수집 0건 검증, DTO nullable 방어, null이면 CTA 비활성·서버 409 |
| D-10 | CUSTOM은 대회일을 포함한 최대 7일, 역순 자동 정렬 |
| D-11 | 종목 기본값은 이전 선택→HALF→첫 종목 |
| D-12 | 숙소 검색은 2자 이상·500ms debounce 후 서버 query 호출 |
| D-13(개정) | 정상 0건은 S7 Empty, 네트워크·timeout·4xx/5xx는 S7 Error. 구 "모든 실패=Empty" 결정 폐기 |
| D-14 | block PATCH는 갱신 block 전체, order PUT은 해당 일자 blocks 전체를 `200`으로 반환 |
| D-15(개정) | S7→S8은 출발지·`min(RECOVERY.walk,5)` 목표거리만 `CourseLaunchContext`로 전달. 종목·난이도는 전달하지 않고 좌표를 route 문자열에 넣지 않음 |
| D-16 | Empty는 입력 유지 후 위저드 복귀·조건 수정, Error는 같은 요청 재시도 |
| D-18(08-23 개정) | `pathPolyline`은 고도 없는 2D Google Encoded Polyline precision 5(E5). 서버가 연속 중복점을 제거하고 소수점 5자리 `lat,lng`를 `;`로 연결한 UTF-8 canonical geometry로 `v1:`+SHA-256 `routeFingerprint`를 계산해 사용자별 멱등 저장. 진행 반대는 별개, 신규 201·중복 200 기존 id |
| D-20(개정) | `courseDetail/{type}/{id}` 폐기. sealed CourseDetailKey + near/saved 분리 route. `ran` 은 결정-56 으로 없앴다 |
| D-22 | 내 정보·계정 관리는 보관함 설정에서 여는 별도 화면 |
| D-23 | 탈퇴 전 EMAIL/KAKAO 재인증으로 5분 token 발급, DELETE 성공 후 모든 세션·캐시 삭제 |
| D-24 | 대회 imageUrl은 P0 nullable, null이면 placeholder |
| ~~D-25~~ | ~~GPS 기록·ran은 P1~~ — **SPEC 결정-56 으로 대체**. 러닝 기록을 제품에서 뺐고 보관함은 saved 한 종류다 |
| D-26 | 별도 splash route 없이 시스템 Splash + core-splashscreen + Startup Gate |
| D-28 | EMAIL 수단에만 Android 비밀번호 변경 메뉴 노출, 변경 성공 시 전 refresh revoke 후 현재 기기 token pair 재발급 |
| D-29(개정) | USER:LOGIN_IDENTITY는 1:1. 가입 시 EMAIL/KAKAO 중 하나만 선택하고 P0 연결·추가·해제·전환 API를 두지 않음. `GET /me.email`은 항상 포함하는 `string|null`이며 KAKAO 이메일 미제공 시 null, 별도 이메일 입력·인증 없음 |
| D-30 / SPEC 결정-50 | 이메일·닉네임 중복 확인은 모두 P0에서 호출한다. `Checking` 동안 인증 메일 발송을 막고 `Available`에서 허용하며, `Duplicate`만 확정 차단한다. `Unchecked`·네트워크/`RATE_LIMITED` `Error`는 발송·가입의 서버 유니크 방어를 믿고 진행을 막지 않는다. 입력 변경 시 결과를 즉시 무효화하고 늦은 응답은 버린다 |
| D-31 / SPEC 결정-51 | Access 30분·Refresh 14일, 기기별 refresh family 회전·재사용 탐지를 적용한다. 로그아웃은 Access 없이 Authenticator가 붙지 않는 클라이언트로 호출하고 non-blank refresh 결과와 무관하게 204를 받으면 로컬 세션을 삭제한다 |
| D-33 | 위저드 대회 조회 실패는 `WizardUiState.contestPhase`(LOADING/LOADED/ERROR/NOT_FOUND)로 담는다. S3 `RaceDetailUiState.Phase`와 같은 기준이며 ERROR만 [다시 시도], `404`와 canonical id 없는 대회는 NOT_FOUND로 [뒤로]. `race == null`을 로딩으로 읽던 규칙 폐기(이슈 #140) |
| D-32 / SPEC 결정-52 | 현재 가입 화면 약관 카피와 서버 활성 버전은 `TOS/PRIVACY/MARKETING=1.0`으로 맞춘다. 앱은 boolean만 보내고 버전 변경은 앱·서버가 같은 활성화 PR에서 함께 확인한다. 다음 문안 TOS 1.1·PRIVACY 1.2는 #227·#228 구현 전까지 비활성이다(이슈 #111) |
| D-34 / SPEC 결정-58 | 이메일·카카오 가입은 전체 동의 밖의 별도 `ageOver14` 필수 확인을 사용한다. 요청 최상위 필드이며 누락은 `VALIDATION_FAILED`, false는 `AGE_REQUIREMENT_NOT_MET`("만 14세 이상만 가입할 수 있습니다.")이다. 생년월일·별도 연령 저장은 없다 |
| D-35 / SPEC 결정-57 | 이메일 가입은 활성 미인증 행만 `EMAIL_NOT_VERIFIED`, 인증 후 30분 만료나 인증 이력 없음은 `CODE_EXPIRED`다. 앱은 `CODE_EXPIRED`를 일반 가입 실패로 처리하지 않고 `mustResend=true`로 바꿔 A2에서 재발송·재인증을 안내한다(#228). Refresh는 14일이며 회전 토큰 재사용 시 같은 family 전체를 폐기하고 재로그인한다 |
| D-36 / SPEC 결정-59 | P0는 마케팅 메일을 보내지 않고 선택 동의 상태만 유지한다. 발송 범위를 열 때 수신거부 API·토큰·페이지와 MARKETING 1.1을 계약부터 추가한다 |
| DB-02 / SPEC 결정-44 | 저장 코스 attribution은 서버 생성 완성 문구 배열을 `JSONB NOT NULL DEFAULT '[]'` snapshot으로 보존. 상세에만 반환하고 목록·fingerprint에서 제외하며 문구 변경을 소급하지 않음. `GET /api/courses`도 실제 응답 코스 원천의 `attributions[]` 반환 |
| DB-01 / SPEC 결정-33(08-23 재개정) | 저장 코스 polyline은 2D Google Encoded Polyline precision 5(E5). 고정 `lat,lng` canonical geometry로 fingerprint를 계산하고, `elevationProfileM`은 최대 100개 정수·미보유 `[]`·PostgreSQL `JSONB NOT NULL DEFAULT '[]'`로 저장 |
| DB-04 / SPEC 결정-45 | 저장 동선은 region·recovery·전체 트리와 RACE를 snapshot으로 보존. contestName·현재 대회 메타는 조회 시 파생하고 일정·시간·장소·지역·좌표 변경만 needsRegeneration=true. 재생성 최종 저장은 `PUT /itineraries/{id}`로 같은 id 교체 |
| DB-05 / SPEC 결정-46 | 승인된 full snapshot에서 source 2회 연속 누락 시 비활성. 실패·부분 snapshot은 미반영, 재등장은 즉시 복구, canonical은 활성 source가 없을 때만 비활성. 공개 탐색 제외·참조 상세 유지 |
| SPEC 결정-48 | 다중 원천은 정상 수용. 최대 source 겹침 동률 또는 기존 canonical 하나를 둘 이상의 새 canonical이 승계하려는 충돌이면 Importer가 snapshot 전체를 거부하고 기존 참조·누락 상태·적용 이력을 유지 |
| SPEC 결정-41 | 새 동선은 백엔드 `POST /itineraries/generate`가 단독 생성. 앱 엔진은 운영 화면에 연결하지 않음 |
| SPEC 결정-42(09-01 배포 개정) | OSM/GraphHopper 도시 경로 생성을 P0에 포함. import는 저장소 고정 builder가 EC2 밖에서 수행하고 EC2는 검증된 graph artifact를 쓰는 server-only 별도 프로세스로 운영한다. 적격 큐레이션 0건이면 fallback 1건을 생성하며 난이도 칩·EventType 기본값은 제거하고 HARD·거리·실거리 차도·실제 회전 상한을 서버가 강제한다 |
| SPEC 결정-53 | 비활성 대회 생성은 `409 CONTEST_INACTIVE`. 생성 `title`은 지역 없는 기간, `dayIndex`는 대회일 상대 오프셋. HALF/FULL 회복일은 D+이고 D+가 없으면 D-day. 생성 엔진 `sources`는 내부 추적값 |
| SPEC 결정-55 | 이메일 로그인은 IP별 모든 요청 30회/고정 1분과 정규화 이메일별 실패 5회/고정 1분을 함께 제한한다. 초과는 `429 RATE_LIMITED`, 성공은 이메일 창만 초기화한다. 존재하지 않는 이메일도 dummy BCrypt 비교 후 동일한 `401 LOGIN_FAILED`를 반환한다 |
| SPEC 결정-56 | 자동 위치 추정을 제품에서 전부 없앤다. 위치 권한·`FusedLocationProvider`·`play-services-location` 을 두지 않고 Wi-Fi AP·기지국 Cell-ID·BLE·IP GeoIP 로 위치를 추정하지 않는다. 출발지는 검색·프리셋·S7 숙소 좌표뿐이며 `/api/courses/near` 의 `lat/lng` 에 기기 유래 좌표를 넣는 경로를 두지 않는다. R1·R2·`ran`·`/api/runs/**` 는 구현하지 않는다. 서버 요청 IP 기반 횟수 제한은 위치 추정이 아니므로 유지하되 지역 변환·저장·추천 사용은 하지 않는다 (D-25 대체 · 이슈 #215) |

### 미정 계약 — **없다**

~~D-21(saved/ran 통합 정렬·페이징)~~ 은 SPEC 결정-56 으로 **사라졌다.** `ran` 이 없으므로
통합할 대상 자체가 없고, 보관함 코스 목록은 `GET /api/me/courses` 하나다.

P0 화면·기능과 물리 DB 계약은 모두 닫혔다. 저장 코스 DB-01은 결정-33의 08-23 재개정으로 확정됐다.

---

## 11. API 명세 보완 목록

### 이번 결정으로 확정된 계약

- `GET /api/contests` 카드의 nullable `applyStart/applyEnd`와 오늘(KST) 기준 `regStatus` 재계산·원본 상태 fallback
- `GET /api/contests/{id}`의 nullable `imageUrl/lat/lng`와 좌표 없음 처리
- `POST /api/itineraries/generate`의 최대 7일·대회일 포함·좌표 없음·Empty/Error 구분
- 저장 동선의 snapshot/current 분리, `needsRegeneration`, 재생성 결과 `PUT /api/itineraries/{id}` 교체
- 대회 source 2회 연속 누락 비활성화와 공개 탐색 제외·참조 상세 유지
- block PATCH와 order PUT의 `200` 갱신 응답
- `POST /api/me/courses`의 fingerprint 멱등 저장
- `GET /api/courses/near`의 목표거리 입력·HARD 제외 큐레이션 우선/품질 상한 OSM fallback·구간 기준 표시 난이도·서버 생성 이름·정상 0건/부분 실패·동적 출처 계약
- `GET /api/courses`의 Page 최상위 `attributions[]`와 저장 코스 상세의 attribution snapshot 계약
- `GET /api/courses`·`/regions`의 안정 정렬·nullable `syncedAt`·번들/마지막 정상 snapshot fail-open 계약
- `PUT /api/me/password`의 token pair 재발급
- `POST /api/me/reauth`와 `DELETE /api/me`의 탈퇴 재인증
- `GET /api/me`의 단일 `loginProvider`와 로그인 수단 연결·해제 API 제거
- `GET /api/pois`의 항목별 `provider(KAKAO|KTO)`, `placeId`·추적 timestamp 제외, 8→20km 확대·원천 보충·부분 실패·5분 캐시 계약

### 남은 springdoc 상세화 항목

아래는 구현 전 `docs/files/런닝구_API_명세서.md` 또는 첫 계약 PR에서 DTO 예시를 상세화해야 한다. 이 매핑표만 보고 임의 구현하지 않는다.

| API | 보완할 계약 |
|---|---|

현재 P0 API의 springdoc 상세화 미정 항목은 없다. `/api/runs/**` 는 **계약을 두지 않는다**
(SPEC 결정-56) — 예약만 남겨도 다음 사람이 곧 붙는 것으로 읽는다.

---

## 12. 구현 순서와 완료 조건

### 세로 기능 순서

1. S1 홈 → S2 캘린더 → S3 상세
2. S3 상세 → S4~S7 위저드·결과·저장
3. S8 코스 → S8-D 상세 → 코스 저장
4. S10 보관함 → M1 계정 관리

### 화면 단위 완료 조건

- route와 필수 전달값이 정의돼 있다.
- API 또는 로컬 처리 주체가 정의돼 있다.
- 요청값과 화면이 소비하는 응답 필드가 정의돼 있다.
- PostgreSQL 영구 저장, 외부 API 캐시, 기기 임시 상태가 구분돼 있다.
- 로그인·게스트 정책이 정의돼 있다.
- Loading/Content/Empty/Error와 부분 실패가 정의돼 있다.
- KTO 사용 화면은 원천·캐시·출처 표시·오류 처리가 정의돼 있다.
- API 계약 미정값은 10·11장에 등록돼 있다.

Android는 springdoc과 동일한 JSON fixture로 FakeRepository를 만들고, 백엔드 준비 후 Retrofit 구현으로 교체한다.
