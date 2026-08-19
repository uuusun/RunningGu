# 서버용 대회 스냅샷 계약 (결정-39·40)

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

스냅샷은 **전체 상태(full state)이며 증분이 아니다.** Importer는 `canonicalKey` 기준으로
upsert하고, 스냅샷에 없는 기존 canonical은 **삭제하지 않는다**(과거 대회는 크롤 범위 밖이지만
저장 동선이 참조할 수 있다). 명시적 삭제는 P0 범위 밖이다.

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
| `roadAddress` | string | ✓ | 도로명 주소 |
| `contestDate` | string | ✗ | `YYYY-MM-DD` (KST 기준 날짜) |
| `startTime` | string | ✓ | `HH:MM`. 형식 불일치 시 null |
| `events` | string[] | ✗(빈 배열 허용) | `FULL·HALF·K10·K5` (API 명세 부록 enum). 순서는 이 고정 순서. 빈 배열 = 종목 미표기(§8.2 28건 정책) → `CONTEST_EVENT` 행 없음 |
| `category` | string | ✗ | `로드·트레일·걷기·야간` (SPEC §6.2) |
| `applyStart` | string | ✓ | `YYYY-MM-DD`. 형식 불일치 시 null |
| `applyEnd` | string | ✓ | `YYYY-MM-DD`. 형식 불일치 시 null |
| `regStatusFallback` | string | ✗ | `OPEN·CLOSED·BEFORE·UNKNOWN`. 서버 `regStatus`는 날짜로 파생(§5.5)하고, 날짜로 판단 불가할 때 이 값을 쓴다(API 명세 3-1). 원천 `접수중→OPEN` `마감→CLOSED` `접수전→BEFORE` `미정/빈값→UNKNOWN` |
| `organizer` | string | ✓ | 주최 |
| `officialUrl` | string | ✓ | 공식 홈페이지 |
| `detailUrl` | string | ✓ | 병합 우선 원천의 상세 페이지 |
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
3. 필수 필드 non-null · `region`/`events`/`category`/`regStatusFallback` enum 값
4. `lat/lng` 존재와 위경도 범위 (대한민국 개략 범위 33≤lat≤39, 124≤lng≤132)
5. `meta` 집계 = 실제 배열 길이
6. 하나라도 실패하면 트랜잭션 롤백으로 이전 canonical 유지 (SPEC §8.2)

적재는 멱등이다: 같은 스냅샷을 두 번 적재해도 결과가 같다.

## 5. 버전 규칙

- 하위 호환 추가(선택 필드 추가)는 `schemaVersion` 유지, 필드 삭제·의미 변경은 +1.
- `schemaVersion`이 올라가면 Importer가 지원 버전을 확인하고 미지원 시 적재를 거부한다.

## 6. 향후 자동화 (참고, P0 범위 밖)

`Python → 인증된 내부 수집 API` 또는 `Python → 스테이징 테이블 → 백엔드 승격`으로 전환한다.
어느 경우에도 Python은 `CONTEST*` 핵심 테이블에 직접 쓰지 않는다(결정-40).
