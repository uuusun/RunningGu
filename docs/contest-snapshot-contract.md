# 서버용 대회 스냅샷 계약 (결정-39·40·46·47)

> **지위**: 데이터 파이프라인(Python) ↔ 백엔드 Importer 사이의 파일 계약.
> 생산자는 `scripts/build_contest_snapshot.py`, 소비자는 백엔드 Importer(AP-07)다.
> SPEC §8.2와 `docs/files/런닝구_API_명세서.md` §3의 "데이터 적재 계약" 문단을 구체화한다.
> 계약 변경은 반드시 PR로 하고, 생산자·소비자 양쪽이 승인한다(AGENTS 4장).

## 1. 파일 경로와 갱신 주기

| 항목 | 값 |
|---|---|
| 경로 | `data/contest_snapshot.json` (저장소 루트 기준, git 추적) |
| 인코딩 | UTF-8 (BOM 없음) · 문자열은 NFC 정규화 |
| 생성 | `python scripts/build_contest_snapshot.py` — 입력은 `data/races_sample.csv` |
| 주기 | 최초 파일 = 초기 시드. 이후 주 1회 재크롤 후 **같은 경로를 재생성**(SPEC §8.2) |
| 결정성 | 같은 입력 CSV → 바이트 단위로 같은 산출물. 타임스탬프는 전부 입력에서 파생하며 생성 시각(wall-clock)을 쓰지 않는다 |

### 1.1 백엔드 전달·실행 (PR #41 리뷰 반영)

- 스냅샷 파일은 백엔드 `bootJar` **리소스에 포함하지 않는다.** 저장소 커밋본이 검토·재현의
  기준이고, 서버에는 배포 파이프라인이 외부 파일(경로 또는 볼륨)로 전달한다.
- Importer는 서버 시작 시 자동 실행이 아니라 **명시적 실행**이다 — 별도 명령(CLI 러너·배치·
  관리자 트리거 중 백엔드 재량)으로 파일 경로를 **인자로** 받는다. 인자가 없으면 저장소 루트
  상대 `data/contest_snapshot.json`을 기본값으로 본다.
- 초기 시드와 주간 갱신은 **같은 명령**이다. 적재는 멱등이므로 같은 파일을 두 번 실행해도
  결과가 같다.

### 1.2 동기화 규칙 — canonical 승계와 하위 테이블 (PR #41 리뷰 반영)

스냅샷은 **전체 상태(full state)이며 증분이 아니다.** Importer의 동기화 규칙:

1. **canonical 식별(승계 포함).** 대회명·일정이 바뀌면 `canonicalKey`도 바뀌지만, 크롤 원천의
   `(sourceType, externalId)`는 재수집 간 안정적이다. 그래서 identity는 소스 키로 잇는다:
   - ① 스냅샷 canonical의 `canonicalKey`가 DB에 있으면 → 그 행을 갱신한다.
   - ② 없으면, 그 canonical의 `sources[]` 키 중 하나라도 DB의 기존 canonical에 붙어 있으면 →
     **신규 insert가 아니라 그 기존 canonical을 갱신**한다(`canonicalKey` 포함 전 필드 교체).
     DB PK(id)가 유지되므로 찜·저장 동선의 참조가 깨지지 않는다. 여러 기존 canonical과 겹치면
     (재병합) 겹치는 소스가 가장 많은 것을 본체로 갱신하고, 나머지 겹친 canonical의 소스는
     본체로 옮긴 뒤 소스가 0개가 된 canonical만 삭제 후보로 로깅한다(자동 삭제는 하지 않는다).
   - ③ 둘 다 없으면 신규 insert.
   - 별도 승계 필드를 스냅샷에 넣지 않는 이유: 생성기는 DB 이전 상태를 모르므로 승계는
     소비자(Importer)만 판정할 수 있다.
2. **스냅샷에 있는 하위만 갱신한다.** `CONTEST_EVENT`는 현재 canonical의 `events[]`로 완전
   대체한다. `CONTEST_SOURCE`는 `(sourceType, externalId)`로 upsert하고, 다른 canonical로
   재병합됐으면 `contest_id`를 옮긴다. 이번 스냅샷에 나타난 source는 `active=true`,
   `consecutive_missing_count=0`으로 즉시 복구한다. 스냅샷에 없다는 이유만으로 source를
   삭제하지 않는다.
3. **누락 판정은 승인된 완전 스냅샷에서만 한다**(결정-46, 이슈 #56).
   - 모든 설정 원천 수집이 성공하고 §4 검증을 통과한 full snapshot만 승인 경로로 승격한다.
     수집 실패·중단·부분 결과는 현재 파일을 교체하거나 Importer에 전달하지 않으므로 누락
     횟수가 변하지 않는다.
   - DB의 기존 source가 첫 번째 승인 스냅샷에서 보이지 않으면
     `consecutive_missing_count=1`, `active=true`로 유지한다. 다음 **서로 다른 승인 스냅샷**에도
     연속 누락되면 count를 2로 고정하고 `active=false`로 바꾼다.
   - 같은 스냅샷을 재적재해도 누락 횟수를 다시 올리지 않는다. Importer는
     `meta.sourceSha256 + meta.checkedAtMax`로 이미 적용한 스냅샷을 식별하고, 이전 스냅샷을
     다시 적용한 누락 상태 후퇴도 금지한다. 물리 판정 규칙은 1.3을 따른다.
   - `CONTEST.active`는 연결된 source 중 하나라도 active면 true, 모두 inactive면 false다.
     비활성 canonical과 과거 대회, 이를 참조하는 찜·저장 동선은 물리 삭제하지 않는다.
     공개 목록·검색·월간 건수·마감 임박만 active 대회로 제한하고 id 상세 조회는 유지한다.

### 1.3 적용 snapshot 이력 — `CONTEST_SNAPSHOT_IMPORT` (결정-47)

Importer는 성공적으로 적용한 snapshot을 다음 물리 계약으로 기록한다.

| 컬럼 | PostgreSQL 타입 | 제약·의미 |
|---|---|---|
| `id` | `BIGINT` | PK |
| `schema_version` | `INTEGER` | snapshot 최상위 `schemaVersion`, NOT NULL |
| `source_sha256` | `VARCHAR(64)` | `meta.sourceSha256`, NOT NULL |
| `checked_at_max` | `TIMESTAMPTZ` | `meta.checkedAtMax`, NOT NULL, UTC 저장 |
| `applied_at` | `TIMESTAMPTZ` | 서버 적용 완료 시각, NOT NULL, UTC 저장 |

- `(source_sha256, checked_at_max)`와 `checked_at_max`는 각각 UNIQUE다. 같은 기준 시각에
  서로 다른 snapshot을 둘 수 없다.
- 같은 쌍이 이미 있으면 성공 no-op로 끝내며 canonical·누락 횟수·`active`를 변경하지 않는다.
- `checked_at_max`가 마지막 성공 이력보다 이르면 거부한다. 마지막 성공 이력과 시각은 같지만
  `source_sha256`이 다를 때도 순서를 판정할 수 없으므로 거부한다.
- 전체 검증과 `CONTEST`·`CONTEST_SOURCE`·`CONTEST_EVENT` 갱신, 이력 insert는 한 트랜잭션이다.
  검증·적재·이력 기록 중 하나라도 실패하면 모두 롤백한다.
- 동시 Importer 실행은 `CONTEST_SNAPSHOT_IMPORT` 잠금 또는 동등한 DB 잠금으로 직렬화해
  최신 이력 확인과 새 이력 insert 사이의 경쟁을 막는다.
- 이 결정은 DB 소비 계약을 구체화한 것이며 snapshot JSON 필드는 바뀌지 않으므로
  파일 `schemaVersion`은 1을 유지한다.

## 2. 최상위 구조

```json
{
  "schemaVersion": 1,
  "meta": { ... },
  "contests": [ { ... } ]
}
```

### 2.1 `meta` — 검증용 집계

| 필드 | 타입 | 설명 |
|---|---|---|
| `source` | string | 입력 파일 저장소 상대 경로 (`data/races_sample.csv`) |
| `sourceSha256` | string | 입력 CSV 파일 바이트의 SHA-256 (hex) |
| `sourceRowCount` | int | 입력 CSV 데이터 행 수 (헤더 제외) |
| `canonicalCount` | int | `contests[]` 길이 |
| `sourceRecordCount` | int | 모든 `sources[]` 길이 합 = `sourceRowCount − len(skipped)` |
| `eventRecordCount` | int | 모든 `events[]` 길이 합 — `CONTEST_EVENT` 적재 행 수 검증용 |
| `skipped` | array | 제외된 원천 행 `{ "externalId", "reason" }`. reason: `MISSING_REQUIRED` |
| `checkedAtMax` | string | 전체 원천 `fetchedAt`의 최댓값 (UTC `Z`) — 스냅샷의 데이터 기준 시각 |

**Importer는 `canonicalCount`·`sourceRecordCount`가 실제 배열 길이와 일치하는지 검증하고,
불일치 시 전체 적재를 거부한다.**

### 2.2 `contests[]` — canonical 대회 (→ `CONTEST` + `CONTEST_EVENT`)

정렬: `(contestDate, canonicalKey)` 오름차순 고정.

| 필드 | 타입 | null | 설명 |
|---|---|---|---|
| `canonicalKey` | string | ✗ | `"{contestDate}|{정규화 이름}"`. 정규화 = NFC → 공백·기호 제거 → 소문자. 병합 그룹 키와 동일하므로 재수집 시 안정적 upsert 키다. **전체에서 유일** |
| `name` | string | ✗ | 대회명 (병합 우선 레코드 기준, §5.4) |
| `region` | string | ✗ | 17개 시도 단축명 (서울·부산·…·제주, SPEC §6.2) 외 값 금지 |
| `place` | string | ✗ | 대회장 (CSV `venue`). API 명세 3-1의 `place` |
| `roadAddress` | string | ✓ | 도로명 주소 → `CONTEST.road_address NULL` |
| `contestDate` | string | ✗ | `YYYY-MM-DD` (KST 기준 날짜) |
| `startTime` | string | ✓ | `HH:MM`. 형식 불일치 시 null → `CONTEST.start_time NULL` |
| `events` | string[] | ✗(빈 배열 허용) | `FULL·HALF·K10·K5` (API 명세 부록 enum). 순서는 이 고정 순서. 빈 배열 = 종목 미표기(§8.2 정책, 현재 2건) → `CONTEST_EVENT` 행 없음 |
| `category` | string | ✗ | `로드·트레일·걷기·야간` (SPEC §6.2) |
| `applyStart` | string | ✓ | `YYYY-MM-DD`. 형식 불일치 시 null |
| `applyEnd` | string | ✓ | `YYYY-MM-DD`. 형식 불일치 시 null |
| `regStatusFallback` | string | ✗ | `OPEN·CLOSED·BEFORE·UNKNOWN`. 서버 `regStatus`는 날짜로 파생(§5.5)하고, 날짜로 판단 불가할 때 이 값을 쓴다(API 명세 3-1). 원천 `접수중→OPEN` `마감→CLOSED` `접수전→BEFORE` `미정/빈값→UNKNOWN` |
| `organizer` | string | ✓ | 주최 |
| `officialUrl` | string | ✓ | 공식 홈페이지 |
| `detailUrl` | string | ✓ | 병합 우선 원천의 상세 페이지 → `CONTEST.detail_url NULL` |
| `imageUrl` | string | ✓ | 포스터. null이면 앱 placeholder(D-24) |
| `lat` / `lng` | number | ✗ | WGS84. **생성기는 좌표 없는 canonical을 만들지 않는다** — 좌표 누락 원천이 생기면 생성이 실패하므로 `add_coordinates.py`를 먼저 돌린다 (SPEC D-09: 재수집 0건 검증) |
| `checkedAt` | string | ✗ | 이 대회 `sources[].fetchedAt`의 최댓값 (UTC `Z`) |
| `sources` | array | ✗(≥1) | 아래 2.3 |

### 2.3 `contests[].sources[]` — 병합 전 원천 (→ `CONTEST_SOURCE`)

정렬: `(sourceType, externalId)` 오름차순 고정.

| 필드 | 타입 | 설명 |
|---|---|---|
| `sourceType` | string | `MARATHON_GO`(마라톤GO) · `MARATHON_ONLINE`(마라톤온라인) |
| `externalId` | string | 크롤러 `race_id`. **`(sourceType, externalId)`는 전체에서 유일** — `CONTEST_SOURCE.sourceKey` |
| `sourceUrl` | string | 원천 상세 URL (`detail_url`) |
| `fetchedAt` | string | 수집 시각. CSV `crawled_at`(naive KST) → UTC `Z` 변환 |
| `lastCheckedDate` | string | `YYYY-MM-DD` (KST) — 병합 우선순위에 쓰인 최근 확인일 |
| `rawPayload` | object | 원본 CSV 행(snake_case 키 그대로). 단 `contact_email`·`contact_phone`·`description`은 **제외** — 개인정보 미보존 원칙. description 자유 텍스트에는 담당자 실명·휴대폰·이메일이 섞여 있어 통째로 뺀다(canonical·공개 API 어디에도 쓰지 않는 필드다). `CONTEST_SOURCE.rawPayload` 복원용 |

## 3. 병합 규칙의 소유권

정규화·region 보정·중복 병합 규칙은 **`scripts/build_races_json.py`가 단일 소유**하고,
스냅샷 생성기는 그 함수를 import해 재사용한다(결정-39: Python-Java 이중 구현 금지의 Python 내부판).
규칙 요약(상세는 해당 스크립트 docstring):

- 병합 키: (NFC·공백/기호 제거·소문자 이름, `event_date`)
- 그룹 내 우선순위: `last_checked` 최신 → 동률 시 마라톤GO. 빈 필드는 후순위 레코드로 보충
- 종목: `has_*` 플래그 우선 + `event_types` 토큰 보강(§5.4). **병합 우선 레코드 기준**
- 필수값(`name`·`event_date`) 누락 행은 경고 후 제외 → `meta.skipped`

## 4. Importer(백엔드) 검증 의무 — 실패 시 전체 롤백

1. `schemaVersion` 지원 여부, UTF-8 디코딩
2. `canonicalKey` 유일 · `(sourceType, externalId)` 유일
3. 필수 필드 non-null · `region`/`events`/`category`/`regStatusFallback` enum 값.
   `startTime`은 null을 허용하고 값이 있으면 `HH:MM`, `roadAddress`·`detailUrl`은 null 또는 string인지 검증
4. `lat/lng` 존재와 위경도 범위 (대한민국 개략 범위 33≤lat≤39, 124≤lng≤132)
5. `meta` 집계 일치 (PR #41 리뷰 반영):
   - `canonicalCount` = `contests[]` 길이
   - `sourceRecordCount` = 모든 `sources[]` 길이 합 = `sourceRowCount − skipped.length`
   - `eventRecordCount` = 모든 `events[]` 길이 합
   - `checkedAtMax` = 전체 `sources[].fetchedAt` 최댓값, canonical별 `checkedAt` = 해당
     `sources[].fetchedAt` 최댓값
6. 모든 설정 원천 수집이 완료된 승인 full snapshot인지 배포 게이트에서 확인. 실패·부분 산출물은
   기존 `data/contest_snapshot.json`을 교체하지 않고 Importer를 실행하지 않음
7. 1.3의 적용 이력으로 동일 snapshot no-op·과거 snapshot·동일 기준 시각의 다른 hash 거부를 판정
8. 하나라도 실패하면 트랜잭션 롤백으로 이전 canonical·누락 횟수·active·적용 이력 유지 (SPEC §8.2)

적재는 멱등이다: 같은 스냅샷을 두 번 적재해도 데이터와 누락 횟수가 같다. 첫 승인 누락은
count 1/active, 서로 다른 다음 승인 스냅샷의 연속 누락은 count 2/inactive, 재등장은 count 0/active가
되어야 한다. 한 canonical에 active source가 하나라도 남으면 canonical은 active를 유지한다.

## 5. 버전 규칙

- 하위 호환 추가(선택 필드 추가)는 `schemaVersion` 유지, 필드 삭제·의미 변경은 +1.
- **Importer는 알 수 없는 필드를 무시한다**(PR #41 리뷰 반영). 선택 필드 추가가 버전을
  올리지 않는 것과 한 쌍인 규칙이다 — unknown 필드를 오류로 거부하지 않는다.
- `schemaVersion`이 올라가면 Importer가 지원 버전을 확인하고 미지원 시 적재를 거부한다.

## 6. 향후 자동화 (참고, P0 범위 밖)

`Python → 인증된 내부 수집 API` 또는 `Python → 스테이징 테이블 → 백엔드 승격`으로 전환한다.
어느 경우에도 Python은 `CONTEST*` 핵심 테이블에 직접 쓰지 않는다(결정-40).
