# 런닝구 — ERD·DFD·API 교차 검증 리포트 v2

> **검증일**: 2026-07-30
> **검증 기준**: SPEC v4 · 화면별 데이터정리 v3 · API 명세 v2.0 · 수정 ERD/DFD · HTML 목업
> **결론**: 기존 검증에서 발견한 구조 충돌을 권장안으로 해소했다. 아래 항목은 구현 시 수용 테스트로 유지한다.

---

## 1. 채택한 결정과 이유

| 영역 | 권장 결정(채택) | 이유 |
|---|---|---|
| 서버 구조 | 서버 중심 온라인 SSOT + 제한적 오프라인 폴백 | 계정·찜·동선의 충돌을 피하고 MVP 동기화 복잡도를 줄인다. |
| 숙소 | 카카오 Local AD5 실시간 프록시, KTO 32 폴백, 저장 시 스냅샷 | 숙소 커버리지는 카카오가 우선이며 API 키를 앱에서 격리할 수 있다. 영구 숙소 마스터 DB는 만들지 않는다. |
| 축제 | KTO `searchFestival2` 실시간 프록시 + 대회별 1일 TTL | 행사 기간을 기준으로 찾고 위치는 서버 Haversine으로 후처리한다. 영구 축제 마스터 DB는 만들지 않는다. |
| 코스 | 두루누비 API 메타 동기화 + GPX 경로 결합 | API는 최신 메타, GPX는 실제 경로 좌표를 제공한다. 어느 한쪽만으로는 요구사항을 완성하기 어렵다. |
| 대회 | `CONTEST` canonical + `CONTEST_SOURCE` 원본 분리 | 중복 병합된 서비스 레코드와 원천 추적·감사를 동시에 만족한다. |
| 계정 | `USER` + `LOGIN_IDENTITY(EMAIL/KAKAO)`, 명시적 연결 | 카카오 이메일은 없거나 바뀔 수 있으므로 자동 병합 키로 안전하지 않다. |
| 대회 블록 | RACE는 시스템 관리, 사용자 수정·삭제·이동 금지 | 대회 원본과 저장 동선의 정합성을 유지한다. |
| API 계약 | ProblemDetail·대문자 Enum·불투명 cursor·Pageable·KST/UTC | 앱/서버의 오류·상태·페이징 해석 차이를 사전에 제거한다. |
| 문자 인코딩 | 스크립트 UTF-8 강제 + CI 검증 | Windows 콘솔 설정에 의존하지 않고 재현 가능한 데이터 산출물을 만든다. |

---

## 2. ERD 검증 결과

### 2.1 반영 완료

- `USER 1:N LOGIN_IDENTITY`: 한 회원이 EMAIL과 KAKAO를 동시에 사용할 수 있다.
- `(provider, provider_subject)` 전역 유일, 마지막 로그인 수단 해제 금지.
- 약관은 `USER_AGREEMENT`에서 종류·버전·동의 여부·변경 시각을 보존한다.
- `CONTEST 1:N CONTEST_SOURCE`, `CONTEST 1:N CONTEST_EVENT`로 canonical과 원천/종목을 분리한다.
- `ITINERARY → ITINERARY_DAY → ITINERARY_BLOCK` 구조와 장소 스냅샷을 유지한다.
- `ITINERARY_BLOCK.block_type=RACE`는 시스템 관리이며 `contest_id`를 참조한다.
- `RUN_RECORD 1:1 RUN_TRACK`으로 목록 요약과 큰 경로 데이터를 분리한다.
- `FAVORITE(user_id, contest_id)`와 저장 동선 자연키에 복합 UNIQUE를 적용한다.
- PostgreSQL `timestamptz`, `numeric(10,7)`, `jsonb` 사용을 명시했다.

### 2.2 구현 수용 테스트

- 다른 USER에 연결된 카카오 회원번호 연결 → `409 IDENTITY_ALREADY_LINKED`.
- 마지막 로그인 수단 해제 → `409 LAST_IDENTITY_REQUIRED`.
- RACE 블록 PATCH/DELETE/이동 → `409 SYSTEM_BLOCK_IMMUTABLE`.
- 같은 대회를 여러 원천에서 수집해도 canonical은 1개, source는 복수로 유지.
- RUN_RECORD 생성 시 RUN_TRACK이 반드시 함께 생성되고 삭제 시 함께 제거.

---

## 3. DFD 검증 결과

### 3.1 반영 완료

- 앱은 백엔드 API를 온라인 SSOT로 사용하고 Room/assets는 제한적 폴백으로만 사용한다.
- 숙소·일반 POI·걷기 스팟은 카카오/KTO 프록시, 축제는 `searchFestival2` 프록시로 분리했다.
- 대회 수집은 원본 저장 → 정규화/병합 → canonical 갱신 흐름이다.
- 코스는 두루누비 메타 동기화와 GPX 리소스를 `courseId`로 결합한다.
- 두루누비 장애 시 마지막 성공 캐시/GPX로 fail-open한다.
- 인증은 SMTP, EMAIL_VERIFICATION, REFRESH_TOKEN까지 포함한다.
- 대회 RACE 블록은 canonical에서 서버가 생성·갱신하며 앱 입력을 신뢰하지 않는다.

### 3.2 오프라인 경계

| 가능 | 불가 |
|---|---|
| 마지막 성공 대회/마이 목록 읽기 | 로그인·토큰 갱신 |
| 앱 GPX 축약본으로 코스 탐색 | 숙소·축제·POI 새 조회 |
| GPS 기록의 전송 전 임시 저장 | 새 동선 생성·찜·저장 변경 |

---

## 4. API 계약 오류와 권장 해결안

| 오류 가능성 | 해결안 | 이유 |
|---|---|---|
| 목록과 월간 점의 필터 불일치 | 검색 Predicate와 `deriveRegStatus` 정책 테스트 공유 | 같은 조건을 두 번 구현하는 드리프트를 방지 |
| `date` 필터 누락 | 공통 Predicate에 `contestDate == date` 포함 | 선택 날짜와 목록 결과 정합성 |
| 커서 내부 구조 노출 | URL-safe 불투명 `nextCursor` | 내부 키 변경과 잘못된 클라이언트 조립 방지 |
| Enum 한글/소문자 혼용 | JSON Enum 대문자 고정 | Kotlin/Java 직렬화 계약 단순화 |
| 외부 장애가 Empty로 보임 | 502/504 ProblemDetail과 Empty 분리 | 사용자가 “결과 없음”과 “조회 실패”를 구분 |
| RACE 보호를 클라이언트에만 의존 | 서버에서 모든 변경 경로 차단 | 변조 요청과 오래된 앱에서도 정책 보장 |
| timestamp 시간대 혼용 | 날짜 판정 KST, 저장·JSON timestamp UTC `Z` | D-day/접수상태와 로그 정렬 오류 방지 |
| 개인 목록/대회 목록 페이징 혼용 | 대회 cursor, 개인 Pageable | 대회는 안정적 미래 날짜 순회, 개인 목록은 전체 건수/페이지 UI에 적합 |

---

## 5. 남은 외부 확인

- 두루누비 `courseId`와 GPX 261코스의 실제 매칭률 및 `API_ONLY/GPX_ONLY` 건수.
- KTO·두루누비 운영키 쿼터와 캐시 허용 범위.
- 카카오 비즈앱의 이메일 동의 항목 사용 가능 여부. 단, 이메일 미제공이어도 계정 정책은 정상 동작해야 한다.

이 세 항목은 구현을 막지 않는다. 매칭 통계·nullable 이메일·TTL 설정값으로 격리하고 운영 확인 후 조정한다.

---

## 6. 최종 판정

- ERD: **구현 가능**
- DFD: **구현 가능**
- API 계약: **구현 가능, 계약 테스트 필수**
- HTML 목업: **정책 표현 보강 후 UI 기준으로 사용**
- 데이터 생성: **UTF-8·결정성 CI 통과를 병합 조건으로 사용**
