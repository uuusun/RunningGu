# 런닝구 화면–API 매핑표 v1.0

> 갱신일: 2026-08-17
> 목적: 화면 플로우, Android Navigation, 백엔드 API, 데이터 원천과 저장 위치를 하나의 추적표로 연결한다.
> 화면 기준: `docs/mockup-design/shots/README.md`의 기본 화면·상태·오버레이 89개와 화면 간 커넥터
> 제품 기준: `SPEC.md` v4(SSOT)
> API 기준: `docs/files/런닝구_API_명세서.md` v2.0(시드 계약)

이 문서에서 **화면 커버리지 완료**는 플로우의 모든 화면·상태·행동에 API 또는 로컬 처리 주체가 연결됐다는 뜻이다. API 응답이나 정책이 아직 합의되지 않은 항목은 임의로 확정하지 않고 10장의 결정 목록에 남긴다.

## 0. 읽는 법과 기준

Android 화면은 PostgreSQL이나 한국관광공사 API에 직접 접근하지 않는다.

```text
Compose 화면
  → ViewModel / Repository
  → 우리 백엔드 /api (JSON)
     ├─ PostgreSQL 서버 SSOT
     ├─ 한국관광공사·두루누비 REST API
     └─ 카카오 REST API

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
| `KTO_SYNC_GPX` | 두루누비 최신 메타 동기화 + GPX 경로 결합 | 마지막 성공 메타·GPX 기준 데이터 보관 |
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
| A1 로그인 | token, user, identities | JSON DTO | `SERVER_DB` / 카카오 SDK | USER·LOGIN_IDENTITY·토큰 해시 | DataStore token |
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
| S8 내 주변 코스 | 코스 경로·난이도·거리 | course items | `KTO_SYNC_GPX` | 최신 메타·경로 기준 데이터 | GPX 축약 폴백·Room |
| S8 걷기 좋은 곳 | 주변 공원·산책 장소 | walk-spot items | `KAKAO_LIVE` | 영구 저장 없음 | 서버 TTL 캐시 |
| S8 지역 코스 | 지역·코스 수·목록 | regions + page | `KTO_SYNC_GPX` | 최신 메타·경로 기준 데이터 | Room 읽기 캐시 |
| R1 GPS 기록 | timestamp, lat, lng, distance | 위치 point stream | `ANDROID_SDK` | 저장 전 없음 | 전송 전 임시 기록 |
| R2 러닝 요약 | 거리·시간·평균 페이스·경로 | local summary / run DTO | `LOCAL_STATE` + `SERVER_DB` | RUN·RUN_TRACK | 저장 전 임시 기록 |
| S10 보관함 | 동선·저장 코스·러닝 기록·찜 | Pageable 목록 | `SERVER_DB` | 사용자 소유 데이터 | Room 읽기 캐시 |
| M1 계정 관리 | 프로필·약관·로그인 수단 | JSON DTO | `SERVER_DB` | USER·IDENTITY·AGREEMENT | 세션만 DataStore |

---

## 2. 화면 route와 기본 흐름

### route 표

| ID | 플로우 화면 | 목표 route | 최신 Android | 전달값 | 로그인 | 상태 |
|---|---|---|---|---|---|---|
| A0 | 앱 시작·세션 확인 | 시작 로직 또는 `splash` | 시작 화면 `home`, 인증 그래프 없음 | 없음 | 선택 | **결정 필요** |
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
| S8-D | 코스 상세 | `courseDetail/{type}/{id}` 제안 | 미구현 | `near/saved/ran`, id | 조회별 상이 | **결정 필요** |
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

홈 → 러닝코스 → 코스 상세 → GPS 기록 → 러닝 요약 → 보관함[러닝코스]
보관함 ─┬─ 동선 카드 → 저장 동선 상세
        ├─ saved/ran 카드 → 코스 상세
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
| 이메일·닉네임 중복 | `GET /api/auth/email/exists`, `GET /api/auth/nickname/exists` | query | exists | 없음 |
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
| 마감 임박 | `GET /api/contests/closing-soon` | limit | 카드 필드, dDayApply, favorite | SERVER_DB/Room | 영역별 Loading/Empty/Error |
| 홈 축제 | `GET /api/festivals` | yearMonth, 선택 lat/lng, size | contentId, name, 기간, region, imageUrl, inProgress, distanceKm? | KTO_LIVE/TTL cache | 영역별 Loading/Empty/502/504 |
| 축제 카드 | P0 표시 전용 | 없음 | 없음 | 없음 | 플로우에 상세 이동 없음 |
| 오프라인 | Room | cachedAt | 마지막 성공 대회·축제 | LOCAL_CACHE | 새로고침/쓰기 제한 |

마감 임박 개수는 SPEC 6건과 API·목업 4건이 다르므로 D-03 확정 전 숫자를 하드코딩하지 않는다.

### S2 캘린더

| UI/행동 | API/로컬 | 요청 | 응답 | 상태·부분 실패 |
|---|---|---|---|---|
| 목록·검색·필터·선택일 | `GET /api/contests` | q, events[], openOnly, regions[], date?, cursor?, size | items, nextCursor, hasNext | 정상 0건은 원인별 Empty, 오류는 Error |
| 검색 입력 | 서버 q 검색 | q | 대회 목록 | 디바운스 시간은 Android 구현 정책 |
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
| 상세 본문 | `GET /api/contests/{contestId}` | 카드 필드, applyStart, organizer, officialUrl, lat, lng, dDay, favorite | SERVER_DB/Room | Loading/Content/Error/`CONTEST_NOT_FOUND` |
| 찜 | S2와 같은 PUT/DELETE | 204 | SERVER_DB | 게스트 modal, 실패 원복 |
| 인근 축제 | `GET /api/contests/{contestId}/festivals` | contentId, name, 기간, distanceKm, imageUrl, address | KTO_LIVE/서버 1일 cache | 본문과 독립 Loading/Empty/502/504 |
| 공식 페이지 | Custom Tabs | officialUrl | 외부 웹 | null이면 버튼 숨김 |
| 공유 | Android 공유 | 대회 요약·URL | 저장 없음 | P1/AP-17 |
| 동선 만들기 | S4 이동 | contestId | WizardUiState | 좌표 없음 정책은 D-09 |

---

## 5. 여행 동선 위저드·결과 매핑

### S4 일정 선택

| 행동 | 처리 | 데이터 | 검증·상태 |
|---|---|---|---|
| 대회 복원 | 공유 WizardViewModel, 없으면 상세 재조회 | contestId, name, contestDate | 조회 실패 시 이전 화면 |
| 패턴 선택 | 순수 도메인 규칙 | PRE/POST/AROUND/DAY/CUSTOM | SPEC §5.2 값 변경 금지 |
| 직접 날짜 선택 | 로컬 | startDate, endDate | 역순 자동 정렬, 최대 기간 D-10 |
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
| 숙소 최초 조회 | `GET /api/pois` | category=LODGING, 대회 lat/lng, radius, size=8 | POI items, 카카오 AD5 우선·KTO 32 폴백 | Loading/Empty/502/504 |
| 숙소 검색 | 같은 API query | query + 기준 좌표 | POI items | 호출 시점 D-12 |
| 숙소 선택/해제 | 로컬 | hotel DTO/null | WizardUiState | picked 상태 |
| 동선 생성 | `POST /api/itineraries/generate` | contestId, start/end, event, themes, hotel? | recovery, days[], blocks[] | 생성 중 중복 방지, 실패 시 S7 Empty |

`generate` 응답은 DB에 저장하지 않는 임시 DTO다. KTO·카카오 POI 실패는 해당 place를 null로 낮추되 전체 동선 생성은 성공시키는 것이 SPEC 계약이다.

### S7 결과·편집·저장

| 행동 | API/로컬 | 요청/응답 | 저장 | 상태 |
|---|---|---|---|---|
| 새 결과 표시 | 로컬 | generate DTO | 저장 전 없음 | 날짜 탭, 지도 핀, 회복 배지 |
| POI 후보 | `GET /api/pois` | category, 기준 좌표, query?, size | 영구 저장 없음 | 시트 Loading/Empty/Error |
| 저장 전 편집 | 로컬 immutable 연산 | USER 블록 추가/교체/삭제/순서 | ResultUiState | RACE 편집 UI 미노출 |
| 새 동선 저장 | `POST /api/itineraries` | 편집된 전체 DTO→201 id 또는 200 replaced | PostgreSQL | 게스트 modal, 성공→보관함 |
| 저장 동선 복원 | `GET /api/itineraries/{id}` | id/dayId/blockId/orderNo 포함 상세 | Room cache | 403/404/Error |
| 저장 후 추가 | POST `/itineraries/{id}/days/{dayId}/blocks` | block body→blockId/orderNo | PostgreSQL | 실패 시 기존 UI 유지 |
| 저장 후 수정 | PATCH `.../blocks/{blockId}` | 변경 필드 | PostgreSQL | 응답 계약 D-14 |
| 저장 후 삭제 | DELETE `.../blocks/{blockId}` | 204 | PostgreSQL | RACE는 409 |
| 저장 후 순서 | PUT `.../blocks/order` | 전체 USER blockIds | PostgreSQL | D-14, set mismatch/409 |
| 빈 결과 재추천 | generate 재시도 또는 위저드 복귀 | 기존 WizardUiState | 없음 | 동작 D-16 |

---

## 6. 러닝코스·GPS 매핑

### S8 내 주변·지역별

| 행동 | API/로컬 | 요청 | 응답·원천 | 상태 |
|---|---|---|---|---|
| 내 위치 | FusedLocationProvider | permission, 6초 timeout | lat/lng | locating/거부/timeout |
| 출발지 검색 | `GET /api/geocode` | query | name,address,lat,lng / KAKAO_LIVE | `NO_RESULT` |
| 프리셋 | 앱 상수 | 5개 좌표 | start point | API 없음 |
| 거리 슬라이더 | 로컬 | 1~21km, 0.5 단위 | targetKm | 드래그 종료 후 조회 권장 |
| 주변 코스 | `GET /api/courses/near` | lat,lng,targetKm,radiusKm=8,size=12 | route/path/difficulty/dataSource/syncedAt / KTO_SYNC_GPX | 코스 0이면 walk spot 중심 |
| 걷기 좋은 곳 | `GET /api/walk-spots` | lat,lng,size=12 | name,category,address,lat,lng,distanceM,url / KAKAO_LIVE | 코스·스팟 조합별 Empty |
| 지역 칩 | `GET /api/courses/regions` | 없음 | region,count | 실패 시 Error |
| 지역 목록 | `GET /api/courses` | region?,page,size | course page | 지역 0건 Empty |
| 코스 저장 | `POST /api/me/courses` | course snapshot | id / SERVER_DB | 게스트 modal, 중복 D-18 |
| 코스 선택 | 상세 이동 | type,id 또는 snapshot | LOCAL_STATE | route D-20 |

### S8-D 코스 상세

| 종류 | 조회 | 필요한 필드 | 행동 |
|---|---|---|---|
| near 추천 코스 | 이전 목록 snapshot | pathPolyline, 거리, 시간, 난이도, entry | 저장·뛰기 |
| saved 저장 코스 | `GET /api/me/courses/{id}` | 목록 필드 + pathPolyline | 삭제 확인·뛰기 |
| ran 러닝 기록 | `GET /api/runs/{id}` | ranAt, distanceKm, durationSec, avgPaceSec, encodedPolyline, pointCount | 삭제 확인 |

### R1 GPS·R2 요약

| 행동 | 처리/API | 데이터 | 저장·상태 |
|---|---|---|---|
| 기록 시작 | Foreground Service + location | courseName?, start point | 로컬 임시 기록 |
| 위치 누적 | 로컬 5m filter | timestamp,lat,lng | polyline·거리·시간 갱신 |
| 종료 | 로컬 | points,duration,distance | R2 임시 요약 |
| 저장 | `POST /api/runs` | courseName?,ranAt,distanceKm,durationSec,points | RUN·RUN_TRACK, id·avgPaceSec |
| 저장 전 취소 | 확인 modal | 없음 | 임시 기록 삭제 |
| 저장 기록 삭제 | `DELETE /api/runs/{id}` | id | 204, 확인 modal |

GPS 기록은 SPEC에 확정돼 있지만 구현 일정은 P1(AP-22)이므로 P0 포함 여부는 D-25에서 확정한다.

---

## 7. 보관함·계정 관리 매핑

### S10 보관함

| segment/행동 | API | 응답에서 쓰는 값 | 상태 |
|---|---|---|---|
| 프로필 | `GET /api/me` | id,email,nickname,identities,agreements | 401/Error |
| 동선 목록 | `GET /api/itineraries` | id,title,region?,contestName,event,recovery,기간,placeCount,createdAt | Loading/Empty/Error/offline |
| 동선 열기 | `GET /api/itineraries/{id}` | 전체 상세 | S7-R |
| 동선 삭제 | `DELETE /api/itineraries/{id}` | 204 | 확인 modal, 실패 시 유지 |
| 저장 코스 | `GET /api/me/courses` | saved projection | 부분 Error |
| 러닝 기록 | `GET /api/runs` | ran summary | 부분 Error |
| saved/ran 통합 | 두 API 병렬 | type+id를 유지한 합성 목록 | 페이징 D-21 |
| 코스/기록 삭제 | 각 DELETE | 204 | 종류별 확인 modal |
| 찜 목록 | `GET /api/me/favorites` | 대회 카드 page, favorite=true | Empty/Error |
| 찜 해제 | DELETE `/api/me/favorites/{contestId}` | 204 | 실패 시 카드 유지 |
| 설정 | account 이동 | 없음 | M1 |
| 오프라인 | Room | 마지막 성공 segment | 읽기만, 수정 비활성 |

### M1 내 정보·계정 관리

| 행동 | API/SDK | 요청 | 성공·실패 |
|---|---|---|---|
| 닉네임 변경 | `PATCH /api/me` | nickname | 응답 계약 보완 필요, duplicated 처리 |
| 마케팅 동의 | `PATCH /api/me/agreements` | marketing | 응답 계약 보완 필요 |
| 비밀번호 변경 | `PUT /api/me/password` | currentPassword,newPassword | 불일치 code 보완 필요 |
| 로그인 수단 | `GET /api/me/identities` | 없음 | provider,linkedAt |
| 카카오 연결 | SDK→`POST /api/me/identities/kakao` | kakaoAccessToken | already linked 처리 |
| 카카오 해제 | `DELETE /api/me/identities/kakao` | 없음 | last identity 거부 |
| 로그아웃 | `POST /api/auth/logout` | refreshToken | 세션 삭제→로그인 |
| 회원 탈퇴 | 확인 modal→`DELETE /api/me` | 재확인 방식 D-23 | 204→세션·캐시 삭제→로그인 |

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
| 서울 코스 기본 상태 | 140 | 두루누비 경로 0 + Kakao 걷기 장소가 정상 Content |

### 확인된 플로우 연결

- 보관함 동선→S7-R, saved/ran→각 코스 상세, 찜→S3가 연결돼 있다.
- 코스 저장→보관함 saved, 러닝 저장→보관함 ran이 연결돼 있다.
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
| 러닝코스 | `GET /api/courses/**` | Durunubi `courseList` | 시작 시+하루 1회 동기화, GPX 결합 | 코스 목록·상세·출처 문구 |

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

### 화면 플로우로 해결된 항목

| 기존 ID | 결론 |
|---|---|
| F-01~03 | 보관함의 동선·saved/ran·찜 카드에서 각 상세로 이동 |
| F-04 | 코스 상세 저장 후 보관함 saved에서 확인 |
| F-05 | 보관함 설정 아이콘에서 계정 관리 진입 |
| D-27 / F-06 | 로그인 후 원래 화면 복귀, 저장·찜 자동 실행 없음 |
| F-07 | 홈·캘린더·보관함의 Loading/Empty/Error와 부분 실패 캡처 추가 |
| D-05 | 홈 축제 카드는 P0 표시 전용, 플로우에 상세 이동 없음 |
| D-06 | Android S3 route는 `raceDetail/{raceId}` |
| D-07 | 캘린더 month는 route가 아니라 ViewModel 상태 |
| D-11 | 종목 기본값은 이전 선택→HALF→첫 종목 |
| D-13 | 생성 실패 결과는 S7 Empty 상태로 표현 |
| D-22 | 내 정보·계정 관리는 보관함 설정에서 여는 별도 화면 |
| D-23(화면) | 탈퇴 전 확인 modal이 있음. 재인증 계약은 아직 필요 |
| D-26(화면) | 별도 splash 캡처는 없고 앱 시작 세션 분기만 요구됨 |

### 아직 합의가 필요한 계약

| ID | 결정할 내용 | 영향 | 주인 |
|---|---|---|---|
| D-03 | 홈 마감 임박 기본 6(SPEC) vs 4(API·목업) | API limit·카드 수 | 제품+백엔드 |
| D-04 | 홈 축제 전국 월간 vs 기존 권한 위치 정렬 | 요청 parameter·권한 UX | 제품+앱 |
| D-08 | 검색 debounce 시간 | API 호출 빈도 | 앱 내부 정책 |
| D-09 | 대회 lat/lng 없음 시 위저드 진입 처리 | S3 CTA·generate 오류 | 백엔드+앱 |
| D-10 | CUSTOM 최대 여행 기간 | validation·오류 code | 제품+백엔드 |
| D-12 | 숙소 검색 query 호출 시점 | POI 쿼터·UX | 앱+백엔드 |
| D-14 | 블록 PATCH·order PUT 성공 응답 | 저장 후 UI 동기화 | 백엔드 |
| D-15 | S7→S8 출발지·목표거리 전달 방식 | Navigation 계약 | 앱 |
| D-16 | S7 Empty의 다시 추천이 즉시 재호출인지 위저드 복귀인지 | ResultUiState | 제품+앱 |
| D-18 | 동일 sourceCourseId 중복 저장 정책 | 201/멱등/409 | 백엔드 |
| D-20 | near/saved/ran 코스 상세 route와 ID 타입 | Navigation·DTO | 앱 |
| D-21 | saved/ran 통합 정렬·페이징 | 보관함 목록 계약 | 앱+백엔드 |
| D-23 | 회원 탈퇴 재인증 방식 | 보안 API | 백엔드 |
| D-24 | 대회 imageUrl의 P0 사용 여부 | 카드·상세 DTO | 제품+백엔드 |
| D-25 | GPS 기록을 공모전 MVP에 포함할지 | 일정·권한·서비스 | 제품 |
| D-26 | 세션 확인을 전용 Splash 화면으로 만들지 | 시작 route | 앱 |
| D-28 | 계정 관리의 비밀번호 변경이 `07-newpw` 웹 재설정 화면을 재사용할지, 현재 비밀번호 입력 Android 화면을 따로 둘지 | 화면 플로우·`PUT /api/me/password` 요청 | 제품+앱+백엔드 |

---

## 11. API 명세 보완 목록

아래는 `docs/files/런닝구_API_명세서.md`와 springdoc DTO에서 확정해야 한다. 이 매핑표만 보고 백엔드 계약을 임의 구현하지 않는다.

| API | 보완할 계약 |
|---|---|
| `GET /api/contests/{id}` | 전체 JSON 예시, organizer/officialUrl/lat/lng nullable, 좌표 없음 처리 |
| `GET /api/festivals` | 카드 tap 없음 명시, KTO 원천·fetchedAt/cachedAt 추적 필드 |
| `GET /api/contests/{id}/festivals` | 좌표 없음 오류, KTO 원천·fetchedAt/cachedAt 추적 필드 |
| `GET /api/pois` | 항목별 provider(KTO/KAKAO), fetchedAt/cachedAt, 안정적 placeId 필요 여부 |
| `POST /api/itineraries/generate` | 최대 기간, 좌표 없음, validation error code |
| `PATCH /api/itineraries/.../blocks/{blockId}` | 성공 status와 갱신 응답 |
| `PUT /api/itineraries/.../blocks/order` | 성공 status와 갱신 응답 |
| `GET /api/itineraries` | 보관함 카드의 region 필드 |
| `POST /api/me/courses` | 중복 저장 정책 |
| `GET /api/me/courses` | saved 목록 projection의 정확한 필드와 page 예시 |
| `GET /api/runs` | ran 목록 요약의 정확한 필드와 page 예시 |
| `PATCH /api/me` | 성공 status와 응답 body |
| `PATCH /api/me/agreements` | 성공 status와 응답 body |
| `PUT /api/me/password` | 성공 status와 현재 비밀번호 불일치 code |
| `POST /api/me/identities/kakao` | 성공 status와 응답 body |
| `DELETE /api/me/identities/kakao` | 성공 status |
| `DELETE /api/me` | 탈퇴 재인증 요청과 오류 code |

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
