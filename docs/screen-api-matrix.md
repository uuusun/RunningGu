# 런닝구 화면–API 매핑표 v1.10

> 갱신일: 2026-08-21
> 목적: 화면 플로우, Android Navigation, 백엔드 API, 데이터 원천과 저장 위치를 하나의 추적표로 연결한다.
> 화면 기준: `docs/mockup-design/shots/README.md`의 기본 화면·상태·오버레이 89개와 화면 간 커넥터
> 제품 기준: `SPEC.md` v4(SSOT)
> API 기준: `docs/files/런닝구_API_명세서.md` v2.11(시드 계약)

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
     └─ GraphHopper 내부 프로세스 ← 대한민국 OSM PBF + SRTM

기기 내부
  ├─ DataStore: 세션 토큰·게스트 여부·설정
  ├─ Room: 마지막 성공 응답의 읽기 캐시
  └─ 임시 상태: 필터·위저드·저장 전 동선·GPS 전송 전 기록
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
| `OSM_GRAPH` | 서버 내부 GraphHopper가 OSM+SRTM으로 순환 경로 생성 | 원천 그래프는 영속 캐시, 응답은 임시 DTO·사용자 저장 시 snapshot만 보관 |
| `LOCAL_STATE` | 화면·ViewModel 메모리 상태 | 저장하지 않음 |
| `LOCAL_CACHE` | Room·DataStore·임시 GPS | 제한적 로컬 보관 |
| `ANDROID_SDK` | 위치·지도·카카오 로그인 등 Android SDK | 기능별 최소 상태만 보관 |

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
| 로그인 필요 | 프로필·찜·동선/코스/러닝 기록 저장 및 보관함 조회 |
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
| S8 내 주변 통합 목록 | 큐레이션/OSM 코스 경로 또는 주변 공원·산책 장소 | `kind=ROUTE\|PLACE` items + degradedSources + attributions | `KTO_SYNC_GPX` + `OSM_GRAPH` + `KAKAO_LIVE` | 큐레이션 메타·경로와 GraphHopper 그래프 캐시, OSM 응답은 저장 전 임시 | GPX 축약 폴백·Room·서버 TTL 캐시 |
| S8 지역 코스 | 지역·코스 수·목록·출처 | regions + page + attributions | `KTO_SYNC_GPX` | 버전 번들에서 시작해 최신 전체 KTO 메타를 결합한 서버 메모리 snapshot | Room 읽기 캐시 |
| R1 GPS 기록 | timestamp, lat, lng, distance | 위치 point stream | `ANDROID_SDK` | 저장 전 없음 | 전송 전 임시 기록 |
| R2 러닝 요약 | 거리·시간·평균 페이스·경로 | local summary / run DTO | `LOCAL_STATE` + `SERVER_DB` | RUN·RUN_TRACK | 저장 전 임시 기록 |
| S10 보관함 | 동선·저장 코스·러닝 기록·찜 | Pageable 목록 | `SERVER_DB` | 사용자 소유 데이터 | Room 읽기 캐시 |
| M1 계정 관리 | 프로필·약관·가입 로그인 방식 | JSON DTO | `SERVER_DB` | USER·IDENTITY(1:1)·AGREEMENT | 세션만 DataStore |

---

## 2. 화면 route와 기본 흐름

### route 표

| ID | 플로우 화면 | 목표 route | 최신 Android | 전달값 | 로그인 | 상태 |
|---|---|---|---|---|---|---|
| A0 | 앱 시작·세션 확인 | 별도 route 없음(Startup Gate) | 시작 화면 `home`, 인증 그래프 없음 | 세션 | 선택 | 시스템 Splash + `core-splashscreen`, 세션에 따라 로그인/홈 |
| A1 | 로그인 | `login` | 미구현 | 복귀 목적 route | 불필요 | 플로우 확정 |
| A2 | 회원가입 4단계 | `signup` 내부 step | 미구현 | 카카오 신규면 SDK token/profile | 불필요 | 플로우 확정 |
| A3 | 비밀번호 찾기 | `reset` | 미구현 | 없음 | 불필요 | 플로우 확정 |
| WEB-R1 | 새 비밀번호 설정 | 웹 `/reset-password?token=` | Android route 아님 | reset token | 불필요 | SPEC 확정 |
| S1 | 홈 | `home` | `home` | 없음 | 선택 | 현재 구현 |
| S2 | 캘린더 | `calendar?q={q}` | `calendar?q={q}` | 선택 `q` | 선택 | 현재 구현 |
| S3 | 대회 상세 | `raceDetail/{raceId}` | 동일 | `raceId` | 선택 | 현재 구현 |
| S4 | 일정 선택 | `wizard/{raceId}` 그래프의 `plan` | 동일 | `raceId` | 불필요 | 현재 구현 |
| S5 | 종목·취향 | wizard 그래프의 `taste` | 미구현 | 공유 WizardUiState | 불필요 | 플로우 확정 |
| S6 | 숙소 선택 | wizard 그래프의 `stay` | 미구현 | 공유 WizardUiState | 불필요 | 플로우 확정 |
| S7 | 새 동선 결과 | wizard 그래프의 `result` | 미구현 | 생성 DTO | 저장 시 필요 | 플로우 확정 |
| S7-R | 저장 동선 상세 | `itinerary/{itineraryId}` 또는 result 재사용 | 미구현 | `itineraryId` | 필요 | **결정 필요** |
| S8 | 러닝코스 | `courses` | `courses` | 선택 출발지·목표 거리 | 선택 | 현재 구현 placeholder |
| S8-D | 코스 상세 | `courseDetail/near`, `courseDetail/saved/{savedCourseId}`, `courseDetail/ran/{runId}` | 미구현 | sealed `CourseDetailKey` | 조회별 상이 | near snapshot은 SavedStateHandle/그래프 상태, ran은 P1 |
| R1 | GPS 기록 | `run` | 미구현 | 코스 snapshot 또는 자유 러닝 | 저장 시 필요 | P1 |
| R2 | 러닝 요약 | `runSummary` | 미구현 | 임시 GPS 기록 | 저장 시 필요 | P1 |
| S9 | 커뮤니티 | 만들지 않음 | 없음 | 해당 없음 | 해당 없음 | 범위 제외 |
| S10 | 보관함 | 내부 `my`, UI 라벨 `보관함` | `my` placeholder | 선택 segment | 필요 | route 현재 구현 |
| M1 | 내 정보·계정 관리 | `account` 제안 | 미구현 | 없음 | 필요 | 플로우 확정 |

`현재 구현`은 route와 UI 존재 여부를 뜻하며 백엔드 연결 완료를 뜻하지 않는다. 최신 `develop`의 S1~S4는 아직 `SampleData`·화면 상태를 사용하는 구간이 있고, 이 표의 API는 Repository/Retrofit 연결 목표 계약이다.

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

홈 → 러닝코스 → 코스 상세 ── P1 → GPS 기록 → 러닝 요약 → 보관함[러닝코스]
보관함 ─┬─ 동선 카드 → 저장 동선 상세
        ├─ saved 카드 → 코스 상세 (ran은 P1)
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
| 토큰 재발급 | `POST /api/auth/refresh` | refreshToken | 회전된 token pair | 실패→세션 삭제·로그인 | 서버 hash + DataStore |
| 게스트 저장·찜 차단 | Android guard | 원래 route와 동작 종류 | 로그인 모달 | 로그인 후 원래 화면 복귀, **자동 실행하지 않음** | route 임시 상태 |
| 공통 API 오류 | Problem Details parser | status, code | 화면별 Error | Empty로 강등 금지 | 저장 없음 |
| 오프라인 읽기 | Room | 마지막 성공 DTO, cachedAt | 읽기 전용 표시 | 쓰기 비활성 | 기기 캐시 |

### A1 로그인

| 행동 | API/SDK | 요청 | 응답 | 성공 | 실패 |
|---|---|---|---|---|---|
| 이메일 로그인 | `POST /api/auth/login` | email, password | token pair + user | pending route가 있으면 복귀, 없으면 홈 | `LOGIN_FAILED` 인라인 오류 |
| 카카오 시작 | Kakao Android SDK | 없음 | kakaoAccessToken | 다음 API 호출 | SDK 취소/오류 |
| 카카오 계정 확인 | `POST /api/auth/kakao` | kakaoAccessToken | 기존 token+user / 신규 isNewUser+profile | 기존 로그인 / 신규 회원가입 | `INVALID_KAKAO_TOKEN` |
| 게스트 둘러보기 | 로컬 | guest=true | 없음 | 홈 | 없음 |
| 회원가입·비밀번호 찾기 | Navigation | 없음 | 없음 | A2/A3 | 없음 |

### A2 회원가입

| 단계/행동 | 처리/API | 입력 | 응답·다음 상태 | 저장 |
|---|---|---|---|---|
| 약관 동의 | 로컬 | tos, privacy, marketing | 필수 2종 동의 시 다음 활성화 | 가입 완료 전 로컬 |
| 정보 입력·검증 | 로컬 + 중복 API | email, password, confirm, nickname | exists/validation | 가입 완료 전 로컬 |
| 이메일·닉네임 중복 | `GET /api/auth/email/exists`, `GET /api/auth/nickname/exists` | query | `Unchecked/Checking/Available/Duplicate/Error`; 입력 변경 시 이전 응답 무효화 | 없음 |
| 코드 발송 | `POST /api/auth/email/send-code` | email | 204, 60초 타이머 | 서버 검증 상태 |
| 코드 확인 | `POST /api/auth/email/verify` | email, code | verified | 서버 검증 상태 |
| 이메일 가입 | `POST /api/auth/signup` | email, password, nickname, agreements | token pair + user | PostgreSQL |
| 카카오 신규 가입 | `POST /api/auth/kakao/signup` | kakaoAccessToken, nickname, agreements | token pair + user | PostgreSQL |
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
| 달력·러닝코스·관광 아이콘 | Navigation/scroll | 없음 | 없음 | LOCAL_STATE | 관광은 축제 영역 스크롤 |
| 히어로·대회 카드 | 로컬 선택 | contestId | 카드 DTO | SERVER_DB/Room | 선택→S3, CTA→S4 |
| 마감 임박 | `GET /api/contests/closing-soon` | limit=4 | 카드 필드(`regStatus`, nullable `applyStart/applyEnd` 포함), dDayApply, favorite | SERVER_DB/Room | 영역별 Loading/Empty/Error |
| 홈 축제 | `GET /api/festivals` | yearMonth(`YYYY-MM`, 기본 KST 이번 달), size(기본 6·1~20) | contentId, name, 기간, region(17개 시도 단축명 또는 `""`), imageUrl, inProgress | KTO_LIVE/5분 TTL cache | 전국 월간, 위치 권한 없음, `addr1` 지역 판별 불가 항목도 `region: ""`으로 유지, 영역별 Loading/Empty/502/504. **P0 표시 전용 — 카드 탭·상세 route 없음**(D-05). 추적 메타데이터(fetchedAt/cachedAt)는 응답에 없다(서버 내부 운영 정보) |
| 축제 카드 | P0 표시 전용 | 없음 | 없음 | 없음 | 플로우에 상세 이동 없음 |
| 오프라인 | Room | cachedAt | 마지막 성공 대회·축제 | LOCAL_CACHE | 새로고침/쓰기 제한 |

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
| 공유 | Android 공유 | 대회 요약·URL | 저장 없음 | P1/AP-17 |
| 동선 만들기 | S4 이동 | contestId | WizardUiState | 좌표 null 또는 active=false면 CTA 비활성, 좌표 전용 안내 UX는 P1 |

---

## 5. 여행 동선 위저드·결과 매핑

### S4 일정 선택

| 행동 | 처리 | 데이터 | 검증·상태 |
|---|---|---|---|
| 대회 복원 | 공유 WizardViewModel, 없으면 상세 재조회 | contestId, name, contestDate | 조회 실패 시 이전 화면 |
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
| 동선 생성(서버 단일 주체) | `POST /api/itineraries/generate` | contestId, start/end(대회일 포함·최대 7일), event, themes, hotel? | recovery, days[], blocks[] | 비활성은 생성 차단(status/code는 #56 추가 리뷰 대기). 200 `days=[]`은 S7 Empty, 네트워크·timeout·4xx/5xx는 Error |

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
| 저장 동선 복원 | `GET /api/itineraries/{id}` | snapshot region/recovery/tree + 최신 contest 메타·active + needsRegeneration | Room cache | RACE는 저장 당시 값 유지. 변경 시 안내/재생성 CTA, 403/404/Error |
| 변경 대회 재생성 | `POST /api/itineraries/generate` | 최신 canonical 기준 입력 | 저장 전 임시 DTO | "직접 고친 장소는 사라져요" 확인 뒤 호출, 기존 저장본 유지 |
| 재생성 최종 교체 | `PUT /api/itineraries/{id}` | 새 편집 DTO→200 same id/replaced | PostgreSQL | 저장 성공 시에만 기존 트리 교체, USER 편집 자동 병합 없음 |
| 저장 후 추가 | POST `/itineraries/{id}/days/{dayId}/blocks` | block body→blockId/orderNo | PostgreSQL | 실패 시 기존 UI 유지 |
| 저장 후 수정 | PATCH `.../blocks/{blockId}` | 변경 필드→200 갱신 block 전체 | PostgreSQL | 응답 block으로 해당 항목 교체 |
| 저장 후 삭제 | DELETE `.../blocks/{blockId}` | 204 | PostgreSQL | RACE는 409 |
| 저장 후 순서 | PUT `.../blocks/order` | 전체 USER blockIds→200 해당 일자 전체 blocks | PostgreSQL | 응답 blocks로 일자 상태 교체, set mismatch/409 |
| Empty 조건 수정 | 위저드 복귀 | 기존 WizardUiState | 없음 | 입력 유지 후 조건 수정 |
| Error 재시도 | 같은 generate 재호출 | 기존 요청 | 없음 | 기존 결과·입력 유지 |
| 숙소 주변에서 뛰기 | S8 이동 | `CourseLaunchContext(startLat,startLng,startName,targetKm=min(walk,5))` | LOCAL_STATE | 종목·난이도는 전달하지 않음; SavedStateHandle/그래프 상태, 좌표를 route 문자열에 넣지 않음 |

---

## 6. 러닝코스·GPS 매핑

### S8 내 주변·지역별

| 행동 | API/로컬 | 요청 | 응답·원천 | 상태 |
|---|---|---|---|---|
| 내 위치 | FusedLocationProvider | permission, 6초 timeout | lat/lng | locating/거부/timeout |
| 출발지 검색 | `GET /api/geocode` | query | name,address,lat,lng / KAKAO_LIVE | `NO_RESULT` |
| 프리셋 | 앱 상수 | 5개 좌표 | start point | API 없음 |
| 거리 슬라이더 | 로컬 | 1~21km, 0.5 단위 | targetKm | 드래그 종료 후 조회 권장 |
| 난이도 표시 | 서버 응답 | 입력 없음 | ROUTE `difficulty`, `gainM`, 고도 스트립 (`difficultyBasis` 없음) | 내 주변 카드는 생성 왕복 구간 기준 `EASY\|NORMAL` 배지 옆에 **"이 구간 기준"** 표시. 지역별 목록은 전체 원본 코스 등급 배지만 기존대로 표시하고 보조 문구를 붙이지 않으며 `HARD` 허용. P0 내 주변 난이도 칩 없음 |
| 근처 경로·장소 통합 목록 | `GET /api/courses/near` | lat,lng,targetKm,radiusKm=8,size=12 | ROUTE `routeId,dataSource,difficulty,routeKm,durationMin,gainM,elevationProfileM,pathPolyline` + PLACE + degradedSources + attributions | 목표 거리·상승 상한에 맞는 큐레이션 0건이면 거리·상승·차도·회전 상한을 통과한 OSM 최대 1건 생성 후 거리순 통합 |
| OSM 품질 상한 | 서버 내부 | seed 0~15 | 거리 75~125%·상승 <50m/km·실거리 차도 ≤10%·실제 회전 ≤6회/km | 하나라도 초과하면 후보 제외, 상한 완화 금지; AP-25 전 차도 거리 가중 PoC 재검증 |
| 부분 실패 | 같은 near 응답 | 없음 | items 비어 있지 않음 + degradedSources | 호출 실패만 Content+비차단 안내; 품질 상한 통과 후보 0건은 정상 결과 |
| 전체 Empty/Error | 같은 near 응답 | 없음 | 모든 원천 정상+items=[] / 원천 실패+표시 항목 없음 | 전자는 Empty, 후자는 `503 COURSE_SOURCES_UNAVAILABLE` Error |
| 지역 칩 | `GET /api/courses/regions` | 없음 | `count DESC, region ASC`의 region,count | KTO 동기화 실패는 번들/마지막 정상 snapshot으로 200 유지. catalog 자체가 없을 때만 Error |
| 지역 목록 | `GET /api/courses` | region?,page,size | `distanceKm ASC, courseId ASC` 큐레이션 page + nullable syncedAt + 현재 `content[]`의 `attributions[]`(OSM 미포함) | 지역 0건 Empty. 번들 fallback·GPX_ONLY의 syncedAt=null, 출처는 완성 문구를 `" · "`로 연결 |
| 코스 저장 | `POST /api/me/courses` | sourceCourseId?,dataSource,경로·고도 snapshot | 신규 201 / fingerprint 중복 200 기존 id | OSM도 저장 가능, 서버 생성 `name`을 snapshot에 보존하고 routeFingerprint 재계산, 게스트 modal |
| 코스 선택 | 상세 이동 | sealed `CourseDetailKey.Near/Saved/Ran` | LOCAL_STATE | near snapshot은 route 문자열에 넣지 않음 |

### S8-D 코스 상세

| 종류 | 조회 | 필요한 필드 | 행동 |
|---|---|---|---|
| near `ROUTE` 항목 | `courseDetail/near` + 이전 통합 목록 snapshot | routeId,dataSource,pathPolyline,routeKm,durationMin,difficulty,gainM,elevationProfileM,lat,lng | 저장(P0)·뛰기(P1) |
| saved 저장 코스 | `courseDetail/saved/{savedCourseId}` + `GET /api/me/courses/{id}` | 목록 필드 + pathPolyline + attributions[] | 출처 완성 문구를 `" · "`로 연결, 삭제 확인(P0)·뛰기(P1) |
| ran 러닝 기록(P1) | `courseDetail/ran/{runId}` + `GET /api/runs/{id}` | ranAt, distanceKm, durationSec, avgPaceSec, encodedPolyline, pointCount | 삭제 확인 |

`CourseDetailKey`는 `Near(snapshot)`, `Saved(savedCourseId)`, `Ran(runId)`의 sealed 타입이다. NEAR snapshot은 URL에 직렬화하지 않고 `SavedStateHandle` 또는 내비게이션 그래프 범위 상태로 전달한다.

### R1 GPS·R2 요약

| 행동 | 처리/API | 데이터 | 저장·상태 |
|---|---|---|---|
| 기록 시작 | Foreground Service + location | courseName?, start point | 로컬 임시 기록 |
| 위치 누적 | 로컬 5m filter | timestamp,lat,lng | polyline·거리·시간 갱신 |
| 종료 | 로컬 | points,duration,distance | R2 임시 요약 |
| 저장 | `POST /api/runs` | courseName?,ranAt,distanceKm,durationSec,points | RUN·RUN_TRACK, id·avgPaceSec |
| 저장 전 취소 | 확인 modal | 없음 | 임시 기록 삭제 |
| 저장 기록 삭제 | `DELETE /api/runs/{id}` | id | 204, 확인 modal |

GPS 기록·요약과 `ran` 상세는 P1(AP-22)이다. P0 구현 범위에는 포함하지 않는다(D-25).

---

## 7. 보관함·계정 관리 매핑

### S10 보관함

| segment/행동 | API | 응답에서 쓰는 값 | 상태 |
|---|---|---|---|
| 프로필 | `GET /api/me` | id,email(`string|null`),nickname,loginProvider,agreements | `email=null`이면 이메일 행·placeholder 없이 숨김. 401/Error |
| 동선 목록 | `GET /api/itineraries` | id,title,region(snapshot),contestName(current),event,recovery,기간,placeCount,active,needsRegeneration,createdAt | 변경 시 "대회 변경" 배지, 비활성도 유지, Loading/Empty/Error/offline |
| 동선 열기 | `GET /api/itineraries/{id}` | snapshot 트리 + current contest + needsRegeneration | S7-R, 변경 안내·재생성 CTA |
| 동선 삭제 | `DELETE /api/itineraries/{id}` | 204 | 확인 modal, 실패 시 유지 |
| 저장 코스(P0) | `GET /api/me/courses` | saved projection | Loading/Empty/Error |
| 러닝 기록(P1) | `GET /api/runs` | ran summary | AP-22 착수 후 구현 |
| saved/ran 통합(P1) | 결정 보류 | 정렬·페이징 계약 | GPS P1 착수 시 D-21 재논의 |
| 코스/기록 삭제 | 각 DELETE | 204 | 종류별 확인 modal |
| 찜 목록 | `GET /api/me/favorites` | 대회 카드 page, favorite=true, active | 비활성 흐림+"정보 제공 종료", Empty/Error |
| 찜 해제 | DELETE `/api/me/favorites/{contestId}` | 204 | 실패 시 카드 유지 |
| 설정 | account 이동 | 없음 | M1 |
| 오프라인 | Room | 마지막 성공 segment | 읽기만, 수정 비활성 |

### M1 내 정보·계정 관리

| 행동 | API/SDK | 요청 | 성공·실패 |
|---|---|---|---|
| 닉네임 변경 | `PATCH /api/me` | nickname | 응답 계약 보완 필요, duplicated 처리 |
| 마케팅 동의 | `PATCH /api/me/agreements` | marketing | 응답 계약 보완 필요 |
| 비밀번호 변경 | `PUT /api/me/password` | currentPassword,newPassword | EMAIL 수단에만 메뉴 노출, 200 새 token pair로 원자 교체 |
| 가입 로그인 방식 | `GET /api/me` | 없음 | `loginProvider`. EMAIL만 비밀번호 메뉴 노출, P0 연결·해제·전환 없음 |
| 로그아웃 | `POST /api/auth/logout` | refreshToken | 세션 삭제→로그인 |
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
| 러닝코스·GPS | 30~34 | 6장 코스·기록·요약 |
| 보관함 | 40~42 | 7장 동선·코스·찜 segment |
| 디자인 시스템 | 50 | API·DB 없음, Compose theme/token 기준표 |

### 상태·오버레이 캡처

| 그룹 | 캡처 | 데이터·처리 매핑 |
|---|---|---|
| 게스트 로그인 유도 | 72,73,74,75 | 찜·동선·코스 저장을 API 전에 차단, 로그인 후 원래 route 복귀, 자동 재실행 없음 |
| 삭제·탈퇴 확인 | 100~104 | modal은 로컬, 확인 시에만 DELETE 또는 임시 기록 폐기 |
| 회원가입·재설정 조작 | 80~83 | 로컬 폼 상태 + 인증/재설정 API 결과 |
| 캘린더 조작 | 84~88 | 같은 contests/daily-counts API에 date/month/filter/q 적용 |
| 위저드 조작 | 89~92 | WizardUiState + POI 조회 + generate 진행 상태 |
| 동선 결과 변형 | 93~95 | 같은 generate DTO의 day 선택·회복일·로컬 편집 |
| 코스 조작 | 96~97 | region API 또는 targetKm 변경 후 near 재조회 |
| 대회 상태 | 98~99 | 서버 파생 regStatus에 따른 CTA·배지 변화 |
| 기본 예외 상태 | 60~71 | 로그인 오류, 축제/숙소 로딩, 동선 Empty, POI 교체, 보관함 Empty, ran 상세, 코스 위치/Empty |
| 홈 영역별 상태 | 110~116 | closing-soon과 festivals를 독립 Loading/Empty/Error로 관리, offline은 Room |
| 캘린더 부분 실패 | 120~125 | 목록 실패와 daily-counts 실패를 분리, 검색/day/month Empty 구분 |
| 보관함 부분 실패 | 130~133 | 동선·코스·찜 segment 오류 분리, offline은 읽기 전용 |
| 서울 코스 기본 상태 | 140 교체 필요 | 목표 거리·상승 상한 적격 큐레이션 0건 → 품질 상한 OSM 생성 경로+Kakao 장소가 정상 Content. GraphHopper 실패 변형은 장소 Content+비차단 안내 |

### 확인된 플로우 연결

- 보관함 동선→S7-R, P0 saved→코스 상세, 찜→S3가 연결돼 있다. ran 상세는 P1이다.
- 코스 저장→보관함 saved가 연결돼 있다. 러닝 저장→보관함 ran 연결은 P1이다.
- 보관함 설정→계정 관리, 계정 관리→비밀번호 변경·로그아웃·탈퇴가 연결돼 있다.
- 홈·캘린더·보관함은 로딩·빈·오류·부분 실패 상태를 가진다.
- 삭제·탈퇴·저장 전 러닝 취소에는 확인 modal이 있다.
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
| F-01~03 | 보관함의 동선·P0 saved·찜 카드에서 각 상세로 이동. ran은 P1 |
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
| D-18(개정) | 서버가 geometry 좌표열만 정규화해 `v1:`+SHA-256 `routeFingerprint`를 계산하고 사용자별 멱등 저장. 진행 반대는 별개, 연속 중복점 제거, 신규 201·중복 200 기존 id. 좌표 정밀도는 TBD |
| D-20 | `courseDetail/{type}/{id}` 폐기. sealed CourseDetailKey + near/saved/ran 분리 route |
| D-22 | 내 정보·계정 관리는 보관함 설정에서 여는 별도 화면 |
| D-23 | 탈퇴 전 EMAIL/KAKAO 재인증으로 5분 token 발급, DELETE 성공 후 모든 세션·캐시 삭제 |
| D-24 | 대회 imageUrl은 P0 nullable, null이면 placeholder |
| D-25 | GPS 기록·ran은 P1, P0 보관함은 saved만 구현 |
| D-26 | 별도 splash route 없이 시스템 Splash + core-splashscreen + Startup Gate |
| D-28 | EMAIL 수단에만 Android 비밀번호 변경 메뉴 노출, 변경 성공 시 전 refresh revoke 후 현재 기기 token pair 재발급 |
| D-29(개정) | USER:LOGIN_IDENTITY는 1:1. 가입 시 EMAIL/KAKAO 중 하나만 선택하고 P0 연결·추가·해제·전환 API를 두지 않음. `GET /me.email`은 항상 포함하는 `string|null`이며 KAKAO 이메일 미제공 시 null, 별도 이메일 입력·인증 없음 |
| D-30 / SPEC 결정-50 | 이메일·닉네임 중복 확인은 모두 P0에서 호출한다. `Checking` 동안 인증 메일 발송을 막고 `Available`에서 허용하며, `Duplicate`만 확정 차단한다. `Unchecked`·네트워크/`RATE_LIMITED` `Error`는 발송·가입의 서버 유니크 방어를 믿고 진행을 막지 않는다. 입력 변경 시 결과를 즉시 무효화하고 늦은 응답은 버린다 |
| DB-02 / SPEC 결정-44 | 저장 코스 attribution은 서버 생성 완성 문구 배열을 `JSONB NOT NULL DEFAULT '[]'` snapshot으로 보존. 상세에만 반환하고 목록·fingerprint에서 제외하며 문구 변경을 소급하지 않음. `GET /api/courses`도 실제 응답 코스 원천의 `attributions[]` 반환 |
| DB-04 / SPEC 결정-45 | 저장 동선은 region·recovery·전체 트리와 RACE를 snapshot으로 보존. contestName·현재 대회 메타는 조회 시 파생하고 일정·시간·장소·지역·좌표 변경만 needsRegeneration=true. 재생성 최종 저장은 `PUT /itineraries/{id}`로 같은 id 교체 |
| DB-05 / SPEC 결정-46 | 승인된 full snapshot에서 source 2회 연속 누락 시 비활성. 실패·부분 snapshot은 미반영, 재등장은 즉시 복구, canonical은 활성 source가 없을 때만 비활성. 공개 탐색 제외·참조 상세 유지 |
| SPEC 결정-48 | 다중 원천은 정상 수용. 최대 source 겹침 동률 또는 기존 canonical 하나를 둘 이상의 새 canonical이 승계하려는 충돌이면 Importer가 snapshot 전체를 거부하고 기존 참조·누락 상태·적용 이력을 유지 |
| SPEC 결정-41 | 새 동선은 백엔드 `POST /itineraries/generate`가 단독 생성. 앱 엔진은 운영 화면에 연결하지 않음 |
| SPEC 결정-42(08-19 개정) | OSM/GraphHopper 도시 경로 생성을 P0에 포함. 서버 내부 별도 프로세스, 적격 큐레이션 0건 fallback 1건. 난이도 칩·EventType 기본값은 제거하고 HARD·거리·실거리 차도·실제 회전 상한을 서버가 강제 |

### P1 착수 시 재논의

| ID | 결정할 내용 | 영향 | 주인 |
|---|---|---|---|
| D-21 | saved/ran 통합 정렬·페이징 | 보관함 목록 계약 | 앱+백엔드 |

P0 화면·기능의 제품 결정은 모두 닫혔다. `DB-04·05`는 결정-45·46으로, 대회 snapshot 승계 충돌은 결정-48로 확정됐다. 남은 `TBD-DB-01`은 fingerprint 좌표 정밀도와 고도 배열 저장 타입이며, D-21은 GPS 기록(AP-22) P1 착수 시 실제 `ran` 목록 요구사항을 기준으로 결정한다. 비활성 대회 생성 차단의 정확한 HTTP status·오류 `code`만 이슈 #56 추가 리뷰 후 API 명세에 보완한다.

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
| `GET /api/runs` | P1 ran 목록 요약의 정확한 필드와 page 예시 |
| `PATCH /api/me` | 성공 status와 응답 body |
| `PATCH /api/me/agreements` | 성공 status와 응답 body |

---

## 12. 구현 순서와 완료 조건

### 세로 기능 순서

1. S1 홈 → S2 캘린더 → S3 상세
2. S3 상세 → S4~S7 위저드·결과·저장
3. S8 코스 → S8-D 상세 → R1/R2 기록
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
