# 런닝구 — ERD·DFD·API 교차 검증 리포트 v4.4

> **검증일**: 2026-08-20
> **검증 기준**: SPEC v4(결정-22·33 개정, 결정-44·45·46·47 포함) · API 명세 v2.8 · 화면–API 매핑표 v1.8 · 논리 ERD v4.3 · 수정 DFD
> **판정**: P0+P1 논리 모델과 확정된 DB-01·02·03·04·05·06·07·08 계약은 정렬됐다. 남은 `TBD-DB-01`과 `TBD-P1-01`은 해당 물리 컬럼·P1 계약 전에 닫는다.

---

## 1. 데이터 모델 범위

| 단계 | 엔티티 | 상태 |
|---|---|---|
| P0 | `USER`, `LOGIN_IDENTITY`, `USER_AGREEMENT`, `EMAIL_VERIFICATION`, `REFRESH_TOKEN` | 논리 계약 반영 |
| P0 | `CONTEST`, `CONTEST_SOURCE`, `CONTEST_EVENT`, `CONTEST_SNAPSHOT_IMPORT`, `FAVORITE` | 논리 계약 반영 |
| P0 | `ITINERARY`, `ITINERARY_DAY`, `ITINERARY_BLOCK`, `SAVED_COURSE` | 논리 계약 반영 |
| P1 | `RUN_RECORD`, `RUN_TRACK` | 예약 초안. saved/ran 통합 계약은 P1 착수 시 재논의 |

총 16개를 한 ERD에서 보되, **P0 확정 14개와 P1 계약 초안 2개를 같은 확정도로 취급하지 않는다**.

---

## 2. ERD 교차 검증 결과

### 2.1 회원·인증

- `USER 1:1 LOGIN_IDENTITY`이며 가입 시 EMAIL/KAKAO 중 정확히 한 수단만 선택한다.
- `UNIQUE(user_id)`와 `UNIQUE(provider, provider_subject)`를 적용하고 P0 연결·추가·해제·전환을 제공하지 않는다.
- 대표 이메일은 EMAIL의 `provider_subject`, KAKAO의 nullable `email_snapshot`에서 파생한다. `GET /me.email`은 항상 포함하는 `string|null`이며, KAKAO가 이메일을 제공하지 않으면 앱이 이메일 행을 숨긴다.
- EMAIL은 `password_hash`·`email_verified_at`이 필수이고 KAKAO는 둘 다 null이며, nullable `last_login_at`을 유지한다.
- 약관은 `USER_AGREEMENT`에 append-only 이력으로 저장한다.
- 인증 코드·재설정 토큰과 refresh token은 원문이 아니라 hash만 저장한다.
- 이메일 인증 목적은 `SIGNUP/PASSWORD_RESET`으로 통일한다.

### 2.2 대회·찜

- Python 데이터 파이프라인이 정규화·좌표 보정·중복 병합한 결정적 JSON snapshot을 생산한다.
- 백엔드 Importer는 전체 snapshot을 검증하고 `CONTEST`·`CONTEST_SOURCE`·`CONTEST_EVENT`를 한 트랜잭션에서 멱등 upsert한다. Python이 운영 DB를 직접 수정하지 않는다.
- canonical은 `canonical_key`, 원천은 `(source_type, external_id)` 논리 유일키를 가진다.
- 종목은 `K5/K10/HALF/FULL` 4종이며 원천 트레일 표기는 거리 버킷으로 정규화한다.
- `CONTEST.start_time`, `road_address`, `detail_url`, `lat/lng`와 이미지·주최·공식 URL은 nullable이다.
- `FAVORITE(user_id, contest_id)`는 복합 UNIQUE다.
- 대회 원천 식별자는 `externalId/external_id`, 수집 시각은 `fetchedAt/fetched_at`, 주최자는 `organizer`로 통일했다.
- 승인된 완전 snapshot에서 source가 1회 누락되면 count 1/활성 유지, 서로 다른 다음 승인 snapshot에도 연속 누락되면 count 2/비활성이다. 실패·부분 snapshot과 같은 파일 재적재는 횟수를 올리지 않고, 재등장은 count 0/활성으로 복구한다.
- canonical `active`는 활성 source 존재 여부로 결정한다. 비활성·과거 canonical을 물리 삭제하지 않고 공개 탐색에서만 제외하며 찜·동선의 상세 참조는 유지한다(확정-DB-05, 이슈 #56).
- 성공한 snapshot은 `CONTEST_SNAPSHOT_IMPORT(schema_version, source_sha256, checked_at_max, applied_at)`에 같은 트랜잭션으로 기록한다. 같은 `(source_sha256, checked_at_max)`는 no-op, 과거 기준 시각과 동일 기준 시각의 다른 hash는 거부하며 실패한 적재는 이력을 남기지 않는다. 동시 실행은 DB 잠금으로 직렬화한다(확정-DB-08, 결정-47).

### 2.3 저장 동선

- `ITINERARY → ITINERARY_DAY → ITINERARY_BLOCK` 트리를 유지한다.
- `(user_id, contest_id, start_date, end_date)`가 같으면 같은 동선을 교체한다.
- `UNIQUE(itinerary_id, day_index)`와 `UNIQUE(day_id, order_no)`를 논리 제약에 반영했다.
- `RACE` 블록은 `system_managed=true`이고 추가·수정·삭제·이동을 서버가 거부한다.
- 외부 POI 부분 실패를 위해 `place_name/address/lat/lng`는 nullable일 수 있다.
- `region`, `recovery`, 호텔, 일자·블록 트리와 RACE 날짜·시간·장소는 저장 snapshot이다. `contestName`과 현재 대회 메타·active는 `CONTEST`에서 파생한다.
- `needsRegeneration`은 이름·active를 제외한 저장 RACE/region과 현재 날짜·시간·장소·지역·좌표 차이로 파생한다. 자동 재배치하지 않고 사용자 최종 저장 시 `PUT /itineraries/{id}`로 같은 id의 트리를 교체한다(확정-DB-04, 이슈 #55).

### 2.4 저장 코스·P1 러닝 기록

- `SAVED_COURSE`에 `data_source`, `route_fingerprint`, `gain_m`, `elevation_profile_m`, `attributions`를 반영했다.
- `CourseDataSource`는 `API_GPX`, `GPX_ONLY`, `OSM_GENERATED`다.
- OSM 저장 코스에는 `source_course_id`, `region`이 없을 수 있다.
- `(user_id, route_fingerprint)`는 복합 UNIQUE이며 중복 저장은 기존 id를 반환한다.
- fingerprint는 백엔드가 geometry만 정규화해 `v1:` + SHA-256 lowercase hex로 생성하고 `VARCHAR(67)`에 저장한다. 진행 순서를 유지하고 연속 중복 좌표만 제거하며, 메타데이터는 입력에서 제외한다.
- fingerprint 좌표 정밀도와 고도 배열 저장 타입은 `TBD-DB-01`이다.
- `attributions`는 서버가 원천 메타데이터에서 만든 완성 문구 배열을 `JSONB NOT NULL DEFAULT '[]'`로 snapshot 저장한다. 클라이언트 입력은 신뢰하지 않고 저장 코스 상세에만 반환하며, 문구 변경을 소급하지 않고 fingerprint에서도 제외한다(확정-DB-02, 이슈 #54).
- P1은 `RUN_RECORD 1:1 RUN_TRACK`으로 목록 요약과 큰 경로 데이터를 분리한다. 정확한 목록 projection과 saved/ran 통합 페이징은 `TBD-P1-01`이다.

---

## 3. PostgreSQL 밖에 두는 데이터

| 데이터 | 위치·수명 | 이유 |
|---|---|---|
| 축제·숙소·POI·걷기 스팟 | Caffeine TTL | 외부 응답을 영구 마스터로 복제하지 않음 |
| 두루누비 최신 메타 | Caffeine 24시간 + 마지막 성공 fallback | 쿼터·장애 격리 |
| 큐레이션 GPX | 버전 관리 서버 리소스 + 앱 축약 번들 | 경로·고도·라이선스 메타의 기준 |
| OSM 그래프·SRTM | GraphHopper 영속 볼륨 | 배포 단계 생성 후 재기동 시 재사용 |
| OSM 생성 경로 | 요청 중 임시 DTO | 코스 마스터·지역 목록에 적재하지 않음 |

OSM 생성 결과는 사용자가 저장했을 때만 `SAVED_COURSE` snapshot으로 PostgreSQL에 남는다. 별도 `OSM_ROUTE`·`COURSE_MASTER` 테이블을 이번 ERD에 추가하지 않는다.

---

## 4. DFD 검증 결과

- 앱은 KTO·카카오·GraphHopper를 직접 호출하지 않고 Spring Boot API만 호출한다.
- 동선 생성은 백엔드 `POST /itineraries/generate` 단일 주체이며 생성 응답은 저장 전까지 DB에 쓰지 않는다.
- 대회 적재는 `원천 HTML → Python snapshot → 백엔드 Importer → PostgreSQL`이다.
- Importer는 완전 snapshot의 2회 연속 누락만 source 비활성으로 반영하고 재등장을 복구한다.
- 코스 조회는 큐레이션을 우선하고 적격 경로가 0건일 때만 내부 GraphHopper를 호출한다.
- GraphHopper 장애는 큐레이션·카카오 장소 결과와 격리하고, 품질 상한 통과 후보 0건은 정상 Empty로 처리한다.
- P0 마이 데이터와 P1 러닝 기록의 단계가 DFD에서 구분된다.
- Redis 표기를 제거하고 단일 서버 MVP의 Caffeine 계약으로 통일했다.

---

## 5. API·기능 명세 정합성

핵심 제품 계약은 SPEC과 API 명세가 일치한다.

- 저장 코스의 OSM snapshot과 fingerprint 멱등성
- 저장 코스 attribution snapshot과 지역별 코스 출처 응답
- P0 동선 생성의 서버 단일 주체
- canonical 대회 + 원천 snapshot Importer
- 저장 동선 snapshot/current 분리와 사용자 확정 재생성 교체
- 대회 source 2회 연속 누락 비활성화·참조 보존
- 시스템 관리 RACE 블록
- 큐레이션 우선 + OSM/GraphHopper 도시 경로 생성
- GPS 기록과 saved/ran 통합 계약의 P1 분리

다음은 충돌이 아니라 아직 상세화되지 않은 계약이다.

- `GET /api/me/courses` 목록 projection
- `GET /api/runs` P1 목록 projection과 통합 페이징
- 회원 PATCH의 일부 성공 응답

이 항목은 화면–API 매핑표 §11에 유지하며 ERD가 먼저 답을 만들지 않는다.

---

## 6. 물리 스키마 전 필수 결정

| ID | 결정할 내용 |
|---|---|
| `TBD-DB-01` | fingerprint 좌표 정규화 정밀도, 고도 배열 PostgreSQL 타입 |
| `TBD-P1-01` | saved/ran 통합 정렬·페이징과 RUN projection |

비활성 대회의 새 동선 생성 차단 원칙은 확정됐지만 정확한 HTTP status·오류 `code`는 이슈 #56 추가 리뷰 후 API 명세에 보완한다. 그 값이 정해지기 전에는 임의의 오류 계약으로 구현하지 않는다.

확정되어 위 표에서 제거한 항목은 다음과 같다.

- `DB-03`: USER와 LOGIN_IDENTITY 1:1, 대표 이메일 파생, P0 연결·전환 없음
- `DB-02`: 저장 코스 attribution을 서버 생성 완성 문구 배열로 snapshot 저장하고 상세에만 반환. `JSONB NOT NULL DEFAULT '[]'`, fingerprint 제외, 소급 변경 없음. 지역별 코스 응답도 실제 사용 원천의 `attributions[]` 포함
- `DB-04`: 동선 region·recovery·트리/RACE snapshot, 대회명·현재 메타 파생, 변경 감지와 사용자 확정 재생성 교체
- `DB-05`: 승인 full snapshot 2회 연속 source 누락 비활성, 실패·부분 미반영, 재등장 복구, canonical·참조 물리 삭제 없음
- `DB-06`: `externalId`, `PASSWORD_RESET`, `fetchedAt`, `organizer` 명칭 통일
- `DB-07`: 사용자·aggregate 자식 CASCADE, 대회 참조 RESTRICT, Enum CHECK, 좌표 `NUMERIC(10,7)`, 거리 `NUMERIC(8,3)`, 문자열 용도별 길이·nullable·주요 조회 인덱스 명시
- `DB-08`: 대회 출발 시각·도로명 주소·상세 URL nullable 저장, 성공 snapshot 적용 이력의 원자 기록과 동일/과거 snapshot 차단

---

## 7. 구현 수용 테스트로 유지할 항목

- 같은 `user_id`에 두 번째 LOGIN_IDENTITY 삽입 → UNIQUE 위반.
- EMAIL/KAKAO별 필수·null 필드 조합 위반 → CHECK 위반.
- 가입 방식과 다른 provider로 탈퇴 재인증 → `409 REAUTH_PROVIDER_MISMATCH`.
- RACE 블록 PATCH/DELETE/이동 → `409 SYSTEM_BLOCK_IMMUTABLE`.
- canonical 이름만 변경 → 저장 동선 `needsRegeneration=false`; 날짜·시간·장소·지역·좌표 변경 → true이며 기존 RACE snapshot 유지.
- 재생성 미리보기·저장 실패 → 기존 동선 유지; `PUT /itineraries/{id}` 성공 → 같은 id로 새 트리 교체.
- 같은 대회를 여러 원천에서 수집해도 canonical 1개와 source 복수를 유지.
- 잘못된 대회 snapshot은 전체 롤백하고 이전 정상 canonical 유지.
- source 첫 승인 누락 → active/count 1, 두 번째 연속 승인 누락 → inactive/count 2, 같은 snapshot 재적재 → 변화 없음, 재등장 → active/count 0.
- 같은 snapshot 재적재 → 성공 no-op·이력 1행 유지, 과거 snapshot 또는 동일 `checked_at_max`의 다른 hash → 적재 거부, 적재 실패 → 이력 미생성.
- canonical의 source 중 하나만 active여도 canonical active 유지; 모두 inactive일 때만 공개 탐색 제외하고 id 상세는 유지.
- 같은 경로 snapshot을 반복 저장하면 `SAVED_COURSE` 1행과 기존 id 유지.
- 같은 geometry의 attribution 문구가 달라져도 fingerprint는 같고, 기존 저장 행의 attribution snapshot은 소급 변경하지 않음.
- OSM 생성 경로는 저장 전 PostgreSQL에 남지 않고 지역별 코스 목록에도 나타나지 않음.
- P1 RUN_RECORD 생성·삭제 시 RUN_TRACK이 반드시 함께 생성·삭제됨.

---

## 8. 최종 판정

- 논리 ERD: **P0+P1 범위 정렬 완료**
- DFD: **현재 SPEC/API 흐름과 정렬 완료**
- 물리 ERD·Flyway: **DB-02·04·05·08 계약 반영 가능, TBD-DB-01 관련 컬럼은 결정 전 확정 금지**
- API: **핵심 계약 정렬, 화면–API 매핑표 §11 상세화 항목 유지**
