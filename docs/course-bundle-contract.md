# 두루누비 코스 번들·동기화 계약

> 기준일: 2026-08-21
> 지위: `SPEC.md` §5.8·§8.4와 `docs/files/런닝구_API_명세서.md` §6을 구현하기 위한
> 데이터 생산자(`scripts/`) ↔ 백엔드 소비자 계약
> 범위: AP-23 두루누비 메타 동기화와 `GET /api/courses`,
> `GET /api/courses/regions`

## 1. 확정 사항

| 항목 | 계약 |
|---|---|
| 서버 기준 파일 | 저장소 루트 `data/courses.json` |
| 런타임 저장 | PostgreSQL 테이블을 추가하지 않고, 검증 완료 번들에서 시작한 불변 메모리 snapshot을 원자적으로 교체한다 |
| 초기화 | classpath의 `data/courses.json`을 동기 로드·검증한 뒤 첫 snapshot으로 공개한다 |
| 외부 동기화 | 서버 준비 완료 후 한 번 실행하고, 직전 실행 완료 시점부터 24시간 간격으로 다시 실행한다 |
| 외부 실패 | 현재 정상 snapshot을 교체하지 않는다. 첫 외부 동기화 전에도 번들 snapshot으로 조회를 제공한다 |
| 코스 보존 | 두루누비 API 응답만으로 261개 GPX 기준 코스를 덮어쓰거나 삭제하지 않는다 |
| 식별자 | `courseId`가 번들·KTO 메타 결합과 공개 응답의 안정적 유일키다 |
| 최신 시각 | 현재 프로세스에서 KTO 메타 결합에 성공한 항목만 `syncedAt`을 가진다. 번들 fallback·`GPX_ONLY`는 `null`이다 |
| DB 적용 시점 | 사용자 저장 코스 snapshot은 별도 `/api/me/courses` 계약에서만 PostgreSQL에 저장한다 |

운영 P0는 단일 Spring Boot 인스턴스를 전제로 하므로 분산 스케줄러 락을 두지 않는다. 같은
프로세스 안에서는 한 번에 동기화 하나만 실행하며, 앞선 실행이 끝나지 않았으면 다음 실행을
건너뛴다. 다중 인스턴스 배포로 바꿀 때만 분산 락과 snapshot 공유 저장소를 별도 계약한다.

## 2. 데이터 흐름

```text
reference-web/src/data/durunubi_courses.json (261코스 GPX 파싱 시드)
        + 두루누비 courseList 메타 + GPX 고도
        ↓ scripts/build_courses.py
data/courses.json (결정적 서버 번들)
        ↓ backend processResources
classpath:data/courses.json
        ↓ 시작 시 검증·불변 snapshot 공개
KTO courseList 전체 페이지 ── 성공한 전체 응답만 courseId로 결합
        ↓
새 불변 snapshot 원자 교체
        ├─ GET /api/courses
        └─ GET /api/courses/regions
```

백엔드는 `reference-web/` 파일을 직접 읽지 않는다. Python 생산물이 경계이며, 원본 GPX와
`.cache/`는 용량 때문에 커밋하지 않는다. 정규화·축약된 `data/courses.json`만 서버 빌드
리소스에 포함한다.

## 3. 파일 스키마 v1

```json
{
  "schemaVersion": 1,
  "sources": [
    {
      "key": "durunubi",
      "attribution": "두루누비 걷기길(한국관광공사)",
      "license": "공공데이터포털 이용약관 — 출처표시",
      "derivable": true
    }
  ],
  "courses": [
    {
      "courseId": "T_CRS_MNG0000005117",
      "source": "durunubi",
      "dataSource": "API_GPX",
      "courseName": "남파랑길 2코스",
      "sido": "부산",
      "sigun": "부산 중구",
      "distanceKm": 19.0,
      "gainM": 732,
      "difficulty": "NORMAL",
      "cycle": "비순환형",
      "summary": "코스 설명",
      "points": [
        [35.11454, 129.04076, 12.4, 0.0],
        [35.11503, 129.04121, 13.1, 0.7]
      ]
    }
  ]
}
```

### 3.1 최상위

| 필드 | 규칙 |
|---|---|
| `schemaVersion` | 정수 `1`. 모르는 버전은 소비자가 거부한다 |
| `sources` | `key` 오름차순 원천 메타 배열. `key`는 파일 안에서 유일하다 |
| `sources[].attribution` | API 응답에 그대로 사용할 검증 완료 완성 문구 |
| `sources[].license` | 원본 라이선스 확인 결과. 빈 문자열 금지 |
| `sources[].derivable` | 라이선스상 코스 절단·축약 등 파생 경로 제작·배포가 허용됨을 확인했으면 `true`다. 변경 금지 또는 허용 여부 미확인 원천은 `false`이며 P0 서버 번들에서 제외한다 |
| `courses` | `courseId` 오름차순의 코스 배열 |

`generatedAt`처럼 실행 시각에 따라 달라지는 필드는 두지 않는다. 같은 입력과 옵션은 UTF-8
바이트가 같은 파일을 만들어야 한다.

### 3.2 코스

| 필드 | 규칙 |
|---|---|
| `courseId` | 비어 있지 않은 문자열, 파일 전체 UNIQUE. 두루누비는 `crsIdx`를 그대로 쓴다 |
| `source` | `sources[].key` 중 하나를 참조한다 |
| `dataSource` | `API_GPX \| GPX_ONLY`. `API_ONLY`, `OSM_GENERATED`는 번들에 넣지 않는다 |
| `courseName` | NFC 정규화한 비어 있지 않은 이름 |
| `sido` | 앱·대회 필터와 같은 17개 시도 단축명 중 하나 |
| `sigun` | 좌표 기준 재검증한 비어 있지 않은 시군구 표시 문자열 |
| `distanceKm` | GPX 원본 해상도 좌표로 계산한 양수, 소수점 한 자리 |
| `gainM` | GPX 원본 해상도로 계산한 누적 상승고도, 0 이상 정수 |
| `difficulty` | 전체 원본 코스 기준 `EASY \| NORMAL \| HARD` |
| `cycle` · `summary` | NFC 문자열, 원천에 없으면 빈 문자열 |
| `points` | 최소 2개. 각 점은 `[lat, lng, eleM, cumGainM]` 네 숫자다 |

`points`의 좌표는 WGS84이고 `lat=-90~90`, `lng=-180~180`이다. `cumGainM`은 0 이상
비감소해야 한다. 거리·상승고도는 축약 전 GPX로 계산하고, 축약된 점에는 해당 원본 지점의
고도와 누적 상승고도를 싣는다. 원본에서 고도를 얻지 못한 코스는 `eleM=0`, `cumGainM=0`,
`gainM=0`으로 보존할 수 있지만 내 주변 자동 경로 후보에서는 제외한다.

## 4. 생산자 검증

`scripts/build_courses.py`는 파일을 쓰기 전에 다음을 전부 검증한다.

1. JSON은 UTF-8이고 모든 필수 필드와 enum이 유효하다.
2. `courseId`와 원천 `key`가 각각 유일하다.
3. 모든 코스가 존재하는 원천을 참조하고 P0에서 `derivable=true`다.
4. 점이 2개 미만인 코스는 파일 생성을 실패시킨다.
5. 경로가 없는 `API_ONLY` 코스는 서비스 대상에서 제외한다.
6. 두루누비 API에 없는 시드 코스도 `GPX_ONLY`로 남긴다.
7. `GPX_ONLY`가 0건이면 시드 누락 가능성으로 실패한다.
8. 같은 입력으로 두 번 실행한 파일의 SHA-256이 같다.

261은 현재 두루누비 시드 기준 수치이지 영구 상수는 아니다. 다만 승인된 시드나 라이선스
검증 완료 원천을 명시적으로 변경하지 않은 실행에서 코스 수가 261 미만이면 새 파일을
승격하지 않는다.

## 5. KTO 동기화와 결합

### 5.1 성공 조건

- `courseList`를 `brdDiv=DNWW`, `MobileOS=ETC`, `MobileApp=RunningGu`, JSON으로 요청한다.
- `totalCount`까지 모든 페이지를 받아야 한 번의 성공이다.
- JSON 요청에 XML 오류가 오거나, HTTP 오류·timeout·파싱 실패·중복 `crsIdx`가 있으면 전체
  실행을 실패로 처리한다.
- 0건 응답은 정상적인 전체 삭제로 해석하지 않고 실패로 처리한다.
- 실패한 실행은 기존 snapshot을 부분 수정하지 않는다.

### 5.2 필드 우선순위

| 값 | 우선 원천 |
|---|---|
| `courseId`, `source`, points, `distanceKm`, `gainM`, `sido`, `sigun`, attribution | 검증된 번들 |
| `courseName`, `difficulty`, `cycle`, `summary` | 유효한 KTO 최신 메타, 없거나 잘못됐으면 번들 |
| `dataSource` | 최신 전체 KTO 응답에 `courseId`가 있으면 `API_GPX`, 없으면 `GPX_ONLY` |
| `syncedAt` | 현재 프로세스에서 성공한 전체 동기화의 완료 UTC 시각. `GPX_ONLY`는 `null` |

KTO의 선언 거리와 지역 문자열은 검증 통계에는 사용하지만, 실제 GPX 거리와 좌표 기준 지역을
덮어쓰지 않는다. 최신 API에만 있고 번들 경로가 없는 `courseId`는 `API_ONLY`로 집계한 뒤
조회 대상에서 제외한다. 새 경로는 데이터 생산자가 GPX·라이선스를 검증해 다음 번들 버전에
넣은 뒤 서비스한다.

### 5.3 통계와 로그

성공·실패마다 다음 수치를 구조화해 남긴다.

- 번들 코스 수
- KTO 메타 수
- `API_GPX` 매칭 수
- `GPX_ONLY` 수
- 경로가 없어 제외한 `API_ONLY` 수
- 잘못된 메타 필드 수
- 동기화 성공 여부와 소요 시간

서비스 키, 응답 원문, 사용자 좌표는 로그에 남기지 않는다.

## 6. snapshot과 장애 정책

번들 파일이 없거나 스키마·유일키·좌표 검증에 실패하면 배포 산출물 결함이므로 서버 시작을
실패시킨다. 정상 번들을 공개한 뒤 발생한 KTO 장애는 서버 시작과 다른 API를 막지 않는다.

동기화는 새 목록·지역 집계·원천 attribution을 별도 불변 객체에 모두 만든 뒤 한 번에
교체한다. 조회 요청은 교체 전 또는 교체 후 snapshot 중 하나만 보며 중간 상태를 보지 않는다.
PostgreSQL과 Caffeine은 이 catalog의 SSOT로 사용하지 않는다.

## 7. 지역별 조회 계약

### `GET /api/courses`

- 번들·최신 메타가 결합된 큐레이션만 반환하고 `OSM_GENERATED`는 제외한다.
- `region`이 있으면 NFC·앞뒤 공백 제거 후 `sido`와 정확히 일치하는 코스만 남긴다.
- 정렬은 `distanceKm ASC, courseId ASC`다.
- `courseId`는 현재 snapshot 안에서 유일하다.
- `syncedAt`은 nullable UTC `Z`다. 현재 프로세스의 성공한 KTO 결합 전 번들 fallback과
  `GPX_ONLY`는 `null`이다.
- `attributions[]`는 현재 `content[]`에 실제 포함된 원천만 원천 배열 순서로 반환한다.
  빈 페이지는 `[]`이다.

### `GET /api/courses/regions`

- 같은 snapshot의 서비스 대상 코스만 `sido`별로 센다.
- 코스가 0개인 시도는 응답에 만들지 않는다.
- 정렬은 `count DESC, region ASC`다.
- 응답 `count`의 합은 필터 없는 `/api/courses`의 `totalElements`와 같아야 한다.

KTO 동기화 실패만으로 두 API를 Error로 바꾸지 않는다. 번들 또는 마지막 정상 snapshot을
계속 `200`으로 제공한다.

## 8. 이번 계약에서 제외

- `GET /api/courses/near`의 구간 생성
- GraphHopper/OSM fallback
- 카카오 걷기 스팟 결합
- `NaturePoiBundleSource`에서 코스를 NATURE POI로 바꾸는 규칙
- 저장 코스·마이 PostgreSQL 영속화
- 원본 GPX·KTO 메타의 PostgreSQL 마스터 테이블

위 항목은 이 catalog를 읽을 수 있지만 별도 HTTP·변환·저장 계약을 먼저 확정한다.
