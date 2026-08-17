# 런닝구 화면–API 매핑표 v0.1

> 작성일: 2026-08-13
> 목적: 화면 와이어프레임, Android Navigation, 백엔드 API 구현을 같은 계약으로 연결하는 1차 작업표
> 범위: 인증 A1~A3, 서비스 S1~S10, 목업에만 있는 코스 상세·GPS 기록·러닝 요약·정보 수정
> 화면 기준: `런닝구 UI MOCKUP V2 화면 플로우.png`의 28개 화면과 상태 변형 검토 반영

## 0. 문서 사용법

이 문서는 화면의 배치나 디자인을 정하는 와이어프레임이 아니다. 각 화면에서 **언제 어떤 API를 호출하고, 어떤 요청값을 보내며, 응답의 어떤 필드를 화면에 쓰는지**를 정리한다.

Android 화면 경로와 백엔드 API 경로는 서로 다른 값이다.

```text
Android 화면 경로: raceDetail/{contestId}
백엔드 API 경로:  GET /api/contests/{contestId}
```

### 기준 문서 우선순위

1. `SPEC.md` v4: 제품 범위와 정책의 SSOT
2. `docs/files/런닝구_API_명세서.md` v2.0: 백엔드 API 시드 계약
3. `docs/mockup-design/런닝구-목업-v2.html`: 화면과 상호작용 참고
4. 현재 Android 기능 브랜치: 실제 구현된 route 확인
5. 백엔드 구현 후 springdoc/Swagger: 최종 실행 계약

### 상태 표기

| 표기 | 뜻 |
|---|---|
| **확정** | SPEC/API 명세에서 확정된 값 |
| **현재 구현** | 현재 Android 기능 브랜치에 실제 존재하는 값 |
| **목업 현재값** | HTML 목업에서 사용하는 화면 키나 동작 |
| **미정** | 팀이 정해야 하며 현재 문서만으로 확정할 수 없는 값 |
| **P1/P2** | MVP P0 이후 범위 |

### 공통 API 규약

| 항목 | 현재 계약 |
|---|---|
| Base URL | `/api` |
| 인증 | `Authorization: Bearer {accessToken}` |
| 날짜 | 비즈니스 날짜 `YYYY-MM-DD`, 판정 기준 `Asia/Seoul` |
| 좌표 | `lat`, `lng` Double, WGS84 |
| 오류 | RFC 9457 Problem Details + `code`, `traceId` |
| 대회 목록 | 커서 페이징: `items`, `nextCursor`, `hasNext` |
| 개인 목록 | 페이지 페이징: `content`, `page.number`, `page.size`, `page.totalElements`, `page.hasNext` |
| 화면 상태 | Loading / Content / Empty / Error를 구분 |
| 공개 범위 | 대회·축제·POI·코스 조회, 무상태 동선 생성 |
| 로그인 필요 | 프로필·찜·동선/코스/러닝 기록 저장 및 마이 조회 |

---

## 1. 전체 화면 경로표

`목표 route`가 `미정`인 행은 와이어프레임 확정 후 팀 결정이 필요하다.

| ID | 화면 | SPEC 값 | 목업 현재값 | Android 현재값 | 목표 route | 필수 전달값 | 로그인 | 하단 탭 | 상태/비고 |
|---|---|---|---|---|---|---|---|---|---|
| A0 | 스플래시·세션 확인 | 앱 시작 시 세션 확인 | 없음 | 없음 | `splash` 또는 앱 시작 로직 내부 **미정** | 없음 | 선택 | 숨김 | 별도 화면으로 만들지 결정 필요 |
| A1 | 로그인 | `login` | `login` | 없음 | `login` | 로그인 후 복귀 목적지 **미정** | 불필요 | 숨김 | 큰 흐름 확정 |
| A2 | 회원가입 | `signup` | `signup` 4단계 | 없음 | `signup` | 카카오 신규 가입이면 SDK 토큰·프로필 | 불필요 | 숨김 | 화면 플로우상 한 route의 4단계로 확정 가능 |
| A3 | 비밀번호 재설정 요청 | `reset` | `findpw` | 없음 | `reset` | 없음 | 불필요 | 숨김 | route 이름 불일치 |
| WEB-R1 | 새 비밀번호 설정 | 백엔드 웹 페이지 | 화면 플로우에서 메일 링크→웹 화면 | 없음 | REST route 아님: `/reset-password?token=` | reset token | 불필요 | 해당 없음 | 화면 플로우로 MVP 웹 방식 확인 |
| S1 | 홈 | `home` | `home` | `home` | `home` | 선택: 초기 검색어 없음 | 선택 | 표시 | 현재 route 구현됨 |
| S2 | 캘린더 | `calendar` | `calendar` | `calendar?q={q}` | `calendar?q={q}` | 선택: 검색어 `q` | 선택 | 표시 | 월을 route에 넣을지는 미정 |
| S3 | 대회 상세 | `raceDetail` | `detail` | 없음 | `raceDetail/{contestId}` **제안** | `contestId` | 선택 | 숨김 | `detail`/`raceDetail` 통일 필요 |
| S4 | 일정 선택 | `plan` | `w1` | 없음 | `wizard/plan/{contestId}` **제안** | `contestId` | 불필요 | 숨김 | 게스트 가능 |
| S5 | 종목·취향 | `taste` | `w2` | 없음 | `wizard/taste` **제안** | Wizard 공유 상태 | 불필요 | 숨김 | 공유 ViewModel 사용 계약 |
| S6 | 숙소 선택 | `stay` | `w3` | 없음 | `wizard/stay` **제안** | Wizard 공유 상태 | 불필요 | 숨김 | 공유 ViewModel 사용 계약 |
| S7 | 새 동선 결과 | `result` | `result` | 없음 | `result` **제안** | 생성된 임시 동선 DTO | 조회 불필요, 저장 필요 | 숨김 | 저장 전 결과는 서버 ID 없음 |
| S7-R | 저장 동선 상세 | S7 복원 | `result` 재사용 | 없음 | `itinerary/{itineraryId}` **제안** | `itineraryId` | 필요 | 숨김 | S7 UI 재사용 여부 결정 필요 |
| S8 | 러닝코스 | `courses` | `courses` | `courses` | `courses` | 선택: 출발지·목표 거리 | 선택 | 표시 | 현재 route 구현됨 |
| S8-D | 코스 상세 | 별도 ID 없음 | `coursedetail` | 없음 | route 이름 **미정** | 저장 코스 `id` 또는 러닝 기록 `id`, 종류 | 필요 | 숨김 | 화면 플로우에서 별도 화면 확인, saved/ran 타입 구분 필요 |
| R1 | GPS 러닝 기록 | S8의 기록 모드 | `run` | 없음 | `run` **제안** | 선택 코스 스냅샷 또는 자유 러닝 | 저장 시 필요 | 숨김 | 화면 플로우에서 별도 화면 확인, P1/포그라운드 서비스 |
| R2 | 러닝 요약 | S8의 종료 요약 | `runsum` | 없음 | `runSummary` **제안** | 기록 좌표·거리·시간 | 저장 시 필요 | 숨김 | 화면 플로우에서 별도 화면 확인, P1 |
| S9 | 커뮤니티 | 범위 제외 | 없음 | 없음 | 만들지 않음 | 해당 없음 | 해당 없음 | 없음 | 확정 |
| S10 | 보관함 | SPEC `my` | `library`, UI 라벨 `보관함` | `my` | 내부 route `my` 유지 가능 | 선택: 초기 세그먼트 | 필요 | 표시 | UI 라벨은 화면 플로우상 `보관함`; 내부 route는 기술값이라 달라도 됨 |
| M1 | 내 정보 수정 | 별도 route 미정 | 화면 플로우에 없음 | 없음 | **미정** | 없음 | 필요 | 숨김 또는 유지 **미정** | SPEC P0 기능을 유지한다면 화면/진입점 추가 필요 |

### 화면 간 기본 흐름

```text
앱 시작
  └─ A0 세션 확인 ─┬─ 세션 유효 → S1 home
                   └─ 세션 없음/무효 → A1 login

A1 login ─┬─ A2 signup
          ├─ A3 reset → WEB-R1 새 비밀번호 설정
          ├─ 로그인 성공 → S1
          └─ 게스트 둘러보기 → S1

S1 home → S2 calendar → S3 raceDetail
                              └─ S4 plan → S5 taste → S6 stay → S7 result
                                                                  ├─ 저장 → S10 my
                                                                  └─ 숙소 주변 코스 → S8 courses

S8 courses → S8-D 코스 상세 또는 R1 기록 → R2 요약 → S10 my
S10 my ─┬─ 저장 동선 → S7-R
        ├─ 저장 코스/러닝 기록 → S8-D
        ├─ 찜한 대회 → S3
        └─ 정보 수정 → M1
```

---

## 2. 공통 화면–API 매핑

| 화면 기능/행동 | 발생 시점 | API 또는 로컬 동작 | 요청값 | 필요한 응답 | 인증 | 실패 처리 | 상태 |
|---|---|---|---|---|---|---|---|
| 저장된 세션 읽기 | 앱 시작 | DataStore 로컬 읽기 | 없음 | access/refresh token, guest 여부 | 없음 | 토큰 없으면 A1 | 확정 |
| 세션 검증 | 저장된 access token 존재 | `GET /api/me` | Bearer token | `id`, `email`, `nickname`, `identities`, `agreements`, `createdAt` | 필요 | 401이면 refresh 시도 | API 명세 확정 |
| access token 재발급 | API 401 또는 만료 사전 감지 | `POST /api/auth/refresh` | `refreshToken` | 새 access token, 회전된 refresh token | refresh token | 실패 시 토큰 삭제 후 A1 | 확정 |
| 로그인 필요 기능 차단 | 게스트가 저장·찜·마이 진입 | API 호출 전 앱 guard | 원래 목적 route/행동 **미정** | 없음 | 없음 | “로그인이 필요해요” 모달 | 정책 확정, 복귀 처리 미정 |
| 로그아웃 | M1에서 선택 | `POST /api/auth/logout` | `refreshToken` | `204` | 필요 | 서버 실패 시 로컬 토큰 삭제 여부 **미정** | 결정 필요 |
| 공통 API 오류 | 모든 API | Problem Details 파싱 | 없음 | `status`, `code`, `title`, `detail`, `traceId`, `errors[]` | 상황별 | Error 상태 + 다시 시도 | 확정 |
| 오프라인 읽기 | 대회·마이 마지막 성공 데이터 | Room 읽기 캐시 | 화면별 cache key | 마지막 성공 DTO, `cachedAt` | 상황별 | 캐시 없으면 Error | 범위 확정, 만료 기준 미정 |

---

## 3. 인증 화면

### A1 로그인

| UI/행동 | 발생 시점 | API 또는 로컬 동작 | 요청값 | 필요한 응답 필드 | 인증 | 성공 | 실패/상태 | 상태 |
|---|---|---|---|---|---|---|---|---|
| 이메일 로그인 | 로그인 버튼 | `POST /api/auth/login` | `email`, `password` | `accessToken`, `refreshToken`, `user.id`, `user.email`, `user.nickname`, `user.identities` | 불필요 | 토큰 저장 후 `home`, 백스택 제거 | `LOGIN_FAILED`: 인라인 “이메일 또는 비밀번호를 확인해 주세요” | 확정 |
| 카카오 SDK 로그인 | 카카오 버튼 | Android Kakao SDK | 없음 | `kakaoAccessToken` | 불필요 | 받은 토큰을 다음 API로 전달 | SDK 취소/오류 문구 **미정** | SDK 사용 확정 |
| 카카오 계정 확인 | SDK 성공 직후 | `POST /api/auth/kakao` | `kakaoAccessToken` | 기존: 로그인 토큰+user / 신규: `isNewUser`, `kakaoProfile.nickname`, nullable `email` | 불필요 | 기존→S1, 신규→A2 카카오 가입 | `INVALID_KAKAO_TOKEN` | 확정 |
| 게스트 둘러보기 | 둘러보기 버튼 | API 없음, guest 상태 저장 | 없음 | 없음 | 불필요 | `home` | 없음 | 확정 |
| 회원가입 이동 | 회원가입 링크 | Navigation | 없음 | 없음 | 불필요 | `signup` | 없음 | 확정 |
| 비밀번호 찾기 이동 | 비밀번호 찾기 링크 | Navigation | 없음 | 없음 | 불필요 | `reset` | 없음 | 확정 |
| 구글·네이버 로그인 | 버튼 표시 여부 | **API/화면 연결하지 않음** | — | — | — | — | — | P2 |

### A2 회원가입

| UI/행동 | 발생 시점 | API 또는 로컬 동작 | 요청값 | 필요한 응답 필드 | 성공 | 실패/상태 | 상태 |
|---|---|---|---|---|---|---|---|
| 약관 전체/개별 동의 | 1단계 | 로컬 상태 | `tos`, `privacy`, `marketing` | 없음 | 필수 2종 동의 시 다음 활성화 | 필수 미동의면 진행 불가 | 확정 |
| 이메일 형식 확인 | 입력 중 | 로컬 validation | email | 없음 | 형식 통과 | 인라인 오류 문구 **미정** | 규칙 확정 |
| 이메일 중복 확인 | 입력 완료/포커스 해제 또는 버튼 **미정** | `GET /api/auth/email/exists?email=` | `email` | `exists` | false면 사용 가능 | true면 중복 안내 | 호출 시점 미정 |
| 닉네임 중복 확인 | 입력 완료/포커스 해제 또는 버튼 **미정** | `GET /api/auth/nickname/exists?nickname=` | `nickname` | `exists` | false면 사용 가능 | true면 중복 안내 | 호출 시점 미정 |
| 비밀번호 규칙 확인 | 입력 중 | 로컬 validation | password, confirm | 없음 | 8자 이상 영문+숫자·확인 일치 | 인라인 오류 | 확정 |
| 인증 코드 발송 | 정보 입력 완료 | `POST /api/auth/email/send-code` | `email` | `204` | 코드 입력 단계, 60초 타이머 | `EMAIL_DUPLICATED`, `SEND_COOLDOWN` | 확정 |
| 인증 코드 재발송 | 재발송 버튼 | 같은 send-code API | `email` | `204` | 타이머 재시작 | `SEND_COOLDOWN` | 확정 |
| 인증 코드 확인 | 6자리 입력 후 확인 | `POST /api/auth/email/verify` | `email`, `code` | `verified` | 가입 요청 가능 | `INVALID_CODE`, `CODE_EXPIRED`, `TOO_MANY_ATTEMPTS` | 확정 |
| 이메일 회원가입 완료 | 인증 성공 후 | `POST /api/auth/signup` | `email`, `password`, `nickname`, `agreements{tos,privacy,marketing}` | 로그인과 동일한 token+user | **목업/API 현재값은 자동 로그인 후 home** | `EMAIL_NOT_VERIFIED`, `AGREEMENT_REQUIRED`, 중복 오류 | 완료 목적지는 SPEC에서 정책값이라 최종 확인 필요 |
| 카카오 신규 가입 | 카카오 신규 사용자 약관 완료 | `POST /api/auth/kakao/signup` | `kakaoAccessToken`, `nickname`, `agreements` | token+user | home | `NICKNAME_DUPLICATED`, `INVALID_KAKAO_TOKEN` | 확정 |

### A3 비밀번호 재설정 요청 / WEB-R1 새 비밀번호

| UI/행동 | 발생 시점 | API 또는 로컬 동작 | 요청값 | 필요한 응답 | 성공 | 실패/상태 | 상태 |
|---|---|---|---|---|---|---|---|
| 재설정 메일 발송 | A3 제출 | `POST /api/auth/password/reset-request` | `email` | `202` | 가입 여부와 무관하게 동일 성공 문구 | 429 쿨다운 | 확정 |
| 링크 열기 | 이메일 링크 탭 | `GET /reset-password?token=` Spring MVC 웹 | `token` | 비밀번호 입력 HTML | 웹에서 새 비밀번호 입력 | 잘못된 토큰 안내 | SPEC MVP 확정 |
| 새 비밀번호 설정 | 웹 제출 | `POST /api/auth/password/reset` | `token`, `newPassword` | `204` | 모든 refresh token 무효화, 로그인 안내 | `INVALID_RESET_TOKEN`, `INVALID_PASSWORD` | 확정 |
| 앱 내 `newpw` 화면 | 목업에 존재 | **미정** | — | — | — | — | SPEC 웹 방식과 충돌 |

---

## 4. 대회 탐색 화면

### S1 홈

| UI/행동 | 발생 시점 | API 또는 로컬 동작 | 요청값 | 필요한 응답 필드 | 인증 | 실패/빈 상태 | 상태 |
|---|---|---|---|---|---|---|---|
| 대회·지역 검색 | 검색 제출 | API 호출 없이 S2 이동 | route `q` | 없음 | 불필요 | 빈 검색 허용 여부 **미정** | 현재 Android `calendar?q={q}` 구현 |
| 달력 아이콘 | 탭 | S2 이동 | 없음 | 없음 | 불필요 | 없음 | 확정 |
| 지도/코스 아이콘 | 탭 | S8 이동 | 초기 탭 `near` 전달 방식 **미정** | 없음 | 불필요 | 없음 | 이동 목적 확정 |
| 관광 아이콘 | 탭 | 홈 축제 섹션으로 로컬 스크롤 | 없음 | 없음 | 불필요 | 없음 | 확정 |
| 히어로 대회로 동선 만들기 | CTA | S4로 직접 이동 | 히어로의 `contestId` | 없음 | 불필요 | 대회 기본정보가 없으면 상세 재조회 | 화면 플로우에서 주 진입 경로로 확인 |
| 마감 임박 대회 | 화면 진입/새로고침 | `GET /api/contests/closing-soon?limit=` | `limit` | 대회 카드 필드 + `dDayApply` | 선택; 로그인 시 `favorite` | 영역 Error 또는 다시 시도 **미정** | SPEC 6건, API/목업 4건으로 불일치 |
| 마감 임박 카드 선택 | 카드 탭 | S3 이동 | `contestId` | 없음 | 불필요 | 없음 | 확정 |
| 홈 축제 추천 | 화면 진입/새로고침 | `GET /api/festivals` | `yearMonth`, 선택 `lat`,`lng`, `size` | `contentId`, `name`, `startDate`, `endDate`, `region`, `imageUrl`, `inProgress`, 선택 `distanceKm` | 불필요 | 빈 캐러셀 문구·502/504 처리 **미정** | 월 기준/내 위치/전국 기준 결정 필요 |
| 축제 카드 선택 | 카드 탭 | **미정** | 현재 API 응답의 URL 필드 없음 | — | — | — | 목업/명세에 목적지 계약 없음 |

### S2 캘린더

| UI/행동 | 발생 시점 | API 또는 로컬 동작 | 요청값 | 필요한 응답 필드 | 인증 | 실패/빈 상태 | 상태 |
|---|---|---|---|---|---|---|---|
| 리스트 조회 | 화면 진입·검색·필터 적용·날짜 선택 | `GET /api/contests` | `q`, `events[]`, `openOnly`, `regions[]`, 선택 `date`, `cursor`, `size` | `items[].id`, `name`, `region`, `place`, `contestDate`, `startTime`, `events`, `regStatus`, `applyEnd`, `sources`, `checkedAt`, `favorite`; `nextCursor`, `hasNext` | 선택 | 정상 0건은 Empty, 오류는 Error | 확정 |
| 검색어 입력 | 입력 즉시 | 서버 검색 또는 현재 페이지 로컬 필터 **미정** | `q` | 위 목록 | 선택 | 디바운스 시간 **미정** | SPEC “입력 즉시”, API는 서버 검색 |
| 리스트/달력 토글 | 토글 | 로컬 상태 | `list/calendar` | 없음 | 불필요 | 없음 | 확정 |
| 월간 날짜별 건수 | 캘린더 진입·월 이동·필터 적용 | `GET /api/contests/daily-counts` | `year`, `month`, `q`, `events[]`, `openOnly`, `regions[]` | `counts[].date`, `counts[].count` | 선택 | 실패 시 점만 숨길지 Error로 볼지 **미정** | API 확정 |
| 날짜 선택/재탭 해제 | 날짜 탭 | 선택일로 목록 API 재호출 | `date` 또는 null | 대회 목록 | 선택 | “이 날/달엔 대회가 없어요” | 확정 |
| 이전/다음 월 | 화살표 | 월 상태 변경 후 daily-counts 조회 | `year`,`month` | 날짜별 건수 | 선택 | 월 이동 범위 **미정** | 일부 미정 |
| 필터 임시 변경 | 필터 시트 | 로컬 draft 상태 | events/openOnly/regions | 없음 | 불필요 | 취소 시 폐기 | 확정 |
| 필터 완료 | 완료 버튼 | 목록+daily-counts 재조회 | 적용 필터 | 위 응답 | 선택 | 0건이면 필터 초기화 CTA | 확정 |
| 다음 페이지 | 리스트 하단 접근 | `GET /api/contests` | 동일 필터 + opaque `cursor` | 추가 items, nextCursor | 선택 | 중복 요청/실패 시 재시도 **미정** | API 확정 |
| 찜 등록 | 빈 하트 탭 | `PUT /api/me/favorites/{contestId}` | path `contestId` | `204` | 필요 | 게스트 로그인 모달, 실패 시 UI 원복 | **PUT 확정** |
| 찜 해제 | 채운 하트 탭 | `DELETE /api/me/favorites/{contestId}` | path `contestId` | `204` | 필요 | 실패 시 UI 원복 | 확정 |
| 대회 카드 선택 | 카드 탭 | S3 이동 | `contestId` | 없음 | 불필요 | 없음 | 확정 |

### S3 대회 상세

| UI/행동 | 발생 시점 | API 또는 로컬 동작 | 요청값 | 필요한 응답 필드 | 인증 | 실패/빈 상태 | 상태 |
|---|---|---|---|---|---|---|---|
| 대회 상세 | 화면 진입 | `GET /api/contests/{contestId}` | path `contestId` | 카드 필드 + `applyStart`, `organizer`, `officialUrl`, `lat`, `lng`, `dDay` | 선택 | `CONTEST_NOT_FOUND`, 다시 시도 | 확정 |
| 찜 등록/해제 | 하트 탭 | S2와 동일 PUT/DELETE | `contestId` | `204` | 필요 | 게스트 로그인 모달, 실패 시 원복 | 확정 |
| 인근 축제 | 상세과 독립적으로 조회 | `GET /api/contests/{contestId}/festivals` | `contestId` | `items[].contentId`, `name`, `startDate`, `endDate`, `distanceKm`, `imageUrl`, `address` | 불필요 | Loading / Empty / Error를 상세 본문과 분리 | 확정 |
| 공식 페이지 | 링크 탭 | Android Custom Tabs | `officialUrl` | 없음 | 불필요 | URL null이면 버튼 숨김 여부 **미정** | 외부 이동 확정 |
| 공유 | 공유 버튼 | Android Kakao Share SDK 또는 시스템 공유 **미정** | 대회 정보 | 없음 | 불필요 | MVP 표시 전용 가능 | P1/AP-17 |
| 동선 만들기 | CTA | S4 이동 | `contestId`; 상세 DTO를 공유 ViewModel에 저장 | 없음 | 불필요 | 좌표가 없을 때 진행 가능 여부 **미정** | 게스트 허용 확정 |

---

## 5. 동선 위저드와 결과

### S4 일정 선택

| UI/행동 | 발생 시점 | API 또는 로컬 동작 | 입력/요청 | 필요한 데이터 | 실패/검증 | 상태 |
|---|---|---|---|---|---|---|
| 대회 기본정보 표시 | 화면 진입 | 공유 WizardViewModel; 없으면 `GET /api/contests/{contestId}` 재조회 **제안** | `contestId` | 대회명, contestDate | 상세 복원 실패 시 S3로 복귀 **미정** | 복원 정책 미정 |
| 일정 패턴 선택 | 패턴 탭 | 로컬 도메인 규칙 | pattern | `startDate`, `endDate` 계산 | 없음 | API 없음 확정 |
| 직접 날짜 선택 | 미니 캘린더 탭 | 로컬 상태 | 시작/종료일 | 기간 | 역순 자동 정렬, 허용 최대 기간 **미정** | 일부 미정 |
| 다음 | CTA | S5 이동 | Wizard state | 없음 | 시작/종료 필수 | API 없음 확정 |

### S5 종목·취향

| UI/행동 | 발생 시점 | API 또는 로컬 동작 | 입력/요청 | 필요한 데이터 | 실패/검증 | 상태 |
|---|---|---|---|---|---|---|
| 기본 종목 설정 | 화면 진입 | 로컬 규칙 | 대회 `events` | 이전 선택 → HALF 우선 → 첫 종목 | 대회 events가 비어 있을 때 기본값 **미정** | 일부 미정 |
| 종목 변경 | 세그먼트 탭 | 로컬 상태 | `K5/K10/HALF/FULL` | 회복 강도·안내 계산 | 대회 미포함 종목도 허용 | 확정 |
| 취향 선택 | 칩 탭 | 로컬 상태 | `TOUR/FOOD/CAFE/WELLNESS/NATURE/HISTORY` | 선택 themes | 0개면 CTA 비활성 | 확정 |
| 다음 | CTA | S6 이동 | Wizard state | 없음 | themes 1개 이상 | API 없음 확정 |

### S6 숙소 선택 및 동선 생성

| UI/행동 | 발생 시점 | API 또는 로컬 동작 | 요청값 | 필요한 응답 필드 | 인증 | 실패/빈 상태 | 상태 |
|---|---|---|---|---|---|---|---|
| 숙소 후보 조회 | 화면 진입 | `GET /api/pois` | `category=LODGING`, 대회 `lat`,`lng`, `radius`, `size=8` | `source`, `items[].name`, `category`, `lat`, `lng`, `distanceM`, `description`, `address`, `url`, `imageUrl` | 불필요 | Loading / Empty / Error | 확정 |
| 숙소 검색 | 검색 입력 | 로컬 이름+주소 필터 또는 `GET /api/pois?query=` **미정** | 위 값 + `query` | 같은 POI 응답 | 불필요 | 검색 0건 | SPEC은 로컬, API는 서버 query도 지원 |
| 숙소 선택/해제 | 항목 탭 | 로컬 Wizard state | 선택 POI 또는 null | 없음 | 불필요 | 없음 | 확정 |
| 숙소 포함 생성 | CTA | `POST /api/itineraries/generate` | `contestId`, `startDate`, `endDate`, `event`, `themes`, `hotel{name,lat,lng}` | `title`, `contestId`, `event`, `themes`, 날짜, hotel, recovery, `days[]`, `blocks[]` | 게스트 허용 | 생성 중 비활성; 오류 시 S7 Empty+스낵바 여부 **미정** | API 확정 |
| 숙소 없이 생성 | CTA | 같은 generate API | 위 요청에서 `hotel=null` | 같은 응답 | 게스트 허용 | 대회장 중심 생성 | 확정 |

### S7 새 동선 결과 / S7-R 저장 동선 상세

| UI/행동 | 발생 시점 | API 또는 로컬 동작 | 요청값 | 필요한 응답 필드 | 인증 | 실패/빈 상태 | 상태 |
|---|---|---|---|---|---|---|---|
| 새 결과 표시 | S6 생성 성공 | 생성 응답을 공유 상태에서 사용 | 없음 | generate 전체 DTO | 불필요 | days/blocks 0건이면 Empty | 확정 |
| 저장 결과 복원 | 마이 동선 카드 탭 | `GET /api/itineraries/{id}` | `itineraryId` | generate DTO + `id`, `days[].id`, `blocks[].id`, `orderNo` | 필요 | 403/404/Error | 확정 |
| 일자·카드·핀 활성화 | 탭/스크롤 | 로컬 UI 상태 | day/block index | 없음 | 불필요 | 없음 | API 없음 확정 |
| 후보 장소 조회 | 교체/추가 시트 열기 | `GET /api/pois` | `category`, 중심 `lat`,`lng`, `radius`, `size=8`, 선택 `query` | POI 응답 | 불필요 | 시트 내부 Loading/Empty/Error | 확정 |
| 저장 전 블록 교체·추가·삭제·순서 변경 | 새 결과 편집 | 로컬 DTO 편집 | 선택 POI/블록 순서 | 없음 | 불필요 | RACE 블록 편집 UI 미노출 | 확정 |
| 새 동선 저장 | 저장 CTA | `POST /api/itineraries` | 편집된 generate 응답 구조 | 신규 `201 {id}` 또는 교체 `200 {id,replaced}` | 필요 | 게스트 로그인 모달, validation/Error | 확정 |
| 저장 후 블록 추가 | 저장 동선 편집 | `POST /api/itineraries/{id}/days/{dayId}/blocks` | `startTime`, `title`, `category`, 장소·좌표·설명 | `blockId`, `orderNo` | 필요 | 403/404/Error | 확정 |
| 저장 후 블록 교체/수정 | 저장 동선 편집 | `PATCH /api/itineraries/{id}/days/{dayId}/blocks/{blockId}` | 변경 필드 | **응답 body 미정** | 필요 | RACE면 `SYSTEM_BLOCK_IMMUTABLE` | 응답 계약 미정 |
| 저장 후 블록 삭제 | 저장 동선 편집 | `DELETE /api/itineraries/{id}/days/{dayId}/blocks/{blockId}` | path ids | `204` | 필요 | RACE면 409 | 확정 |
| 저장 후 순서 변경 | 드래그 완료 | `PUT /api/itineraries/{id}/days/{dayId}/blocks/order` | 해당 day의 전체 USER `blockIds` | **응답 body 미정** | 필요 | `BLOCK_SET_MISMATCH`, RACE 경계 위반 409 | 응답 계약 미정 |
| 공유 | 공유 버튼 | MVP 목업 스낵바; P1 Kakao Share SDK | 동선 요약 | 없음 | **미정** | 공유 실패 | P1 |
| 숙소 주변 러닝코스 | 연계 카드 | S8 이동 | `startLat`,`startLng`,`startName`, `targetKm=min(recovery.walk,5)` 전달 방식 **미정** | 없음 | 불필요 | 숙소 null이면 대회장 사용 여부 **미정** | 목적 확정, route 인자 미정 |
| 다시 추천 | Empty CTA | S4 또는 generate 재호출 **미정** | 기존 Wizard state | generate 응답 | 불필요 | 재실패 처리 | 목적지 미정 |

---

## 6. 러닝코스와 GPS 기록

### S8 러닝코스

| UI/행동 | 발생 시점 | API 또는 로컬 동작 | 요청값 | 필요한 응답 필드 | 인증 | 실패/빈 상태 | 상태 |
|---|---|---|---|---|---|---|---|
| 내 위치 가져오기 | 버튼/첫 진입 **미정** | FusedLocationProvider | FINE/COARSE 권한, 6초 timeout | `lat`,`lng` | 불필요 | 거부/timeout 시 검색·프리셋 안내 | 정책 확정, 자동 요청 여부 미정 |
| 출발지 검색 | 검색 제출 | `GET /api/geocode?query=` | `query` | `name`, `address`, `lat`, `lng` | 불필요 | `NO_RESULT` | 확정 |
| 프리셋 선택 | 칩 탭 | 앱 상수 | 부산 해운대·여수·강릉·인천 강화·서울시청 좌표 | `name`,`lat`,`lng` | 불필요 | 없음 | 확정 |
| 목표 거리 변경 | 슬라이더 | 로컬 상태 후 near 재조회 시점 **미정** | 1~21km, 0.5 단위 | 없음 | 불필요 | 없음 | 디바운스/CTA 여부 미정 |
| 주변 코스 생성 | 출발지·거리 확정 | `GET /api/courses/near` | `lat`,`lng`,`targetKm`,`radiusKm=8`,`size=12` | `courseId`, `courseName`, `sido`, `sigun`, `difficulty`, `fullDistanceKm`, `accessM`, `routeKm`, `durationMin`, `shortfall`, `entry`, `pathPolyline`, `dataSource`, `syncedAt` | 불필요 | 빈 items면 걷기 스팟 중심 상태 | 확정 |
| 걷기 좋은 곳 | 출발지 확정 | `GET /api/walk-spots` | `lat`,`lng`,`size=12` | `name`, `category`, `address`, `lat`, `lng`, `distanceM`, `url` | 불필요 | 코스/스팟 조합별 Empty 문구 | 확정 |
| 대체 코스 선택 | 카드 탭 | near 응답 내부 로컬 선택 | `courseId` | 없음 | 불필요 | 없음 | 확정 |
| 걷기 장소 열기 | 항목 탭 | Custom Tabs | `url` | 없음 | 불필요 | URL 실패 처리 **미정** | 확정 |
| 지역 칩 조회 | 지역별 탭 진입 | `GET /api/courses/regions` | 없음 | `items[].region`, `count` | 불필요 | 칩 없이 전국 목록 여부 **미정** | 확정 |
| 지역별 목록 | 지역 탭 진입/칩 선택 | `GET /api/courses` | 선택 `region`, `page`, `size` | `content[].courseId`, `courseName`, `sido`, `sigun`, `distanceKm`, `difficulty`, `durationMin`, `dataSource`, `syncedAt`; page | 불필요 | “이 지역엔 코스가 없어요” | 확정 |
| 코스 저장 | 저장 버튼 | `POST /api/me/courses` | `sourceCourseId`, `courseName`, `region`, `distanceKm`, `durationMin`, `difficulty`, `entryLat`, `entryLng`, `pathPolyline` | `id` | 필요 | 게스트 로그인 모달; 중복 저장 정책 **미정** | API 존재, 중복 정책 미정 |
| 이 코스 뛰기 | CTA | R1 시작 | 선택 코스 스냅샷 | 없음 | 기록은 로컬, 저장 시 필요 | 위치 권한/서비스 오류 | P1 |
| 자유 달리기 | 출발지만 있는 상태의 CTA **위치 미정** | R1 시작 | courseName=null | 없음 | 기록은 로컬, 저장 시 필요 | 위치 권한 오류 | P1, UI 위치 미정 |

### S8-D 코스 상세

| 상세 종류 | 발생 시점 | API | 요청값 | 필요한 응답 필드 | 행동 | 상태 |
|---|---|---|---|---|---|---|
| 저장 코스(saved) | 마이 저장 코스 탭 | `GET /api/me/courses/{id}` | savedCourse id | 목록 필드 + `pathPolyline` | 점선 경로, 삭제, 이 코스 뛰기 | API 확정, route 미정 |
| 러닝 기록(ran) | 마이 러닝 기록 탭 | `GET /api/runs/{id}` | run id | 요약 + `encodedPolyline`, `pointCount` | 실선 경로, 삭제 | API 확정, route 미정 |
| 저장 코스 삭제 | 삭제 | `DELETE /api/me/courses/{id}` | id | `204` | 마이 복귀 | 확정 |
| 러닝 기록 삭제 | 삭제 | `DELETE /api/runs/{id}` | id | `204` | 마이 복귀 | 확정 |

### R1 GPS 기록 / R2 러닝 요약

| UI/행동 | 발생 시점 | API 또는 로컬 동작 | 요청값/데이터 | 필요한 응답 | 인증 | 실패/상태 | 상태 |
|---|---|---|---|---|---|---|---|
| 기록 시작 | R1 진입·시작 버튼 **미정** | Foreground Service + FusedLocationProvider | 선택 코스명/출발지 | 없음 | 불필요 | 위치 권한 필요 | P1 |
| 위치 누적 | 기록 중 | 로컬, 5m 이동 필터 | timestamp, lat, lng | 경과 시간, 누적 거리, polyline | 불필요 | GPS 튐/신호 없음 UI **미정** | 필터 규칙 확정 |
| 기록 종료 | 종료 버튼 | 서비스 중지 후 R2 이동 | points, duration, distance | 로컬 요약 | 불필요 | 좌표 2개 미만이면 저장 불가 | 일부 미정 |
| 기록 저장 | R2 저장 CTA | `POST /api/runs` | `courseName` nullable, `ranAt`, `distanceKm`, `durationSec`, `points[[lat,lng]]` | `id`, `avgPaceSec` | 필요 | 게스트 로그인 모달, `INVALID_TRACK` | P1/API 확정 |
| 저장 전 삭제/취소 | R2 삭제 버튼 | 로컬 임시 기록 폐기 | 없음 | 없음 | 불필요 | 확인 모달 여부 **미정** | 목업 존재, 정책 미정 |
| 저장된 기록 삭제 | S8-D 삭제 | `DELETE /api/runs/{id}` | run id | `204` | 필요 | 오류 시 유지 | 확정 |

---

## 7. 마이 화면

### S10 마이

| UI/행동 | 발생 시점 | API 또는 로컬 동작 | 요청값 | 필요한 응답 필드 | 실패/빈 상태 | 상태 |
|---|---|---|---|---|---|---|
| 프로필 요약 | 화면 진입 | `GET /api/me` | Bearer token | `id`, `email`, `nickname`, `identities[]`, `agreements`, `createdAt` | 401 로그인 이동, Error | 확정 |
| 동선 목록 | 동선 세그먼트 | `GET /api/itineraries` | `page`,`size` | `id`, `title`, `contestName`, `event`, `recovery`, `startDate`, `endDate`, `placeCount`, `createdAt`; page | “아직 저장한 동선이 없어요” | 응답에 카드용 `region`이 없어 계약 보완 필요 |
| 저장 동선 열기 | 카드 탭 | S7-R 이동 후 `GET /api/itineraries/{id}` | itinerary id | 전체 동선 상세 | 403/404/Error | 확정 |
| 저장 동선 삭제 | 삭제 | `DELETE /api/itineraries/{id}` | id | `204` | 실패 시 목록 유지 | 확정 |
| 저장 코스 목록 | 러닝코스 세그먼트 | `GET /api/me/courses` | `page`,`size` | 목록 projection, 정확한 필드 목록 **미정** | “저장한 코스가 없어요” | 응답 필드 상세화 필요 |
| 러닝 기록 목록 | 러닝코스 세그먼트 | `GET /api/runs` | `page`,`size` | id, ranAt, courseName, distanceKm, durationSec, avgPaceSec 등 **명시 보완 필요** | saved와 ran이 모두 없을 때 Empty | 응답 필드 상세화 필요 |
| saved/ran 통합 표시 | 세그먼트 진입 | 위 두 API 병렬 호출 후 앱에서 합침 | 페이지 독립 | 두 목록 | 부분 실패 처리 **미정** | 페이징 통합 방식 결정 필요 |
| 저장 코스/기록 열기 | 카드 탭 | S8-D 이동 | 종류(saved/ran), id | 없음 | route 미정 | 결정 필요 |
| 찜한 대회 목록 | 찜한 대회 세그먼트 | `GET /api/me/favorites` | `page`,`size` | S2 대회 카드 필드, `favorite=true`; page | “찜한 대회가 없어요” | 확정 |
| 찜 해제 | 채운 하트 | `DELETE /api/me/favorites/{contestId}` | contest id | `204` | 실패 시 카드 유지 | 확정 |
| 대회 둘러보기 | Empty CTA | S2 이동 | 없음 | 없음 | — | 확정 |
| 러닝코스 둘러보기 | Empty CTA | S8 이동 | 없음 | 없음 | — | 확정 |
| 정보 수정 | 버튼 | M1 이동 또는 시트 **미정** | 없음 | 없음 | — | 화면 형태 미정 |

### M1 내 정보 수정·계정 관리

| UI/행동 | API 또는 로컬 동작 | 요청값 | 필요한 응답 | 성공 | 실패 | 상태 |
|---|---|---|---|---|---|---|
| 닉네임 변경 | `PATCH /api/me` | `nickname` | 응답 body **미정** | 프로필 갱신 | `NICKNAME_DUPLICATED` | 응답 계약 보완 필요 |
| 마케팅 수신 변경 | `PATCH /api/me/agreements` | `marketing` | 응답 body **미정** | 동의 상태 갱신 | 오류 시 원복 | 응답 계약 보완 필요 |
| 비밀번호 변경 | `PUT /api/me/password` | `currentPassword`, `newPassword` | 응답 body **미정** | 다른 refresh token revoke | 잘못된 현재 비밀번호 code **미정** | 오류 계약 보완 필요 |
| 로그인 수단 조회 | `GET /api/me/identities` | 없음 | `items[].provider`, `linkedAt` | 연결 상태 표시 | Error | 확정 |
| 카카오 연결 토큰 획득 | Android Kakao SDK | 없음 | `kakaoAccessToken` | 다음 API 호출 | SDK 오류 | 확정 |
| 카카오 연결 | `POST /api/me/identities/kakao` | `kakaoAccessToken` | 응답 body **미정** | 연결 상태 갱신 | `IDENTITY_ALREADY_LINKED` | 응답 계약 보완 필요 |
| 카카오 연결 해제 | `DELETE /api/me/identities/kakao` | 없음 | `204`로 예상되나 명세 명시 없음 | 연결 상태 갱신 | `LAST_IDENTITY_REQUIRED` | 성공 status 명시 필요 |
| 로그아웃 | `POST /api/auth/logout` | `refreshToken` | `204` | 토큰 삭제 후 A1 | 서버 실패 시 로컬 처리 **미정** | 일부 미정 |
| 회원 탈퇴 | `DELETE /api/me` | 재확인 수단 **미정** | `204` | 토큰·캐시 삭제 후 A1 | 확인/재인증 정책 **미정** | 보안 UX 결정 필요 |
| 이메일 주소 변경 | 제공하지 않음 | — | — | — | — | MVP 제외 |

---

## 8. API 없는 화면 동작

다음 동작은 백엔드 API를 만들지 않고 Android 내부에서 처리한다.

| 동작 | 처리 위치 |
|---|---|
| 화면 이동과 뒤로가기 | Navigation Compose |
| 하단 탭 전환과 탭별 스택 초기화 | Navigation Compose |
| 필터 시트의 임시 draft·취소·초기화 | Calendar ViewModel |
| 위저드 일정·종목·취향 선택 | Wizard graph-scoped ViewModel |
| 저장 전 동선 편집 | Result ViewModel의 임시 DTO |
| 지도 핀↔타임라인 활성화 | Compose UI state |
| 현재 위치 조회와 GPS 기록 | Android Location API/Foreground Service |
| 공식 페이지·카카오 장소 페이지 | Custom Tabs |
| 프리셋 출발지 5곳 | 앱 상수 |
| 게스트 상태 | DataStore |
| 스낵바·확인 모달 | Compose UI |

---

## 9. 화면 플로우에서 보완할 연결

현재 화면 플로우는 주요 happy path와 상태 변형을 충분히 보여준다. 아래는 새 화면을 전부 다시 그리는 작업이 아니라, **화살표·진입점·버튼 중 빠진 것만 추가하거나 범위 제외로 표시하는 작업**이다.

| ID | 플로우에 보완할 내용 | 이유 | 처리 선택지 |
|---|---|---|---|
| F-01 | 보관함 [동선] 카드 → 저장 동선 결과/상세 | 저장한 동선을 다시 여는 흐름이 API 명세에 존재 | S7 결과 화면 재사용으로 화살표 추가 |
| F-02 | 보관함 [러닝코스] 카드 → 코스 상세 | saved 코스와 ran 기록 상세 조회가 존재 | 카드에 `saved`/`ran` 표시 후 같은 상세 UI 또는 별도 route |
| F-03 | 보관함 [찜한 대회] 카드 → 대회 상세 | 찜 목록에서 S3 진입 계약 | S3로 화살표 추가 |
| F-04 | 코스 상세의 [코스 저장] 행동과 저장 후 목적지 | 보관함에는 saved 코스가 있으나 상세 화면에는 뛰기 CTA만 명확함 | 저장 버튼 추가 또는 saved 코스 기능을 범위 제외 |
| F-05 | 프로필·정보 수정·계정 관리 진입점 | SPEC P0에 닉네임·약관·비밀번호·카카오 연결·로그아웃·탈퇴가 있으나 화면이 없음 | 보관함 상단 설정 아이콘→M1 추가, 또는 P0 제외 결정 |
| F-06 | 게스트 로그인 후 복귀 위치 | 저장·찜 시 로그인 유도는 있으나 로그인 완료 후 행동이 불명확 | 원래 화면 복귀만 할지, 원래 저장/찜까지 자동 재시도할지 표기 |
| F-07 | 홈·캘린더 기본 Loading/Empty/Error | 현재 상태 변형에는 일부 상세·위저드·코스·보관함만 있음 | 화면 전체 또는 섹션 단위 상태를 상태 변형 영역에 추가 |

### 화면 플로우로 확정된 기존 미정값

| 항목 | 화면 플로우에서 확인된 값 |
|---|---|
| 회원가입 구성 | `약관 동의 → 정보 입력 → 이메일 인증 → 가입 완료` 4단계 |
| 회원가입 완료 | [시작하기] → 홈 |
| 비밀번호 재설정 | 앱에서 메일 발송 → 메일 링크의 웹 새 비밀번호 설정 |
| 홈 주 동선 진입 | 히어로 [이 대회로 동선 만들기] → 위저드 1 |
| 위저드 | 일정 → 종목·취향 → 숙소 → 결과 |
| 동선 편집 | 결과 → 편집 모드 → POI 추가/교체 → 결과 |
| 러닝 기록 | 코스 상세 → GPS 기록 중 → 러닝 요약 |
| 코스 상세 | 러닝코스 목록과 분리된 별도 화면 |
| 하단 네 번째 탭 표시명 | `보관함` |
| 보관함 세그먼트 | `동선 / 러닝코스 / 찜한 대회` |

---

## 10. 불일치·미정·결정 필요 목록

이 표의 항목을 결정하기 전까지 관련 칸은 비워 두거나 현재값을 병기한다.

| ID | 항목 | 현재 값 A | 현재 값 B | 결정할 내용 | 우선순위 |
|---|---|---|---|---|---|
| D-03 | 홈 마감 임박 개수 | SPEC: 상위 6건 | API·목업: `limit=4` | 기본 노출 개수 | P0 |
| D-04 | 홈 축제 기준 | 이번 달 전국 | 위치 권한 있으면 내 위치 | 기본 요청 파라미터와 권한 요청 시점 | P0 |
| D-05 | 홈 축제 카드 탭 | 화면에는 카드 존재 | API 응답에 상세 URL 계약 없음 | 탭 동작 없음/웹 상세/별도 화면 | P0 |
| D-06 | S3 route 이름 | SPEC `raceDetail` | 목업 `detail` | Android 최종 route | P0 |
| D-07 | 캘린더 월 route | 현재 코드: ViewModel 상태, `calendar?q=` | 초안: `month` query | 딥링크/복원용 month 인자 필요 여부 | P0 |
| D-08 | 캘린더 검색 방식 | SPEC: 입력 즉시 필터 | API: 서버 `q` 검색 | 서버 debounce 또는 내려받은 범위 로컬 필터 | P0 |
| D-09 | S3 대회 좌표 없음 | 상세 API lat/lng nullable 가능성 | 위저드는 좌표 필요 | 동선 CTA 비활성/서버 지오코딩/대체 좌표 | P0 |
| D-10 | 직접 선택 최대 여행 기간 | 명시 없음 | 엔진은 날짜 범위를 받음 | 최대 n일 제한 | P0 |
| D-11 | 종목 없는 대회의 기본값 | events 빈 배열 가능 | 기본 규칙은 첫 종목 | K5/HALF/미선택 중 기본값 | P0 |
| D-12 | 숙소 검색 | SPEC: 받은 8건 로컬 필터 | API: `query` 지원 | 입력 debounce 서버 검색 여부 | P0 |
| D-13 | 생성 실패 이동 | SPEC: 빈 S7 + 스낵바 | 일반 UX: S6 Error 유지 가능 | 실패 시 머무를 화면 | P0 |
| D-14 | 저장 동선 편집 성공 응답 | PATCH/PUT 경로만 명시 | 응답 body/status 미정 | 204 또는 갱신 DTO | P0 |
| D-15 | S7→S8 전달 방식 | 숙소 좌표·목표 거리 전달 | route 형식 없음 | SavedStateHandle/typed route/공유 저장소 | P0 |
| D-16 | 다시 추천받기 목적지 | S7 Empty CTA 존재 | S4 이동/즉시 재호출 미정 | 목적지와 상태 보존 범위 | P0 |
| D-17 | 코스 거리 슬라이더 호출 | 값이 연속 변경 | API 호출 비용 존재 | 별도 조회 버튼/디바운스/드래그 종료 호출 | P0 |
| D-18 | 저장 코스 중복 | API POST만 정의 | 동일 sourceCourseId 처리 없음 | 중복 허용/멱등/409 | P0 |
| D-20 | 코스 상세 route | 별도 코스 상세 화면 확인 | saved와 ran의 ID 공간이 다름 | route와 타입 전달 방식 | P0/P1 |
| D-21 | saved/ran 통합 페이징 | API가 목록 2개로 분리 | UI는 러닝코스 세그먼트 1개 | 두 목록 탭 분리/병합 정렬/각자 더보기 | P1 |
| D-22 | 정보 수정 화면 형태 | SPEC 기능만 정의 | 목업에 별도 화면 없음 | 별도 route/시트/마이 내부 | P0 |
| D-23 | 탈퇴 재확인 | DELETE API만 정의 | 재인증/문구 없음 | 비밀번호·카카오 재인증 또는 확인 문구 | P0 |
| D-24 | 대회 이미지 | 데이터에 133개 존재 | API에서는 `imageUrl` 활성화가 P1 | S1/S2/S3 P0에서 이미지 사용 여부 | P0 |
| D-25 | GPS 기록 범위 | SPEC 백로그 AP-22는 P1 | 목업에는 완성 화면 존재 | 공모전 MVP에 포함할지 | 일정 결정 |
| D-26 | 앱 시작 화면 | 세션 확인 정책 존재 | `splash` route/화면 없음 | 별도 Splash UI 또는 시작 로직만 둘지 | P0 |
| D-27 | 로그인 후 원래 행동 복귀 | 게스트 저장 시 로그인 유도 | 복귀 route/action 저장 방식 없음 | 로그인 후 찜/저장 자동 재시도 여부 | P0 |

---

## 11. API 명세에서 보완해야 할 응답 계약

백엔드 구현 전에 아래 항목은 Swagger DTO 수준으로 구체화해야 한다.

| API | 현재 빠진 내용 |
|---|---|
| `PATCH /api/me` | 성공 status와 응답 body |
| `PATCH /api/me/agreements` | 성공 status와 응답 body |
| `PUT /api/me/password` | 성공 status, 현재 비밀번호 불일치 error code |
| `POST /api/me/identities/kakao` | 성공 status와 응답 body |
| `DELETE /api/me/identities/kakao` | 성공 status |
| `PATCH /api/itineraries/.../blocks/{blockId}` | 성공 status와 갱신 응답 |
| `PUT /api/itineraries/.../blocks/order` | 성공 status와 갱신 응답 |
| `GET /api/itineraries` | 마이 카드에 필요한 `region` 또는 표시용 지역 필드 |
| `GET /api/me/courses` | 목록 projection의 정확한 필드와 page 예시 |
| `GET /api/runs` | 목록 요약의 정확한 필드와 page 예시 |
| `GET /api/festivals` | 카드 탭에 사용할 상세 URL/content URL 여부 |

---

## 12. 백엔드 첫 구현 단위 제안

와이어프레임과 병렬로 진행하기 좋은 첫 세로 기능은 `S1 홈 → S2 캘린더 → S3 대회 상세`다.

### 1차 공개 API

1. `GET /api/contests`
2. `GET /api/contests/daily-counts`
3. `GET /api/contests/closing-soon`
4. `GET /api/contests/{contestId}`
5. `GET /api/contests/{contestId}/festivals`
6. `GET /api/festivals`

### 1차 인증 쓰기 API

1. `POST /api/auth/login`
2. `POST /api/auth/refresh`
3. `GET /api/me`
4. `PUT /api/me/favorites/{contestId}`
5. `DELETE /api/me/favorites/{contestId}`

Android는 같은 요청·응답 JSON fixture로 FakeRepository를 먼저 만들고, 백엔드가 준비되면 Retrofit 구현으로 교체한다.
