# 런닝구 백엔드 API 명세서 v2.11

> **기준 문서**: SPEC v4(SSOT) + 화면별 데이터정리 v5 + ERD v4.3·수정 DFD
> **스택**: Spring Boot 3.x (Java 21) · PostgreSQL(결정-3) · Spring Security + JWT · QueryDSL · Spring Mail · Flyway · Spring Cache + Caffeine · 내부 GraphHopper 프로세스(결정-42)
> **테스트**: JUnit 5 · Testcontainers(PostgreSQL 통합 테스트)
> **지위**: springdoc-openapi(결정-18) 구현의 **시드 문서** — 컨트롤러 확정 후 Swagger UI가 최종 계약이 된다. SPEC §9.3 초안을 대체·상세화한 판.
> **핵심 결정**: 서버 중심 온라인 SSOT · 제한적 오프라인 폴백 · canonical 대회 · 사용자당 EMAIL/KAKAO 단일 가입 수단 · 시스템 관리 RACE 블록 · 큐레이션 API+GPX 결합 · **OSM/GraphHopper 도시 경로 생성(P0)**

---

## 0. 공통 규약

### 0-1. 기본

| 항목 | 규약 |
|---|---|
| Base URL | `/api` (호스트는 빌드 타입별 `BASE_URL` — SPEC §9.4) |
| 포맷 | 요청·응답 `application/json; charset=UTF-8` |
| 날짜/시간 | 비즈니스 날짜 `YYYY-MM-DD`와 오늘·D-day·접수상태 판정은 `Asia/Seoul`. timestamp는 PostgreSQL `timestamptz` UTC 저장, JSON ISO-8601 `Z` |
| 좌표 | `lat`/`lng` Double(WGS84, DECIMAL(10,7)) — 외부 API의 x/y·mapx/mapy 변환은 서버 리모트 매퍼에서만 🔒(NFR-8) |
| 명명 | JSON 필드 camelCase / DB snake_case |
| 대회 원천 ID | JSON·데이터 계약 `externalId` / DB `external_id`, `(sourceType, externalId)` UNIQUE |

### 0-2. 인증 · 게스트 🔒(결정-4)

- 인증 방식: `Authorization: Bearer {accessToken}`. Access JWT와 Refresh JWT는 **HS256**으로 서명하고 서명 키는 서버 환경변수로만 관리한다. **액세스 30분 · 리프레시 14일**이며, 리프레시는 회전(rotate) + 서버 저장(SHA-256 해시) — 비밀번호 변경·재설정·탈퇴 시 전체 무효화한다(NFR-11). 🔒
- 게스트: 공개 콘텐츠 탐색과 무상태 동선 생성은 허용한다. 프로필·마이·찜·동선/코스/기록 저장은 인증 필요. 인증 API `401`은 "로그인이 필요해요" 모달로 매핑한다.

| 공개 (게스트 허용) | 인증 필요 |
|---|---|
| 인증(`/auth/**`) · 대회(`/contests/**`) · 축제(`/festivals`) · POI(`/pois`, `/geocode`) · 러닝코스(`/courses/**`) · **동선 생성**(`/itineraries/generate` — 무상태) | 회원(`/me`) · 동선 저장/조회/편집(`/itineraries/**`) · 저장 코스(`/me/courses/**`) · 러닝 기록(`/runs/**`, P1) · 찜(`/me/favorites/**`) |

### 0-3. 에러 응답 — RFC 9457 Problem Details 확장

```json
{
  "type": "/errors/system-block-immutable",
  "title": "변경할 수 없는 일정입니다.",
  "status": 409,
  "detail": "대회 일정은 사용자가 변경할 수 없습니다.",
  "instance": "/api/itineraries/3/days/9/blocks/21",
  "code": "SYSTEM_BLOCK_IMMUTABLE",
  "traceId": "7f3d8c..."
}
```

Content-Type은 `application/problem+json`. Bean Validation 오류는 `errors[]`에 `{field, reason}`을 추가한다.

| HTTP | 의미 |
|---|---|
| 400 | 요청 값 검증 실패 |
| 401 | 미인증·토큰 만료 (게스트의 쓰기 시도 포함) |
| 403 | 권한 없음 (남의 리소스 접근 — 소유자 검증) 🔧 |
| 404 | 리소스 없음 |
| 409 | 유니크 충돌·현재 계정 방식과 맞지 않는 작업·시스템 블록 변경 시도 |
| 429 | 쿨다운·시도 횟수 초과 (인증 메일 60초, 코드 5회) |
| 500 | 처리되지 않은 서버 내부 오류. 세부 예외 대신 추적 가능한 `traceId`만 응답 |
| 502 | 외부 API가 오류/비정상 응답 반환 |
| 503 | 내부 라우팅 원천 장애로 표시할 코스·장소가 없음 |
| 504 | 외부 API 응답 시간 초과 |

클라이언트는 `Loading / Content / Empty / Error`를 구분한다. 정상 빈 결과는 `200`의 빈 배열이며, 502/504를 Empty나 Loading으로 강등하지 않는다. 전체 에러 코드 → **부록 D**.

### 0-4. 페이징 🔒(정리본 확정 7)

- **대회 목록만 불투명 커서 페이징** — 서버 내부 `(contestDate, id)`를 URL-safe Base64 `nextCursor`로 인코딩하고 클라이언트는 해석하지 않는다. 기본 20·최대 50.
- **개인 목록은 Spring Pageable** — `?page=0&size=20`, `createdAt DESC, id DESC`, 최대 50. 응답은 `content[] + page{number, size, totalElements, hasNext}`.
- 집계·Enum·지역 목록은 페이징하지 않는다.

### 0-5. 외부 API 프록시 방어 정책 🔒(정리본 공통 방어 + SPEC NFR-3~5)

| 정책 | 값 |
|---|---|
| 타임아웃 | 연결 1초 · 응답 2.5초 🔧 → 초과 시 504 |
| 카카오 429 | 1회 재시도 후 실패 처리 (NFR-5) |
| KTO 오류 방어 | `resultCode≠0000` · **JSON 요청에도 XML로 오는 포털 오류**를 컨버터 예외로 구분 처리·로깅 (NFR-4) |
| 캐시 | 대회별 인근 축제 1일 · 기타 프록시 5분 · 두루누비 메타 24시간(주최측 허용 확인 후) |
| 캐시 구현 | 단일 서버 MVP는 Spring Cache + Caffeine 인메모리 캐시. Redis는 MVP에서 사용하지 않음 🔒 |
| 예외 | 동선 생성은 POI 조회 실패 시 해당 블록을 `place=null`로 강등하고 생성은 성공한다(NFR-3). `/courses/near`는 일부 원천 실패에도 표시 항목이 있으면 `200`+`degradedSources`, 원천 실패가 있고 표시 항목도 없으면 `503 COURSE_SOURCES_UNAVAILABLE`를 반환한다 |

### 0-6. GraphHopper 내부 라우팅 방어 정책 🔒(결정-42)

| 정책 | 값 |
|---|---|
| 접근 | Spring Boot만 내부 HTTP로 호출, GraphHopper 포트 외부 비공개 |
| 타임아웃 | 경로 후보 16건 전체 3초 🔧, 초과 시 `OSM` degraded 처리 |
| 기동 | readiness 완료 전 OSM 생성 비활성, Spring Boot 기동과 분리 |
| 그래프 | 고정 GraphHopper+대한민국 PBF로 배포 단계에서 생성, 그래프 약 514MB+SRTM 캐시를 영속 볼륨에서 재사용 |
| 품질 | 거리 75~125%·상승 <50m/km·실거리 가중 차도 ≤10%·실제 방향 전환 ≤6회/km를 모두 만족한 후보만 허용, 상한 완화 금지 |
| 장애 | 다른 경로·장소가 있으면 `200`+`degradedSources`, 표시 항목도 없으면 `503 COURSE_SOURCES_UNAVAILABLE` |
| 저장 | OSM 원천 그래프는 DB에 복제하지 않고 생성 경로 snapshot만 사용자 저장 시 PostgreSQL에 보관 |

---

## 1. 인증 API `/api/auth`

| # | 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|---|
| 1-1 | GET | `/auth/email/exists` | 이메일 중복 확인 | 공개 |
| 1-2 | GET | `/auth/nickname/exists` | 닉네임 중복 확인 | 공개 |
| 1-3 | POST | `/auth/email/send-code` | 가입 인증 코드 발송 | 공개 |
| 1-4 | POST | `/auth/email/verify` | 인증 코드 검증 | 공개 |
| 1-5 | POST | `/auth/signup` | 회원가입 (이메일) | 공개 |
| 1-6 | POST | `/auth/login` | 로그인 (이메일) | 공개 |
| 1-7 | POST | `/auth/kakao` | 카카오 로그인 (SDK 토큰 검증) | 공개 |
| 1-8 | POST | `/auth/kakao/signup` | 카카오 신규 가입 (동의 포함) | 공개 |
| 1-9 | POST | `/auth/refresh` | 액세스 토큰 재발급 | 공개(리프레시 필요) |
| 1-10 | POST | `/auth/logout` | 로그아웃 (리프레시 무효화) | 인증 |
| 1-11 | POST | `/auth/password/reset-request` | 재설정 링크 메일 발송 | 공개 |
| 1-12 | POST | `/auth/password/reset` | 새 비밀번호 설정 | 공개(토큰 필요) |

> 재설정 링크가 여는 **웹 페이지**(`GET /reset-password?token=`)는 REST API가 아니라 백엔드가 서빙하는 Spring MVC 뷰다 🔒(결정-6 MVP안) — 페이지 내부에서 1-12를 호출한다.

### 1-1 · 1-2 중복 확인

`GET /api/auth/email/exists?email=` · `GET /api/auth/nickname/exists?nickname=` → `200 {"exists": true}`
이메일은 앞뒤 Unicode 공백 제거 후 전체 소문자화하며 최대 320자, `^[^\s@]+@[^\s@]+\.[A-Za-z]{2,}$` 형식이다. Gmail 점·`+tag` 같은 공급자별 변환은 하지 않는다. KAKAO의 `email_snapshot`은 EMAIL 중복으로 세지 않는다.
닉네임은 앞뒤 공백 제거 후 Unicode 코드포인트 2~12자이고 내부 공백·Unicode를 허용한다. 표시 문자열은 보존하되 중복 키는 ASCII 영문 대소문자를 무시하며 NFC/NFKC 변환은 하지 않는다. (가입 2단계 인라인 검증용 — 이슈 #97)

두 API는 공개 이메일 존재 조회라는 의도된 비대칭을 가진다. IP 합산 30회/분, 정규화 이메일·닉네임별 5회/분을 단일 서버 Caffeine에서 제한하고 초과 시 `429 RATE_LIMITED`를 반환한다. P0 응답에는 `Retry-After` 헤더나 남은 시간을 넣지 않으며 앱은 카운트다운 없이 일반 재시도 안내를 표시한다. 조회 실패·제한은 앱의 `DuplicateCheck.Error`이며 가입 진행을 막지 않고, 발송·가입의 유니크 오류가 최종 방어다.

IP는 Spring 전달 헤더 처리 후 `request.getRemoteAddr()`를 사용한다. 로컬·직접 연결은 `FORWARD_HEADERS_STRATEGY=none`, 신뢰 가능한 프록시 뒤 운영은 `framework`로 설정한다. 프록시는 외부 전달 헤더를 제거·재설정하고 원본 서버 직접 접근을 차단해야 한다. 프록시 배포에서 `none`이면 모든 사용자가 프록시 IP 버킷을 공유하고, 신뢰 경계 없이 `framework`를 켜면 헤더 위조로 제한을 우회할 수 있다.

### 1-3 `POST /auth/email/send-code`

```json
{ "email": "runner@test.com" }
```
`204` — 6자리 코드 메일 발송. **유효 10분 · 재발송 쿨다운 60초 · 5번째 오입력부터 재발송 요구** 🔒(NFR-10).
코드는 BCrypt strength 10 해시만 저장한다. 재발송은 이전 코드·검증 상태를 무효화하고 실패 횟수를 0으로 초기화한다. 인증 메일은 UTF-8 일반 텍스트이며 코드를 공백·하이픈 없이 표시한다.
오류: `409 EMAIL_DUPLICATED`(이미 가입) · `429 SEND_COOLDOWN` · `502 EXTERNAL_API_ERROR`(SMTP 실패, 코드·쿨다운 미반영)

SMTP는 공급자 독립 Spring Mail로 연결하고 인증·STARTTLS를 필수로 하며 connect/read/write timeout은 각 5초, 애플리케이션 재시도는 하지 않는다. `MAIL_ENABLED` 기본값은 `false`이고 운영은 `true`로 설정하며 `SMTP_HOST`, `SMTP_PORT`(기본 587), `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM_ADDRESS`, `SMTP_FROM_NAME`(기본 런닝구)을 환경변수로 받는다. outbox를 두지 않는 P0 계약상 SMTP 성공 뒤 DB commit이 실패하면 먼저 도착한 코드는 검증할 수 없고 사용자가 즉시 재발송해야 하는 잔여 위험을 허용한다.

### 1-4 `POST /auth/email/verify`

```json
{ "email": "runner@test.com", "code": "483920" }
```
`200 {"verified": true}` — 서버가 해당 이메일을 '인증 완료(30분 내 가입 유효 🔧)'로 마킹.
6자리 숫자 형식 자체가 아니면 `400 VALIDATION_FAILED`이며 횟수를 올리지 않는다. 발송 이력이 없거나 인증 완료 후 30분이 지나면 `400 CODE_EXPIRED`다. 형식은 맞지만 코드가 다르면 1~4번째는 `400 INVALID_CODE`, 5번째부터는 시도 횟수를 5로 보존하고 `429 TOO_MANY_ATTEMPTS`다. 성공한 같은 코드는 인증 완료 후 30분 동안 멱등 `200`이고, 성공 후 다른 코드는 인증 상태·횟수를 바꾸지 않고 `INVALID_CODE`다. 재발송 후 이전 코드는 `INVALID_CODE`다.

### 1-5 `POST /auth/signup`

```json
{
  "email": "runner@test.com",
  "password": "run4life1",
  "nickname": "김러너",
  "agreements": { "tos": true, "privacy": true, "marketing": false }
}
```
`201` — 응답은 1-6 로그인과 동일(가입 완료 → "시작하기 → 홈" 목업 플로우에 맞춰 **자동 로그인** 🔧).
검증: 이메일 인증 완료 상태 필수(`403 EMAIL_NOT_VERIFIED`) · 비밀번호 8자 이상 영문+숫자 🔒 · 필수 동의 2종(`400 AGREEMENT_REQUIRED`) · `409 EMAIL_DUPLICATED / NICKNAME_DUPLICATED`. 비밀번호는 BCrypt 단방향 해시(NFR-9). 서버가 활성 약관 버전을 결정하고 항목별 `{type, version, agreed, changedAt}` 이력을 append-only로 저장한다(NFR-12).

### 1-6 `POST /auth/login`

```json
{ "email": "runner@test.com", "password": "run4life1" }
```
```json
{
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "eyJhbGciOi...",
  "user": {
    "id": 1,
    "email": "runner@test.com",
    "nickname": "김러너",
    "loginProvider": "EMAIL"
  }
}
```
오류: `401 LOGIN_FAILED` — 메시지 "이메일 또는 비밀번호를 확인해 주세요" 🔒(§4.1, 계정 존재 여부 비노출)

### 1-7 `POST /auth/kakao` 🔒(U1 P0 — 목업 "카카오로 시작하기")

```json
{ "kakaoAccessToken": "카카오 SDK가 발급한 액세스 토큰" }
```
서버가 카카오 API(`/v2/user/me`)로 토큰 검증 → `(provider=KAKAO, providerSubject=카카오 회원번호)`로 `LOGIN_IDENTITY`를 조회한다.
- 기존 KAKAO 가입 계정: `200` — 1-6과 동일 응답
- 미가입: `200 {"isNewUser": true, "kakaoProfile": {"nickname": "카카오프로필명", "email": null}}` → 앱은 약관 동의 화면으로 → 1-8 호출

카카오 이메일은 동의 항목에 따라 없을 수 있는 **nullable 프로필 스냅샷**일 뿐 로그인 식별자가 아니다. 동일 이메일의 EMAIL 계정이 있어도 자동 병합하지 않는다. 한 USER는 가입 시 선택한 EMAIL 또는 KAKAO 수단 하나만 가지며 P0에서는 연결·추가·전환을 지원하지 않는다(결정-22 개정).

오류: `401 INVALID_KAKAO_TOKEN`

### 1-8 `POST /auth/kakao/signup`

```json
{
  "kakaoAccessToken": "...",
  "nickname": "김러너",
  "agreements": { "tos": true, "privacy": true, "marketing": false }
}
```
`201` — 1-6과 동일 응답. **이메일 인증 생략** 🔒(§4.2 카카오 가입). KAKAO 로그인 수단에는 비밀번호를 저장하지 않는다.
구현은 `USER`와 `LOGIN_IDENTITY(provider=KAKAO)`를 한 트랜잭션에서 생성한다. 비밀번호는 USER가 아니라 EMAIL 로그인 수단에만 존재한다.
`LOGIN_IDENTITY.user_id`와 `(provider, provider_subject)`는 각각 UNIQUE다. EMAIL은 `password_hash`·`email_verified_at`이 필수이고, KAKAO는 둘 다 null이다.

### 1-9 ~ 1-10 토큰 관리

`POST /auth/refresh` `{"refreshToken": "..."}` → `200` 새 액세스 + **회전된 리프레시**. 오류 `401 INVALID_REFRESH_TOKEN`(만료·revoked) → 앱은 재로그인 유도.
`POST /auth/logout` `{"refreshToken": "..."}` → `204` — 해당 리프레시 revoke.

### 1-11 `POST /auth/password/reset-request`

```json
{ "email": "runner@test.com" }
```
`202` — **가입 여부와 무관하게 항상 성공 응답** 🔒(§4.3 계정 존재 노출 방지). 가입된 이메일이면 1회용 재설정 토큰(유효 30분, 해시로만 저장 — NFR-9) 링크 발송. 쿨다운 60초(`429`).

### 1-12 `POST /auth/password/reset`

```json
{ "token": "재설정 링크의 토큰", "newPassword": "newRun4life1" }
```
`204` — 변경 + 사용 즉시 토큰 만료 + **해당 계정의 모든 리프레시 토큰 revoke** 🔒(§4.3-4, NFR-11) → 앱 재로그인.
오류: `400 INVALID_RESET_TOKEN`(만료·사용됨·불일치) · `400 INVALID_PASSWORD`

---

## 2. 회원 API `/api/me` (인증)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/me` | 내 정보 — `{id, email, nickname, loginProvider, agreements, createdAt}` (앱 시작 세션 검증 겸용). `email` 키는 항상 포함하는 `string|null`: EMAIL은 정규화 이메일, KAKAO는 카카오가 제공한 이메일 스냅샷 또는 `null`. KAKAO 가입자에게 별도 이메일 입력·인증을 요구하지 않으며, 앱은 `null`이면 이메일 행을 숨기고 placeholder를 두지 않음 |
| PATCH | `/me` | 닉네임 변경 `{"nickname": "..."}` — `409 NICKNAME_DUPLICATED` |
| PATCH | `/me/agreements` | 선택 약관 변경 `{"marketing": true}`. 필수 약관 철회는 탈퇴 절차로 안내 |
| PUT | `/me/password` | EMAIL 로그인 수단의 비밀번호 변경. 성공 시 기존 refresh token 전부 revoke 후 현재 기기용 token pair 재발급 |
| POST | `/me/reauth` | 탈퇴용 재인증 토큰 발급 |
| DELETE | `/me` | `X-Reauth-Token` 필수. 탈퇴 후 동의·찜·동선·코스·기록 연쇄 삭제, `204` |

가입 수단 정책:

- 한 `USER`는 EMAIL 또는 KAKAO 중 정확히 하나만 보유한다.
- P0에서는 로그인 수단 연결·추가·해제·전환 API를 제공하지 않는다.
- 동일 이메일만으로 서로 다른 provider 계정을 자동 병합하지 않는다.
- 이메일 주소 변경은 MVP 범위 밖이다.

### 2-1 `PUT /api/me/password` — EMAIL 비밀번호 변경

`GET /api/me`의 `loginProvider=EMAIL`인 계정에서만 사용한다. Android는 `loginProvider=KAKAO`이면 비밀번호 변경 메뉴를 노출하지 않는다.

```json
{ "currentPassword": "run4life1", "newPassword": "newRun4life2" }
```

`200` — 한 트랜잭션에서 비밀번호를 변경하고 해당 사용자의 기존 refresh token hash를 전부 revoke한 뒤, 현재 기기용 새 token pair를 발급한다. 앱은 두 토큰을 원자적으로 교체한다.

```json
{ "accessToken": "eyJhbGciOi...", "refreshToken": "eyJhbGciOi..." }
```

오류: `400 CURRENT_PASSWORD_MISMATCH` · `400 INVALID_PASSWORD` · `409 EMAIL_IDENTITY_REQUIRED`.

### 2-2 `POST /api/me/reauth` · `DELETE /api/me` — 탈퇴 재인증

재인증은 계정의 단일 `loginProvider`와 같은 수단으로 수행한다. EMAIL은 현재 비밀번호, KAKAO는 Android SDK가 방금 발급한 액세스 토큰을 보낸다.

```json
{ "provider": "EMAIL", "password": "run4life1" }
```

또는

```json
{ "provider": "KAKAO", "kakaoAccessToken": "카카오 SDK 토큰" }
```

`200 {"reauthToken":"...","expiresInSec":300}`. `reauthToken`은 현재 사용자와 `DELETE_ACCOUNT` 목적에만 유효한 5분 토큰이며 로그에 남기지 않는다.

`DELETE /api/me`는 `X-Reauth-Token: {reauthToken}` 헤더를 요구한다. 성공 시 `204`, 사용자의 모든 refresh token을 revoke하고 USER에 종속된 동의·찜·동선·저장 코스·러닝 기록을 삭제한다. 앱은 성공 후 Access/Refresh Token과 사용자 Room 캐시를 삭제한다.

오류: `401 REAUTH_FAILED` · `401 INVALID_REAUTH_TOKEN` · `409 REAUTH_PROVIDER_MISMATCH`.

---

## 3. 대회 API `/api/contests` (공개)

**데이터 적재 계약 🔒(결정-39·40·46·47·48)**: 크롤 원천의 정규화·중복 병합은 Python 데이터 파이프라인이 수행한다. P0에서는 canonical·`events[]`·원천 `sources[]`를 포함한 서버용 JSON 스냅샷을 생성하고, 백엔드 Importer가 이를 검증해 `CONTEST`·`CONTEST_EVENT`·`CONTEST_SOURCE`에 트랜잭션으로 멱등 적재한다. 성공한 적용 이력은 `CONTEST_SNAPSHOT_IMPORT`에 같은 트랜잭션으로 기록한다. Importer가 읽은 snapshot 파일 바이트의 SHA-256인 `snapshot_sha256`으로 동일 snapshot을 no-op 처리하고 과거 snapshot 또는 동일 기준 시각의 다른 `snapshot_sha256`을 거부한다. `meta.sourceSha256`은 입력 CSV 출처 추적용이며 snapshot 식별에 쓰지 않는다. `CONTEST.start_time`·`road_address`·`detail_url`은 nullable로 저장한다. Python은 운영 핵심 테이블에 직접 쓰지 않는다. 현재 목업용 `reference-web/public/data/races.json`은 서버 스냅샷으로 직접 사용하지 않는다. 검증 완료 full snapshot에서 source가 2회 연속 누락될 때만 비활성화하고, 실패·부분 snapshot은 누락 횟수에 포함하지 않으며 재등장 시 즉시 활성화한다. canonical은 활성 source 존재 여부로 `active`를 파생·갱신하고 물리 삭제하지 않는다. 다중 원천은 정상 수용하되, 최대 source 겹침 동률 또는 기존 canonical 하나의 분리처럼 승계할 기존 PK를 하나로 확정할 수 없으면 snapshot 전체를 거부하고 기존 참조와 적용 이력을 유지한다. 향후 자동화는 인증된 내부 수집 API 또는 스테이징 테이블 후 백엔드 승격 방식 중 하나로 전환한다. **스냅샷 파일 계약(경로 `data/contest_snapshot.json`·스키마·유일키·Importer 검증 의무)은 `docs/contest-snapshot-contract.md`가 SSOT다.**

### 3-1 `GET /api/contests` — 목록 (커서 페이징) 🔒

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `q` | string | 대회명+장소+지역 부분 일치 (홈 검색창에서 전달) |
| `events` | string[] | `K5,K10,HALF,FULL` — 복수 선택 **OR** 🔒(결정-12) |
| `openOnly` | boolean | 접수 가능만 (오늘 기준 **파생** 접수상태 = OPEN) |
| `regions` | string[] | 17개 시도 복수 선택 (부록 C) |
| `date` | date | 월간 뷰에서 선택한 날짜 — 해당 일자만 |
| `cursor` | string | 이전 응답의 불투명 `nextCursor` 그대로. 클라이언트 해석 금지 |
| `size` | int | 기본 20 · 최대 50 🔧 |

규칙: `active=true AND contest_date >= 오늘(KST)` 고정 🔒 · 정렬 `(contest_date, id) ASC` · 필터는 AND 결합(events 내부만 OR) · 서버는 cursor를 검증·복호화한 뒤 내부 `(contestDate, id)` 튜플에 keyset 조건을 적용한다. 비활성 대회는 검색·목록·월간 건수·마감 임박에서 제외한다.

```json
{
  "items": [
    {
      "id": 153,
      "name": "2026 세종 호수공원 마라톤",
      "region": "세종", "place": "세종중앙공원",
      "contestDate": "2026-08-22", "startTime": "08:00",
      "events": ["FULL", "HALF", "K10"],
      "regStatus": "CLOSED",
      "applyStart": "2026-04-01", "applyEnd": "2026-08-10",
      "imageUrl": "https://...",
      "sources": ["MARATHON_ONLINE", "MARATHON_GO"],
      "checkedAt": "2026-07-15T04:30:00Z",
      "active": true,
      "favorite": false
    }
  ],
  "nextCursor": "MjAyNi0wOC0yMnwxNTM",
  "hasNext": true
}
```

- 대회는 관리 배치가 `CONTEST_SOURCE` 원본을 정규화·중복 제거해 `CONTEST` canonical 레코드로 만든다. 공개 API는 canonical만 읽고 사용자는 생성·수정·삭제할 수 없다.
- `applyStart`와 `applyEnd`는 nullable이다. 앱은 Room에 캐시한 목록을 오프라인에서 표시할 때 두 날짜를 오늘(KST) 기준 접수상태 재계산 근거로 사용한다(SPEC §5.5).
- `startTime`은 nullable이다. 원천에 출발 시각이 없거나 형식이 올바르지 않으면 `null`을 반환한다.
- `regStatus`는 **저장값이 아니라 공통 함수로 조회 시점 파생** 🔒: `applyEnd != null && applyEnd < 오늘 → CLOSED` / `applyStart != null && 오늘 < applyStart → BEFORE` / `applyStart != null && applyStart <= 오늘 → OPEN` / **`applyEnd`만 알고 아직 지나지 않았으면 날짜만으로 `OPEN`을 단정하지 않고 최신 원본 상태** / 그 밖에 날짜만으로 판단할 수 없으면 최신 원본 상태 / 그것도 없으면 `UNKNOWN`.
- `events`가 빈 배열이면 클라가 "종목 미표기" 배지 표시.
- `favorite`: 로그인 시 실제 찜 여부, 게스트는 항상 `false`.
- `imageUrl`은 nullable이며 없으면 앱이 기본 placeholder를 표시한다.

### 3-2 `GET /api/contests/daily-counts` — 월간 뷰 점 집계 🔒(정리본 확정 6)

`?year=2026&month=8` + 3-1과 **동일한 필터 파라미터**(`q, events, openOnly, regions`) → `200 {"counts": [{"date": "2026-08-22", "count": 2}]}`
구현 규약: 목록 API와 **검색 Predicate 및 regStatus 파생 함수를 공유**해 점 표시와 목록 불일치를 방지한다(부록 G-1).

### 3-3 `GET /api/contests/closing-soon` — 홈 마감 임박

`?limit=4` 🔒(기본 4, 허용 범위 1~4) → 파생 접수상태 `OPEN` ∧ `applyEnd not null`, `applyEnd ASC`.
범위를 벗어난 `limit`은 `400 VALIDATION_FAILED`.
응답 항목 = 3-1 카드 + `"dDayApply": 11` (applyEnd − 오늘, "마감 D-n" 배지용).

### 3-4 `GET /api/contests/{id}` — 상세

3-1 카드 필드 + `organizer, officialUrl, lat, lng, dDay`(대회일 − 오늘, KST).
`404 CONTEST_NOT_FOUND`. `organizer`, `officialUrl`, `imageUrl`, `lat`, `lng`는 nullable이다. 현재 원천 271건과 canonical 153건은 좌표 누락 0건이다. 좌표는 지도·인근 축제·동선 위저드의 기준점이며, 앱은 좌표가 없으면 P0에서 동선 만들기 CTA를 비활성화한다.
비활성 canonical도 id 상세 조회는 유지하고 `active=false`를 반환한다. 앱은 "정보 제공 종료"를 표시하고 동선 생성 CTA를 비활성화하며 3-5를 호출하지 않는다. 찜·저장 동선의 참조를 보존하기 위한 계약이며 비활성을 `404`로 숨기지 않는다.

### 3-5 `GET /api/contests/{id}/festivals` — 인근 축제 (M3 프록시) 🔒

구현 규약(§8.3 — **위치기반조회 아님**, 부록 E-1 함정):
`searchFestival2(eventStartDate = 대회일−14일)` 호출 → 서버에서 ① 기간이 대회일 ±14일과 겹치는 것 필터 ② **Haversine 반경 40km** 필터 → 거리순 6건. 대회별 1일 캐시.

```json
{
  "items": [
    {
      "contentId": "2764321",
      "name": "세종 빛 축제",
      "startDate": "2026-08-20", "endDate": "2026-08-25",
      "distanceKm": 0.8,
      "imageUrl": "http://tong.visitkorea.or.kr/...",
      "address": "세종특별자치시 연기면"
    }
  ]
}
```
빈 배열 = 목업 빈 상태("대회 기간에 열리는 인근 축제가 없어요") · `502` = 로딩 실패 상태 매핑. 출처 표기 "한국관광공사"는 클라 고정 문구(NFR-7). 대회 좌표가 없으면 외부 API를 호출하지 않고 `409 CONTEST_LOCATION_UNAVAILABLE`.

`409 CONTEST_LOCATION_UNAVAILABLE`은 **빈 상태가 아니라 재시도 버튼이 없는 별도 오류 상태**로 그린다 — 문구는 "인근 축제를 확인할 수 없어요"로 고정한다. 좌표는 다시 눌러도 생기지 않으므로 [다시 시도]를 붙이면 헛돌고, 그렇다고 "축제가 없어요"로 적으면 사실과 다르다.

**수집·캐시 추적 메타데이터(`fetchedAt`·`cachedAt`·KTO 원천 구분)는 응답에 넣지 않는다.** 서버 내부 로그·모니터링에서만 관리한다 — 어느 화면도 그 값을 그리지 않기 때문이다(출처 표기는 위와 같이 클라 고정 문구다). 앱은 위 일곱 필드만 받는다.

---

## 4. 축제 · POI 프록시 API (공개)

### 4-1 `GET /api/festivals` — 홈 축제 섹션

| 파라미터 | 설명 |
|---|---|
| `yearMonth` | `YYYY-MM`(예: `2026-08`) 🔧 기본 = KST 이번 달. 형식이 다르면 `400 VALIDATION_FAILED` |
| `size` | 기본 6 🔧 · 허용 범위 `1~20`. 범위를 벗어나면 `400 VALIDATION_FAILED` |

응답은 `{"items": [...]}`이고 항목은 정확히 `{contentId, name, startDate, endDate, region, imageUrl, inProgress}`다. `inProgress` = start ≤ KST 오늘 ≤ end (진행중 배지).
조회 월과 겹치는 전국 축제를 진행 중 우선, 시작일 오름차순으로 반환한다. 홈에서는 위치 권한과 사용자 좌표를 사용하지 않는다. 서버가 KTO `searchFestival2`를 호출·캐시하며 앱은 우리 서버만 호출한다.

`region`은 KTO `addr1`의 첫 토큰을 17개 시도 단축명(서울·부산·대구·인천·광주·대전·울산·세종·경기·강원·충북·충남·전북·전남·경북·경남·제주)으로 정규화한 값이다. `addr1`이 없거나 17개 시도로 판별할 수 없으면 항목을 제외하지 않고 `region: ""`으로 유지한다. 좌표·주소·상세 이동 키·`fetchedAt/cachedAt/source` 같은 추적 메타데이터는 응답에 추가하지 않는다.

**P0에서 홈 축제 카드는 표시 전용이다** — 축제 상세로 이동하는 동작도, 그 화면으로 가는 route도 없다(결정 D-05). 그래서 응답에 상세 조회용 키를 더 넣지 않는다.

**수집·캐시 추적 메타데이터(`fetchedAt`·`cachedAt`·KTO 원천 구분)는 응답에 넣지 않는다.** 서버 내부 로그·모니터링에서만 관리한다 — §3-5와 같은 정책이며, 어느 화면도 그 값을 그리지 않는다. 앱은 위 일곱 필드만 받는다. (오프라인 표시에 쓰는 `cachedAt`은 기기 Room 읽기 캐시의 것이라 이 응답 필드와 다른 이야기다.)

### 4-2 `GET /api/pois` — 위저드 숙소 · 동선 슬롯 · 교체/추가 시트

| 파라미터 | 설명 |
|---|---|
| `category` | 필수. `TOUR / FOOD / CAFE / WELLNESS / NATURE / HISTORY / LODGING` (부록 B 매핑) |
| `lat` `lng` | 필수 WGS84 기준점. `lat=-90~90`, `lng=-180~180` (숙소 선택 전 = 대회장, 이후 = 숙소·현재 블록) |
| `radius` | 기본 8000m 🔧 · 양의 정수, 최대 20000(KTO 제약) · **3건 미만이면 같은 원천을 20km에서 재검색** 🔒(§8.1) |
| `query` | *(선택)* 키워드 검색 — W3 숙소 검색창. 공백 제거 후 2자 이상, 미만이면 `400 VALIDATION_FAILED` |
| `size` | 기본 8 🔒(노출 8건) · 허용 범위 `1~20` |

```json
{
  "source": "LIVE",
  "items": [
    {
      "name": "호텔 세종 가온", "category": "LODGING", "provider": "KAKAO",
      "lat": 36.4912, "lng": 127.2714, "distanceM": 1200,
      "description": "어진동 · 대회장 1.2km",
      "address": "세종특별자치시 어진동 123", "url": "https://place.map.kakao.com/...",
      "imageUrl": null
    }
  ]
}
```

항목은 정확히 `{name, category, provider, lat, lng, distanceM, description, address, url, imageUrl}`다.
`provider`는 실제 항목을 제공한 원천 `KAKAO | KTO`이며 필수다. `name`, `category`, `provider`,
`lat`, `lng`, `distanceM`, `description`, `address`, `url`은 non-null이고, 원천에 문자열 값이 없으면
빈 문자열을 반환한다. `imageUrl`만 nullable이다. `distanceM`은 요청 기준점부터의 0 이상 정수 미터다.

P0 동선은 POI를 별도 마스터로 참조하지 않고 장소 snapshot을 저장하므로 안정적 `placeId`를 응답에
두지 않는다. `fetchedAt`·`cachedAt`도 서버 캐시·운영 정보이므로 응답에서 제외한다. 앱의 Room
`cachedAt`과는 별개다.

| category | 1순위 | 폴백 |
|---|---|---|
| `TOUR` · `HISTORY` | KTO KorService2 `locationBasedList2` 12 | 카카오 AT4/키워드 |
| `FOOD` | 카카오 FD6 | KTO KorService2 39 |
| `CAFE` | 카카오 CE7 | 없음 |
| `LODGING` | 카카오 AD5 | KTO KorService2 32 |
| `WELLNESS` | KTO WellnessTursmService | 카카오 키워드 |
| `NATURE` | 카카오 자연 키워드 + AP-23 두루누비 번들 | KTO KorService2 12 |

조회·폴백 규칙은 다음과 같다.

1. 1순위 원천을 요청 반경에서 조회한다.
2. 결과가 3건 미만이고 요청 반경이 20km 미만이면 같은 원천을 20km에서 다시 조회한다.
3. 그래도 `size`보다 적으면 폴백 원천에서 부족한 개수를 채운다. 폴백이 없는 카테고리는 적은
   결과를 그대로 반환한다. AP-23 두루누비 어댑터 장애는 NATURE의 카카오·KTO 결과를 막지 않는다.
4. 정규화한 이름과 좌표가 같은 항목을 중복 제거하고 `distanceM` 오름차순으로 최대 `size`건을
   반환한다. 최종 응답 `items`에서는 JSON 응답값 기준 `(name, lat, lng)` 조합의 유일성을
   보장한다. 좌표는 숫자값으로 비교하므로 소수점 뒤 0만 다른 값은 같다. 앱은 이 조합을 목록
   `key`로 사용할 수 있다.
5. 성공 결과만 요청 파라미터 기준으로 5분 캐시하고 오류 응답은 캐시하지 않는다.

모든 원천이 정상 응답했지만 결과가 없으면 `200 {"source":"LIVE","items":[]}`다. 일부 원천이
실패해도 다른 원천에서 한 건 이상 만들면 `200`으로 성공한다. 표시 항목이 없는데 하나 이상의
원천이 실패했다면 모든 실패가 timeout일 때 `504 EXTERNAL_API_TIMEOUT`, 그 외에는
`502 EXTERNAL_API_ERROR`다. 외부 장애를 빈 배열이나 SAMPLE/SYNTH로 낮추지 않는다.

- Android는 최초 진입 시 query 없이 주변 8건을 조회하고, 검색어가 2자 이상일 때 500ms debounce 후 query를 보낸다. debounce는 앱 내부 정책이다.
- 운영 응답의 `source`는 항상 `LIVE`다. `SAMPLE/SYNTH`는 목업·데모 빌드 전용이며, 운영에서 502/504를 샘플 데이터로 숨기지 않는다(NFR-2).

### 4-3 걷기 스팟 — `/courses/near` 내부 조회원

독립 `GET /api/walk-spots` 앱 API는 제공하지 않는다(이슈 #19 결정). 서버가 카카오 키워드 6종(공원·산책로·둘레길·하천·한강공원·생태공원)을 반경 3km에서 조회하고, SPEC §5.9 포함/제외·중복 제거 규칙을 적용한 거리순 12곳을 `GET /api/courses/near`의 `PLACE` 후보로 합친다. 카카오 조회 캐시는 두루누비 코스 캐시와 분리해도 되지만 앱에는 6-1의 단일 응답만 노출한다.

### 4-4 `GET /api/geocode` — 출발지 검색

`?query=해운대해수욕장` → `200 {"name": "해운대해수욕장", "address": "부산 해운대구 ...", "lat": 35.1587, "lng": 129.1604}` (카카오 키워드 첫 결과 🔒 §4.11-1). `404 NO_RESULT`.
`query`는 필수이며 서버가 앞뒤 공백을 제거한다. 누락됐거나 공백뿐이면 `400 VALIDATION_FAILED`다.

---

## 5. 동선 API `/api/itineraries`

### 5-1 `POST /api/itineraries/generate` — 생성 (무상태 · **게스트 허용**) 🔒(정리본 확정 5)

**생성 권한 🔒(결정-41)**: P0 운영 동선은 이 서버 API만 생성한다. 앱은 카테고리별 POI를 조회해 자체 `ItineraryEngine`으로 새 동선을 조립하지 않고, 이 응답을 표시·저장 전 편집한다. 서버 엔진은 외부 POI 어댑터와 §5.6 순수 규칙 모듈을 분리해 테스트한다.

```json
{
  "contestId": 153,
  "startDate": "2026-08-21", "endDate": "2026-08-23",
  "event": "HALF",
  "themes": ["TOUR", "FOOD"],
  "hotel": { "name": "호텔 세종 가온", "lat": 36.4901, "lng": 127.2688 }
}
```
- `hotel`은 `null` 허용("숙소 없이 추천받기" → 대회장 중심으로 슬롯 채움 🔒 §4.9).
- `themes`는 1개 이상(§4.8 — 0개면 클라 CTA 비활성) · `event`는 대회 종목에 없어도 선택 가능(§4.8).
- `startDate/endDate`는 역순을 허용하지 않으며 시작·종료일 포함 최대 7일, 해당 대회일을 반드시 포함한다. 위반 시 `400 INVALID_TRAVEL_PERIOD`.
- canonical 대회의 `lat/lng`가 없으면 생성하지 않고 `409 CONTEST_LOCATION_UNAVAILABLE`.
- 비활성 대회는 새 동선을 생성하지 않는다. 정확한 HTTP status와 문제 응답 `code`는 이슈 #56의 추가 리뷰 전까지 미확정이므로 임의의 오류 계약으로 구현하지 않는다.

응답 `200` — **DB 저장 없는 DTO** (규칙 엔진 §5.6 서버 이식: 날짜 골격 → 고정 블록(대회·체크인/아웃) → 종목→피로도 → 회복일 → 슬롯 채우기):

```json
{
  "title": "세종 2박 3일",
  "contestId": 153, "event": "HALF", "themes": ["TOUR", "FOOD"],
  "startDate": "2026-08-21", "endDate": "2026-08-23",
  "hotel": { "name": "호텔 세종 가온", "lat": 36.4901, "lng": 127.2688 },
  "recovery": { "label": "D+1 회복 모드", "note": "하프는 완주 다음날 회복이 중요해요..." },
  "days": [
    {
      "dayIndex": 0, "date": "2026-08-21", "dayLabel": "D-1",
      "recovery": false, "note": "내일 완주 · 가볍게 먹고 푹 쉬기",
      "blocks": [
        { "startTime": "15:00", "title": "숙소 체크인", "category": "LODGING",
          "placeName": "호텔 세종 가온", "address": "세종특별자치시 어진동 123",
          "lat": 36.4901, "lng": 127.2688, "description": "짐 풀고 휴식",
          "blockType": "USER", "systemManaged": false },
        { "startTime": "18:30", "title": "카보로딩 저녁", "category": "FOOD",
          "placeName": "도담동 파스타집", "address": "...", "lat": 36.5, "lng": 127.26,
          "description": "탄수화물 보충", "blockType": "USER", "systemManaged": false }
      ]
    }
  ]
}
```
- `recovery`는 하프/풀만, D+ 없으면 `"D-day 회복 모드"` 🔒(§5.6-6). 회복일 day는 `recovery=true`.
- 외부 POI 실패 시 해당 블록 `placeName/lat/lng=null` 강등, **생성은 실패하지 않음** 🔒(NFR-3).
- 슬롯 채우기 카테고리 풀: `{FOOD, TOUR} ∪ themes` + (noHard면 WELLNESS, 아니면 CAFE) 🔒(§5.6-2).
- 정상 처리됐지만 표시 가능한 블록이 없으면 `200`에서 `days: []`를 반환하고 앱은 S7 Empty로 표시한다. 네트워크·timeout·4xx/5xx는 Error이며 Empty로 강등하지 않는다.

### 5-2 `POST /api/itineraries` — 저장 (인증, 트리 1회 cascade) 🔒

요청 = 5-1 응답 구조 그대로(클라 편집 반영본). `201 {"id": 42}`.
같은 `(user, contestId, startDate, endDate)`가 이미 있으면 **교체** 후 `200 {"id": 42, "replaced": true}` 🔒(SPEC §4.10 trip id `{대회id}-{시작일}-{종료일}` "동일 id 교체" 계약 승계).
검증: `RACE` 블록은 서버가 저장 시점의 canonical 대회 정보로 재구성해 **강제 주입**하고 이후 snapshot으로 보존한다. 클라이언트가 보낸 RACE 제목·시간·장소·순서는 신뢰하지 않는다. `region`, `recovery.label/note`, 호텔, 일자와 USER/RACE 블록 전체도 저장 시점 snapshot이다.

### 5-3 `PUT /api/itineraries/{id}` — 재생성 결과로 교체 (인증·소유자)

대회 변경 안내에서 사용자가 다시 만든 결과를 최종 저장할 때만 호출한다. 요청은 5-1 응답 구조의 클라이언트 편집 반영본이며, path의 기존 동선과 `contestId`가 같아야 한다. 서버는 현재 canonical으로 RACE를 재구성·검증한 뒤 기존 id를 유지하고 트리와 snapshot을 한 트랜잭션에서 교체한다. 성공은 `200 {"id": 42, "replaced": true}`다.

`POST /api/itineraries/generate` 미리보기와 확인 모달 단계에서는 기존 동선을 변경하지 않는다. 교체 트랜잭션이 실패해도 기존 트리를 유지하며, 기존 USER 편집을 새 결과에 자동 병합하지 않는다.

### 5-4 `GET /api/itineraries` — 내 동선 목록 (인증, Pageable)

`content[]`: `{id, title, contestId, contestName, event, region, recovery, startDate, endDate, placeCount, createdAt, active, needsRegeneration}`.

- `contestName`, `active`는 현재 `CONTEST`에서 파생하고 `region`, `recovery`, 기간은 저장 snapshot이다.
- 저장된 RACE의 날짜·시간·장소·지역·좌표와 현재 canonical이 다르면 `needsRegeneration=true`다. 이름만 바뀐 경우와 `active` 변경만으로는 true가 되지 않는다.
- 앱은 `needsRegeneration=true`에 "대회 변경" 배지를 표시하고, `active=false`인 기존 동선도 목록에서 삭제하지 않는다.

### 5-5 `GET /api/itineraries/{id}` — 상세 (인증·소유자)

5-1 응답 구조 + `id`, snapshot `region`, `days[].id`, `blocks[].id`, `blocks[].orderNo`(ASC 정렬), `needsRegeneration`, 최신 canonical `contest: {name, region, place, contestDate, startTime, lat, lng, active}`. S7 복원·편집 모드 진입용이다.

`recovery`, 호텔, days와 모든 블록은 저장 snapshot을 반환한다. 특히 RACE 날짜·시간·장소를 최신 canonical로 자동 덮어쓰거나 타임라인을 재배치하지 않는다. 앱은 `contest`를 최신 대회 안내에, snapshot 트리를 저장 당시 일정 표시에 사용한다.
구현 규약: days+blocks 동시 fetch join 금지(MultipleBagFetchException) → `hibernate.default_batch_fetch_size=100` 배치 로딩 🔒(정리본 확정).

### 5-6 `DELETE /api/itineraries/{id}` → `204` (소유자 검증 `403`)

### 5-7 ~ 5-10 저장 후 편집 (인증·소유자) 🔒(정리본 확정 8 — 저장 전 편집은 클라 로컬)

| # | 메서드/경로 | 규칙 |
|---|---|---|
| 5-7 | `POST /itineraries/{id}/days/{dayId}/blocks` | 추가 — body `{startTime(기본 "13:00" 🔒), title, category, placeName, address, lat, lng, description}` → `201 {blockId, orderNo}` (맨 끝) |
| 5-8 | `PATCH /itineraries/{id}/days/{dayId}/blocks/{blockId}` | USER 블록의 장소 교체·수정 — 보낸 필드만 반영. 성공 `200` + 갱신된 block 전체. RACE면 `409 SYSTEM_BLOCK_IMMUTABLE` |
| 5-9 | `DELETE /itineraries/{id}/days/{dayId}/blocks/{blockId}` | USER 블록 삭제 `204`. RACE면 `409 SYSTEM_BLOCK_IMMUTABLE` |
| 5-10 | `PUT /itineraries/{id}/days/{dayId}/blocks/order` | USER 블록끼리만 순서 변경 — body `{"blockIds": [21, 19, 23]}`. 해당 day의 **USER 블록 전체 집합**과 정확히 일치해야 함(`400 BLOCK_SET_MISMATCH`). RACE의 고정 위치를 넘나드는 요청은 `409 SYSTEM_BLOCK_IMMUTABLE`. 성공 `200 {"dayId":7,"blocks":[...]}`로 해당 일자의 전체 블록을 `orderNo` 오름차순 반환 |

5-8의 block 응답은 5-5 `blocks[]`와 같은 필드(`id, orderNo, startTime, title, category, placeName, address, lat, lng, description, blockType, systemManaged`)를 사용한다. 앱은 PATCH·order 응답으로 해당 블록 또는 일자의 상태를 교체한다.

---

## 6. 러닝코스 API `/api/courses` (공개)

> 원천: 두루누비 API `courseList` 최신 메타데이터+GPX 파싱본 261코스와 라이선스 검증 완료 큐레이션 GPX를 우선한다. 한국등산·트레킹지원센터가 제공한 국가숲길·100대명산 GPX는 이용허락범위 제한 없음·`derivable=true`이고 통합 출처 문구는 `등산로·숲길(한국등산·트레킹지원센터)`다. P0 운영 빌드는 `derivable=false`·출처 미확인 소스를 제외하고 `--include-nonderivable`을 사용하지 않는다. 내 주변에서 목표 거리에 맞고 상승 `50m/km` 미만인 큐레이션 경로가 0건이면 서버 내부 GraphHopper가 대한민국 OSM 그래프와 SRTM 고도로 품질 상한을 통과한 순환 경로를 최대 1건 생성한다(결정-42 개정). OSM 생성 경로는 지역 목록·코스 마스터에 적재하지 않는다.
>
> 두루누비 번들 파일·시작 후/24시간 동기화·`courseId` 결합·원자적 fail-open의 내부 계약은
> [`docs/course-bundle-contract.md`](../course-bundle-contract.md)가 기준이다. 이 catalog는
> PostgreSQL에 복제하지 않고 검증된 번들에서 시작한 불변 메모리 snapshot으로 제공한다.

### 6-1 `GET /api/courses/near` — 내 주변 경로·장소 통합 목록

`?lat=&lng=&targetKm=5&radiusKm=8&size=12`

- `targetKm`: 1~21, 0.5 단위.
- `radiusKm`: 기본 8. 큐레이션 진입점 조회 반경이며 GraphHopper는 입력 출발점에서 순환 경로를 만든다.
- `size`: 큐레이션/OSM 경로와 PLACE를 합친 최대 항목 수, 기본·최대 12.
- P0 내 주변 요청에는 난이도 파라미터가 없다. 서버가 `HARD(≥50m/km)`를 자동 추천에서 제외하며 응답 `difficulty`는 표시용이다.

```json
{
  "items": [
    {
      "kind": "ROUTE",
      "routeId": "osm:2e808bd75c4a",
      "dataSource": "OSM_GENERATED",
      "name": "내 주변 5km 평지 러닝코스",
      "distanceM": 12,
      "lat": 37.52461, "lng": 126.92028,
      "difficulty": "EASY",
      "routeKm": 5.02, "durationMin": 46,
      "gainM": 38,
      "elevationProfileM": [12, 14, 17, 15, 19, 18],
      "shortfall": false,
      "pathPolyline": "인코딩된 왕복 경로"
    },
    {
      "kind": "PLACE",
      "name": "여의도공원",
      "distanceM": 650,
      "lat": 37.5264, "lng": 126.9227,
      "category": "공원",
      "address": "서울 영등포구 여의공원로 68",
      "placeUrl": "https://place.map.kakao.com/..."
    }
  ],
  "degradedSources": [],
  "attributions": ["© OpenStreetMap contributors", "카카오 로컬"]
}
```
- 공통 필드: `kind`, `name`, `distanceM`, `lat`, `lng`. `distanceM`은 입력 출발지에서 실제 경로 시작점 또는 장소까지의 거리다.
- `ROUTE` 전용 공통: `routeId`, `dataSource`, `difficulty`, `routeKm`, `durationMin`, `gainM`, `elevationProfileM`, `shortfall`, `pathPolyline`. `/courses/near`의 `difficulty`는 생성된 왕복 구간의 실제 `gainM/routeKm` 기준이다.
- `OSM_GENERATED.name`은 서버가 만든 한국어 완성 문구다. 앱은 이름을 다시 조합하지 않고 표시·저장 요청에 그대로 사용하며, 저장 코스 snapshot도 같은 `courseName`을 보존한다.
- 큐레이션 `ROUTE`만 `sourceCourseId`, `sido`, `sigun`, `fullDistanceKm`를 추가한다. OSM 생성 경로는 원본 코스가 없으므로 이 필드를 `null`로 채우지 않고 생략한다.
- `PLACE` 전용: `category`, `address`, `placeUrl`. 종류별 전용 필드는 다른 종류의 항목에서 `null`로 채우지 않고 생략한다.
- `routeId`는 near snapshot 내부 식별용 불투명 문자열이다. 저장·중복 판정에는 사용하지 않고 서버가 경로 snapshot으로 `routeFingerprint`를 다시 계산한다.
- 큐레이션 규칙 🔒(SPEC §5.8): 반경 내 코스별 최근접 진입점 → `targetKm/2` 편도(더 길게 뻗는 방향) → 왕복 경로. 조각의 `cumGainM`으로 계산한 상승이 `<50m/km`인 큐레이션 경로가 1건 이상이면 GraphHopper를 호출하지 않는다. 고도를 계산할 수 없는 조각은 내 주변 자동 추천에서 제외한다.
- OSM 규칙 🔒(SPEC §5.8): 적격 큐레이션 경로가 0건일 때 `run` 프로파일·보정 거리 0.78·seed 16개로 후보를 만든다. 목표 75~125%·상승 `<50m/km`·차도 실제 거리 비율 `≤10%`·실제 방향 전환 `≤6회/km`를 모두 통과한 후보만 남기고, 차도 `≤5%` 그룹 우선 → 거리 오차 → 방향 전환/km → 차도 비율 순으로 최대 1건을 고른다. 통과 후보가 없으면 상한을 완화하지 않고 OSM 경로 0건으로 처리한다.
- 차도 비율은 `PRIMARY|SECONDARY|TRUNK|TERTIARY|MOTORWAY` path detail 각 구간의 폴리라인 실거리 합으로 계산한다. `toRef-fromRef` 포인트 인덱스 개수를 거리로 사용하지 않는다. 방향 전환은 instruction sign `-98|-8|-3|-2|2|3|6|8`만 세고 직진·출발·도착·길 이름 변경은 제외한다.
- 난이도는 `gainM/routeKm`: `EASY <15m/km`, `NORMAL 15~50m/km 미만`, `HARD ≥50m/km`. 내 주변 `ROUTE`는 `EASY|NORMAL`만 나오며, 지역별 큐레이션 목록은 `HARD`도 표시와 함께 제공한다. `elevationProfileM`은 SRTM/GPX 고도를 최대 100개로 균등 축약한 배열이며 고도가 없으면 빈 배열이다.
- 경로 공통 계산은 분당 110m로 `durationMin`, `shortfall = routeKm < targetKm-0.3`이다.
- 장소 규칙 🔒(SPEC §5.9): 4-3의 카카오 후보를 포함/제외·중복 제거한 뒤 합친다.
- 서버가 `ROUTE`와 `PLACE`를 `distanceM` 오름차순으로 합쳐 최대 `size`건을 반환한다. 앱은 받은 순서를 다시 정렬하지 않는다.
- `degradedSources`는 호출·동기화 실패로 제외된 `DURUNUBI|OSM|KAKAO` 원천이다. 품질 상한 통과 후보 0건은 정상 결과이므로 `OSM` degraded가 아니다. 항목이 하나라도 있으면 부분 실패도 `200`이며 앱은 Content와 비차단 안내를 함께 표시한다.
- 모든 원천이 정상 완료되고 두 종류가 모두 0건이면 `200 {"items": [], "degradedSources": [], "attributions": []}`이며 S8 Empty다. 원천 실패가 있고 항목도 0건이면 `503 COURSE_SOURCES_UNAVAILABLE`로 S8 Error다.
- `attributions`는 실제 응답 항목에 사용된 원천만 중복 없이 큐레이션 → OSM → 카카오 순서로 반환한다. canonical 문구는 `두루누비 걷기길(한국관광공사)`, `등산로·숲길(한국등산·트레킹지원센터)`, `© OpenStreetMap contributors`, `카카오 로컬`이다. 새 큐레이션 GPX는 원본 `LICENSE.txt`를 확인해 빌드 산출물에 기록한 `attribution`을 사용하고, 출처 미확인 문구를 서버가 추측하지 않는다. 앱은 문자열을 변형하지 않고 목록 하단에 표시한다.

### 6-2 `GET /api/courses` — 지역별 (Pageable)

`?region=부산&page=0&size=20` → 큐레이션 코스만 `distanceKm ASC, courseId ASC`로
안정 정렬해 반환한다 🔒(§4.11-b). `region`은 앞뒤 공백 제거 후 `sido`와 정확히 일치시키며
`OSM_GENERATED`는 포함하지 않는다.

```json
{
  "content": [
    {
      "courseId": "T_CRS_MNG0000005117",
      "courseName": "해파랑길 1코스",
      "sido": "부산",
      "sigun": "남구",
      "distanceKm": 17.8,
      "difficulty": "NORMAL",
      "gainM": 312,
      "durationMin": 162,
      "dataSource": "API_GPX",
      "syncedAt": "2026-08-20T00:00:00Z"
    }
  ],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 27,
    "hasNext": true
  },
  "attributions": ["두루누비 걷기길(한국관광공사)"]
}
```

- `difficulty`는 전체 원본 코스의 정규화 등급으로, `/courses/near`에서 잘라 만든 왕복 구간의 등급과 달라도 정상이다.
- `courseId`는 번들·KTO 결합에 사용하는 안정적 유일키다. `dataSource`는 지역별 응답에서
  `API_GPX|GPX_ONLY`만 가능하다.
- `syncedAt`은 nullable UTC `Z`다. 현재 서버 프로세스에서 전체 KTO 동기화에 성공해 결합한
  `API_GPX` 항목만 완료 시각을 가지며, 번들 fallback과 `GPX_ONLY`는 `null`이다.
- `attributions`는 현재 응답 `content[]`에 실제 사용된 원천의 검증 완료 완성 문구만 중복 없이 담는다. 빈 페이지는 `[]`이다. 앱은 문자열을 변형하지 않고 배열 순서대로 `" · "`로 연결해 목록 하단에 표시한다.

### 6-3 `GET /api/courses/regions` → `{"items": [{"region": "부산", "count": 27}]}`

같은 catalog snapshot의 서비스 대상 코스를 `sido`별로 세고 `count DESC, region ASC`로
정렬한다. 0건인 시도는 만들지 않으며, `count` 합은 필터 없는 6-2의 `totalElements`와 같다.
KTO 동기화 실패 시에도 번들 또는 마지막 정상 snapshot으로 두 지역 API를 계속 제공한다.

---

## 7. 마이 API (인증)

### 7-A 저장 코스 `/api/me/courses`

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/me/courses` | 코스 저장(스냅샷) — body `{sourceCourseId?, dataSource, courseName, region?, distanceKm, durationMin, difficulty?, gainM, elevationProfileM, entryLat, entryLng, pathPolyline}`. 신규 `201 {id, created:true}`, 중복 `200 {id, created:false}` — `created`는 **항상 온다** |
| GET | `/me/courses` | 목록(Pageable) — **`pathPolyline` 제외 프로젝션** 🔧(목록이 LOB를 안 읽도록) |
| GET | `/me/courses/{id}` | 상세 — `pathPolyline`, `attributions[]` 포함 (코스 상세 **점선** 렌더링 🔒) |
| DELETE | `/me/courses/{id}` | `204` |

`difficulty`는 **선택**이다 🔧. `/courses/near`의 `difficulty`는 생성된 왕복 구간 기준이라 고도 정보가 없으면 값이 없고(§6-1 표시용), 저장 코스 목록·상세 응답에서도 `null`을 허용한다. 요청만 필수로 두면 난이도를 못 낸 경로는 저장 자체가 막힌다.

**목록 응답** `GET /me/courses` — Pageable(§0-4), `savedAt DESC, id DESC`.

```json
{
  "content": [
    {
      "id": 42,
      "courseName": "해파랑길 1코스",
      "distanceKm": 17.8,
      "durationMin": 162,
      "gainM": 312,
      "difficulty": "NORMAL",
      "dataSource": "API_GPX",
      "region": "부산",
      "savedAt": "2026-08-19T15:30:00Z"
    }
  ],
  "page": { "number": 0, "size": 20, "totalElements": 3, "hasNext": false }
}
```

| 필드 | 필수 | 비고 |
|---|---|---|
| `id` · `courseName` · `distanceKm` · `durationMin` · `gainM` · `savedAt` | ✅ | `savedAt`은 UTC `Z`, 앱이 KST 날짜로 접어 카드에 쓴다 |
| `difficulty` | ✗ | 원본 등급이 없으면 `null` — 배지를 그리지 않는다 |
| `dataSource` | ✗ | `API_GPX\|GPX_ONLY\|OSM_GENERATED` |
| `region` | ✗ | 큐레이션만. `OSM_GENERATED`는 `null` |
| `pathPolyline` · `elevationProfileM` · `attributions` | — | **목록에는 없다**(LOB 제외 프로젝션 🔧). 목록 카드는 지도를 그리지 않는다 |

**상세 응답** `GET /me/courses/{id}` — 목록 필드 + `elevationProfileM`(최대 100, 없으면 `[]`) · `pathPolyline`(경로가 없으면 `null`) · `attributions[]`(없으면 `[]`).

`sourceCourseId`와 `region`은 큐레이션 경로에만 있고 `OSM_GENERATED`에서는 생략한다. OSM 경로는 `/courses/near`에서 서버가 생성한 `name`을 `courseName`으로 그대로 저장한다. 서버는 `pathPolyline`의 geometry만 사용해 `routeFingerprint`를 계산한다. 좌표를 확정 정밀도로 정규화하고 연속 중복 좌표만 제거하되 진행 순서는 유지하며, 반대 방향은 다른 경로다. 코스명·지역·난이도·시간·상승고도·거리·`dataSource`는 입력에서 제외한다. 저장 형식은 `v1:<SHA-256 lowercase hex 64자>`이고 DB 타입은 `VARCHAR(67)`이다. 좌표 정밀도는 GraphHopper 실제 응답 확인 후 고정한다. `(userId, routeFingerprint)`가 같으면 새 행을 만들지 않고 기존 id를 반환하며 클라이언트가 fingerprint를 보내더라도 신뢰하지 않는다.

저장 시 서버는 `sourceCourseId`와 원천 메타데이터 또는 서버가 생성한 OSM 원천 정보를 기준으로 attribution 완성 문구를 확정한다. 요청에 attribution을 받지 않으며 클라이언트가 보내더라도 무시한다. `SAVED_COURSE.attributions`는 PostgreSQL `JSONB NOT NULL DEFAULT '[]'` snapshot이고, 외부 라이선스 문구가 바뀌어도 기존 값을 소급 변경하지 않는다. 상세 응답의 `attributions`는 `List<String>`이며 출처가 없으면 `[]`이다. 목록 응답에는 이 필드를 포함하지 않고, 상세에서 앱이 배열 순서대로 `" · "`로 연결한다. attribution은 `routeFingerprint` 입력에서 제외한다(결정-44, 이슈 #54).

### 7-B 러닝 기록 `/api/runs` — P1 예약

GPS 기록·`ran` 목록은 AP-22와 함께 P1에서 구현한다. P0 보관함은 7-A 저장 코스만 조회하며 saved/ran 통합 정렬·페이징 API는 두지 않는다. 아래 계약은 P1 구현 시 재검토할 예약 초안이다.

**`POST /api/runs`** — GPS 기록 저장

```json
{
  "courseName": "남파랑길 2코스",
  "ranAt": "2026-07-27T22:12:00Z",
  "distanceKm": 5.21,
  "durationSec": 1930,
  "points": [[35.11454, 129.04076], [35.11347, 129.04087]]
}
```
`201 {"id": 7, "avgPaceSec": 371}`
- `courseName`은 스냅샷 — **자유 러닝은 null** 🔒(§4.11-c).
- 5m 이동 필터는 **기록 중 클라가 수행** 🔒, 서버는 방어적 재필터 후 polyline 인코딩(`RUN_TRACK` 1:1 저장) + `avgPaceSec = durationSec ÷ distanceKm` 계산.
- 검증 🔒(NFR-13): 좌표 2개 이상 · **한국 영역**(위도 33~39, 경도 124~132 🔧) 벗어나면 `400 INVALID_TRACK` · 본인 기록만 접근.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/runs` | 목록(Pageable) — 요약만, RUN_TRACK 미조회(분리 설계 이유) — 마이 "07.28 (화) · 실제 5.21km · 32:10 · 6'11"/km" |
| GET | `/runs/{id}` | 상세 — `encodedPolyline, pointCount` 포함 (코스 상세 **실선** 렌더링 🔒) |
| DELETE | `/runs/{id}` | `204` |

### 7-C 찜 `/api/me/favorites` 🔒(결정-16)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/me/favorites` | 찜한 대회 목록(Pageable) — 항목 = 3-1 대회 카드(`favorite=true`). 공개 목록과 달리 비활성도 유지해 `active=false`로 반환하며, 지난 대회·비활성 흐림은 클라 판정 🔒 |
| PUT | `/me/favorites/{contestId}` | 찜 — **멱등** `204` (이미 찜이어도 성공). 스낵바 "찜했어요" |
| DELETE | `/me/favorites/{contestId}` | 해제 — 멱등 `204`. 스낵바 "찜을 해제했어요" |

---

## 부록 A. 화면 ↔ API 매핑 (커버리지 교차 검증)

| 화면 (정리본) | 액션 | API |
|---|---|---|
| 1-1 로그인 | 로그인 / 카카오 / 게스트 | 1-6 / 1-7·1-8 / (API 없음 — 토큰 없이 진입) |
| 1-2 회원가입 4단계 | 동의(클라 상태) → 정보 입력 → 인증 → 완료 | 1-1·1-2(중복) → 1-3·1-4(인증) → 1-5(가입) |
| 1-3 비밀번호 찾기 | 메일 발송 / 새 비밀번호 | 1-11 / 1-12 (+웹 페이지) |
| 2 홈 | 검색(엔터) / 마감 임박 4 / 축제 추천 | →S2에 `q` 전달(3-1) / 3-3 / 4-1 |
| 3 캘린더 리스트 | 목록·필터·검색·커서 / 찜 하트 | 3-1 / 7-C |
| 3 캘린더 월간 | 날짜별 점·건수 / 선택일 목록 | 3-2 / 3-1(`date=`) |
| 4 대회 상세 | 상세 / 찜 / 인근 축제 / 공식 페이지 | 3-4 / 7-C / 3-5 / (클라 Custom Tabs) |
| 5 위저드 W1·W2 | 기간·종목·취향 | (클라 상태 — 서버 호출 없음) |
| 5 위저드 W3 | 숙소 목록·검색 | 4-2 (`category=LODGING` ± `query`) |
| 5→6 생성 | 동선 추천받기 | 5-1 (게스트 허용) |
| 6 동선 결과 | 저장 / 빈 상태 재추천 | 5-2 / 5-1 |
| 6 편집(저장 후) | 순서·교체·삭제·추가 / 후보 시트 | 5-10·5-8·5-9·5-7 / 4-2 |
| 6 연계 카드 | 숙소 주변에서 뛰기 | →S8 진입(6-1, 출발지=숙소·목표 min(walk,5)km — walk는 5-5의 `event`로 파생) |
| 7 러닝코스 내 주변 | 내 위치/출발지 검색/프리셋 / 경로·장소 통합 목록 | (GPS 클라)·4-4·(클라 상수) / 6-1 |
| 7 러닝코스 지역별 | 지역 칩 / 목록 | 6-3 / 6-2 |
| 7 코스 저장·뛰기 | 저장(P0) / 뛰기(P1) | 7-A POST / (GPS 기록 클라 → 종료 시 7-B) |
| 8 GPS 기록/요약(P1) | 저장 / 삭제 | 7-B POST / 7-B DELETE |
| 9 마이 동선 | 목록 / 열기 / 재생성 교체 / 삭제 | 5-4 / 5-5 / 5-3 / 5-6 |
| 9 마이 코스 | P0 saved / P1 ran | 7-A GET / 7-B GET(P1) |
| 9 마이 즐겨찾기 | 목록 / 해제 | 7-C GET / 7-C DELETE |
| 9 마이 프로필 | 조회 / 닉네임·마케팅 수정 / 비밀번호 변경 | 2-GET / 2-PATCH·`/agreements` / 2-PUT `/password` |
| 9 마이 계정 | 가입 로그인 방식 표시 / 로그아웃 / 탈퇴 재인증·탈퇴 | 2-GET `/me` / 1-10 / 2-2 POST·DELETE |
| 10 코스 상세 | 점선(예정, P0) / 실선(뛴 기록, P1) | 7-A GET {id} / 7-B GET {id} |
| 공통 | 앱 시작 세션 확인 / 재발급 / 로그아웃 | 2-GET / 1-9 / 1-10 |

> 정리본의 모든 "액션" 항목이 매핑됨 — 누락 없음 확인(2026-07-30).

## 부록 B. 취향 카테고리 ↔ 외부 API 매핑 🔒(SPEC §5.3 + §8.1 이식 — 정리본 체크리스트 "매핑표" 해소)

| category | 라벨 | 1순위 | 폴백 | 비고 |
|---|---|---|---|---|
| TOUR | 관광지 | KTO `locationBasedList2` contentTypeId=12 | 카카오 AT4/키워드 | 공모전 필수요건 축, 공식 이미지 |
| FOOD | 맛집 | 카카오 category FD6 | KTO 39 | 커버리지 우선(실측 856곳) |
| CAFE | 카페 | 카카오 category CE7 | — | |
| WELLNESS | 힐링·웰니스 | KTO 웰니스 `wellnessThemaCd`(EX050100~) | 카카오 키워드 "온천 스파 사우나 찜질방" | noHard 핵심 · 희소해 기본 반경 20km · **페어 키 전용, 좌표 대문자 mapX/mapY** |
| NATURE | 자연·트레킹 | 카카오 키워드 "둘레길 공원 산책로 수목원" | KTO 12 | 걷기 스팟(4-3)과 공유 |
| HISTORY | 역사·문화 | KTO 12 | 카카오 키워드 "박물관 유적지 문화재" | |
| LODGING | 숙소 | **카카오 category AD5** | KTO 32 | 회의 결정 7 · KTO 숙박 희소(실측 4건) |

블록 태그 전용(조회 없음): RACE 대회 · RECOVERY 회복.

## 부록 C. Enum 사전

| Enum | 값 | 표시 |
|---|---|---|
| EventType | `K5, K10, HALF, FULL` | 5K · 10K · 하프 · 풀. 원천의 트레일 표기는 거리 기준 `stdEventKm`으로 4종 중 하나에 정규화 🔒(SPEC §5.4) |
| BlockCategory | `TOUR, FOOD, CAFE, WELLNESS, NATURE, HISTORY, LODGING, RACE, RECOVERY` | 관광지·맛집·카페·힐링웰니스·자연트레킹·역사문화·숙소·대회·회복 (9종 🔒) |
| RegStatus | `OPEN, BEFORE, CLOSED, UNKNOWN` | 접수중·접수전·마감·미정 (조회 시점 파생 🔒§5.5) |
| Region | 서울·부산·대구·인천·광주·대전·울산·세종·경기·강원·충북·충남·전북·전남·경북·경남·제주 | 17개 시도 🔒(§6.2 — 비표준 값은 배치에서 주소로 보정) |
| Provider | `EMAIL, KAKAO` | (구글·네이버 P2) |
| ContestSource | `MARATHON_ONLINE, MARATHON_GO` | 마라톤 온라인 · 마라톤GO |
| Difficulty | `EASY, NORMAL, HARD` | 평지·완만·언덕. 생성/조각 경로는 `<15`, `15~50 미만`, `≥50m/km`; 내 주변 자동 추천은 EASY/NORMAL만, 지역별 큐레이션 목록은 HARD도 제공 |
| PoiSource | `LIVE, SAMPLE, SYNTH` | 서버 라이브 · 데모/캐시 샘플 · 합성 |
| CourseDataSource | `API_GPX, GPX_ONLY, OSM_GENERATED` | API 메타+GPX 경로 · GPX fallback · 요청 시점 OSM 생성 경로 |
| CourseDegradedSource | `DURUNUBI, OSM, KAKAO` | `/courses/near` 부분 실패 원천 |
| BlockType | `USER, RACE` | 사용자 편집 가능 · 시스템 관리 |

## 부록 D. 에러 코드 총람

| code | HTTP | 발생 |
|---|---|---|
| `VALIDATION_FAILED` | 400 | 공통 요청 값 오류 (필드별 상세는 `errors[]` 🔧) |
| `INVALID_PASSWORD` | 400 | 8자 이상 영문+숫자 위반 |
| `CURRENT_PASSWORD_MISMATCH` | 400 | 비밀번호 변경의 현재 비밀번호 불일치 |
| `INVALID_TRAVEL_PERIOD` | 400 | CUSTOM 기간이 7일 초과·역순·대회일 미포함 |
| `AGREEMENT_REQUIRED` | 400 | 필수 약관 미동의 |
| `INVALID_CODE` / `CODE_EXPIRED` | 400 | 인증 코드 불일치 / 만료(10분) |
| `INVALID_RESET_TOKEN` | 400 | 재설정 토큰 만료·사용됨 |
| `BLOCK_SET_MISMATCH` | 400 | 순서 PUT의 blockIds가 day 전체 집합과 불일치 |
| `INVALID_TRACK` | 400 | 궤적 좌표 2개 미만·한국 영역 밖 |
| `LOGIN_FAILED` | 401 | 이메일/비밀번호 불일치 (존재 비노출) |
| `UNAUTHORIZED` | 401 | 토큰 없음·만료 (게스트 쓰기 → 로그인 모달) |
| `INVALID_KAKAO_TOKEN` / `INVALID_REFRESH_TOKEN` | 401 | 카카오 토큰 검증 실패 / 리프레시 무효 |
| `REAUTH_FAILED` / `INVALID_REAUTH_TOKEN` | 401 | 탈퇴 재인증 실패 / 탈퇴 전용 토큰 만료·불일치 |
| `EMAIL_NOT_VERIFIED` | 403 | 인증 미완료 상태로 가입 시도 |
| `FORBIDDEN` | 403 | 남의 동선·코스·기록 접근 |
| `CONTEST_NOT_FOUND` 등 `*_NOT_FOUND` | 404 | 리소스 없음 |
| `NO_RESULT` | 404 | 지오코딩 검색 결과 없음 |
| `EMAIL_DUPLICATED` / `NICKNAME_DUPLICATED` | 409 | 유니크 충돌 |
| `EMAIL_IDENTITY_REQUIRED` / `REAUTH_PROVIDER_MISMATCH` | 409 | KAKAO 가입자의 비밀번호 변경 / 가입 방식과 다른 수단으로 재인증 |
| `CONTEST_LOCATION_UNAVAILABLE` | 409 | 좌표 없는 대회의 인근 축제·동선 생성 시도 |
| `SYSTEM_BLOCK_IMMUTABLE` | 409 | RACE 블록 수정·삭제·이동 시도 |
| `SEND_COOLDOWN` / `TOO_MANY_ATTEMPTS` | 429 | 재발송 60초 / 코드 5회 초과 |
| `RATE_LIMITED` | 429 | 공개 중복 확인 IP 30회/분 또는 정규화 입력 5회/분 초과 |
| `INTERNAL_SERVER_ERROR` | 500 | 처리되지 않은 서버 내부 오류. 내부 메시지·스택 트레이스는 응답하지 않음 |
| `COURSE_SOURCES_UNAVAILABLE` | 503 | `/courses/near` 원천 실패로 표시할 경로·장소가 하나도 없음 |
| `EXTERNAL_API_ERROR` | 502 | 외부 API가 오류·비정상 응답 반환(동선 생성 제외 — NFR-3) |
| `EXTERNAL_API_TIMEOUT` | 504 | 외부 API 제한시간 초과(동선 생성 제외 — NFR-3) |

## 부록 E. 외부 API 함정 체크리스트 (백엔드 구현 시 — SPEC §7.4 발췌·실측 근거)

1. **인근 축제에 `locationBasedList2(15)` 금지** — 날짜 필드 없음(실측 A-3). `searchFestival2` + 서버 Haversine으로.
2. **`searchFestival2`에 구 `areaCode` 금지** — 에러 없이 0건(조용한 실패, 실측 A-6). 지역 필터는 `lDongRegnCd`만.
3. KTO 포털 오류는 JSON 요청에도 **XML 응답** — 컨버터 예외 잡아 오류 구분(NFR-4).
4. `serviceKey` 인코딩 — HTTP 클라이언트가 쿼리를 인코딩하면 **디코딩 키**, URL 직접 조립이면 인코딩 키. 혼용 = 인증 실패.
5. 좌표 순서 — 카카오·KTO REST는 `x=lng, y=lat` — 리모트 매퍼 단일 계층에서만 변환(NFR-8).
6. 카카오 로컬 제약 — `size≤15, page≤45, radius≤20000` · 429는 1회 재시도 후 폴백.
7. KTO 쿼터 — 개발키 오퍼레이션당 일 1,000건 → 서버 캐시(§0-5)로 절약, 운영키는 제출 직전 전환.
8. 웰니스 — data.go.kr **페어 키 전용**(구 hex 키 403) · 좌표 필드 **대문자** `mapX/mapY` · 이미지는 `firstimage`.
9. KorService1 금지 — HTTP 500(폐기).
10. 키 격리 🔒 — KTO·카카오 REST 키는 서버 환경변수 전용, 저장소·앱 포함 금지(NFR-14).
11. GraphHopper `round_trip`은 목표보다 길게 나올 수 있다 — 목표의 0.78배 요청+seed 16개 후보 필터를 생략하지 않는다(SPEC §5.8).
12. OSM way에는 고도가 거의 없다 — `graph.elevation.provider=srtm`과 영속 SRTM 캐시 없이 난이도를 계산하지 않는다.
13. 기본 `foot` 프로파일은 큰 도로 보도를 우선할 수 있다 — P0 운영은 러닝 가중치 `run` 프로파일만 사용한다.
14. OSM/GraphHopper는 서버 내부 원천이다 — 앱 직호출·OSM 그래프 번들을 금지하고 `© OpenStreetMap contributors`를 응답 출처에 포함한다.
15. `road_class` path detail의 `fromRef/toRef`는 응답 좌표 인덱스다 — `toRef-fromRef`를 차도 거리로 계산하지 말고 각 구간의 폴리라인 실거리를 합산한다. AP-25 착수 전 PR #32 `--preset filter`를 이 방식으로 재실행한다.
16. 품질 상한 미달 후보를 "가장 나은 후보"라는 이유로 반환하지 않는다. 적격 후보 0건은 정상 0건이며 GraphHopper 호출 장애와 구분한다.

## 부록 F. P1 예약 엔드포인트 (이번 명세 범위 밖 — 시그니처만 예약)

| API | 용도 |
|---|---|
| `GET /api/directions?points=` | 블록 간 이동시간 라벨(A5, 카카오모빌리티 — waypoints ≤5 · **자동차 전용, 도보 없음**) |
| `/api/runs/**` | GPS 기록·ran 목록(AP-22). saved/ran 통합 정렬·페이징은 P1 착수 시 결정 |
| (서버 불필요) 카톡 공유 A4 · 카카오내비 A6 | Android SDK 직접 — 백엔드 무관 |

## 부록 G. 구현 노트 (Java · Spring)

**G-1. 대회 목록·집계 Predicate 공유 + 불투명 커서 (QueryDSL)** — 점 표시와 목록 불일치 방지의 핵심.

```java
// ContestQueryRepository.java
private BooleanBuilder filterPredicate(ContestSearchCond cond) {
    BooleanBuilder builder = new BooleanBuilder();
    LocalDate today = LocalDate.now(clock.withZone(KST));
    builder.and(contest.contestDate.goe(today));                        // 오늘 이후 고정
    if (StringUtils.hasText(cond.q())) {
        builder.and(contest.name.contains(cond.q())
                .or(contest.place.contains(cond.q()))
                .or(contest.region.contains(cond.q())));
    }
    if (!CollectionUtils.isEmpty(cond.events())) {                      // 종목 OR
        builder.and(contest.id.in(
                JPAExpressions.select(contestEvent.contest.id).from(contestEvent)
                        .where(contestEvent.eventType.in(cond.events()))));
    }
    if (Boolean.TRUE.equals(cond.openOnly())) {                         // 접수상태 '파생' 조건
        builder.and(regStatusPredicate(today, RegStatus.OPEN));         // 응답 파생 함수와 동일 규칙
    }
    if (!CollectionUtils.isEmpty(cond.regions())) builder.and(contest.region.in(cond.regions()));
    if (cond.date() != null) builder.and(contest.contestDate.eq(cond.date()));
    return builder;
}

public List<Contest> findPage(ContestSearchCond cond, String cursor, int size) {
    BooleanBuilder where = filterPredicate(cond);                       // ← daily-counts와 동일 Predicate
    if (StringUtils.hasText(cursor)) {
        ContestCursor decoded = cursorCodec.decodeAndValidate(cursor);  // URL-safe Base64는 전송 형식일 뿐
        where.and(contest.contestDate.gt(decoded.date())
                .or(contest.contestDate.eq(decoded.date()).and(contest.id.gt(decoded.id()))));
    }
    return queryFactory.selectFrom(contest).where(where)
            .orderBy(contest.contestDate.asc(), contest.id.asc())
            .limit(size + 1)                                            // hasNext 판정용 +1
            .fetch();
}
```

`regStatusPredicate()`와 응답 DTO의 `deriveRegStatus()`는 하나의 도메인 정책 테스트를 공유한다. 특히 시작일·종료일 일부만 있는 경우와 원본 상태 폴백을 같은 테스트 벡터로 검증한다.

**G-2. 순서 변경 PUT — USER 집합 검증 + RACE 고정 (멱등)**

```java
// ItineraryBlockService.java
@Transactional
public void reorder(Long userId, Long dayId, List<Long> blockIds) {
    ItineraryDay day = dayRepository.findWithBlocksById(dayId)
            .orElseThrow(() -> new NotFoundException("DAY_NOT_FOUND"));
    day.validateOwner(userId);                                          // 403 FORBIDDEN

    Map<Long, ItineraryBlock> userBlocks = day.getBlocks().stream()
            .filter(block -> block.getBlockType() == BlockType.USER)
            .collect(Collectors.toMap(ItineraryBlock::getId, Function.identity()));
    if (userBlocks.size() != blockIds.size() || !userBlocks.keySet().equals(Set.copyOf(blockIds))) {
        throw new BadRequestException("BLOCK_SET_MISMATCH");            // USER 블록 전체 집합 강제
    }
    day.reorderUserBlocksWithoutMovingRace(blockIds)
            .orElseThrow(() -> new ConflictException("SYSTEM_BLOCK_IMMUTABLE"));
}
```

**G-3. 그 외 확정 구현 규약**
- `hibernate.default_batch_fetch_size=100` — 동선 트리 조회(5-5)의 N+1·MultipleBagFetchException 대응 🔒.
- 비밀번호·재설정 토큰·리프레시 토큰은 **전부 해시로만 저장**(BCrypt/SHA-256, NFR-9).
- 접수 상태·D-day 판정은 `Clock` 주입으로 테스트 가능하게(KST 고정, §6.6).
- springdoc: `@Tag`·`@Operation`은 본 문서의 절 제목·설명을 그대로 옮겨 Swagger UI가 이 명세와 1:1이 되게 한다(결정-18).
