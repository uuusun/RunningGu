# 런닝구 백엔드

Java 21 · Spring Boot · PostgreSQL 기반 런닝구 API 서버다. API 계약은
`docs/files/런닝구_API_명세서.md`, 제품 정책은 `SPEC.md`를 기준으로 한다.

## 준비물

- JDK 21
- Docker Desktop 또는 PostgreSQL 17
- 내 주변 자동 경로를 사용할 때 GraphHopper용 메모리 6GB와 디스크 약 1GB

## 로컬 PostgreSQL

```powershell
cd backend
Copy-Item .env.example .env
# .env의 DB_PASSWORD를 로컬 값으로 채운다.
docker compose up -d
```

Compose의 PostgreSQL은 운영과 같은 기반을 검증하도록 `postgres:17.10-trixie`에
pgBackRest `2.55.1-1`을 설치한 저장소 관리 이미지를 빌드한다. 로컬에서는 WAL 아카이빙과
S3 백업을 켜지 않으며 `compose.ec2.yaml`을 함께 적용할 때만 활성화된다.

`.env`는 Docker Compose가 읽지만 Spring Boot가 직접 읽지는 않는다. `bootRun`을 실행할 때는
IDE 실행 구성 또는 셸 환경변수에 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 주입한다.
`deploy/env/*.env.example`은 EC2 배포 전용 예시이며 로컬 `.env.example`을 대체하지 않는다.

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:DB_URL = 'jdbc:postgresql://localhost:5432/runninggu'
$env:DB_USERNAME = 'runninggu'
$env:DB_PASSWORD = '<로컬 비밀번호>'
$env:JWT_SECRET = '<Base64로 인코딩한 32바이트 이상 키>'
$env:KAKAO_REST_KEY = '<서버용 REST API 키>'
$env:KAKAO_APP_ID = '<카카오 Developers 내 앱의 숫자형 앱 ID>'
$env:KTO_SERVICE_KEY = '<디코딩된 한국관광공사 서비스 키>'
$env:PASSWORD_RESET_URL = 'http://localhost:8080/reset-password'
$env:GRAPHHOPPER_ENABLED = 'true'
$env:GRAPHHOPPER_BASE_URL = 'http://localhost:8989'
.\gradlew.bat bootRun
```

`JWT_SECRET`은 HS256 서명 키이며 Base64 디코딩 결과가 최소 32바이트여야 한다. 로컬용 키는
`[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`로 생성할 수
있고 저장소에 커밋하지 않는다. 누락·Base64 형식 오류·길이 미달이면 서버는 기동하지 않는다.

비밀번호 재설정 메일을 발송하려면 SMTP 환경변수와 `MAIL_ENABLED=true`를 설정한다.
`PASSWORD_RESET_URL`은 사용자가 접근할 수 있는 백엔드의 `/reset-password` 공개 URL이며,
운영에서는 HTTPS 절대 URL을 사용한다. 링크 토큰은 30분 동안 한 번만 사용할 수 있고 서버에는
SHA-256 해시만 저장한다.

## 계정 보안 API

- `PUT /api/me/password`: EMAIL 계정의 현재 비밀번호를 확인하고 비밀번호와 전체 세션을
  원자적으로 교체한 뒤 현재 기기의 새 token pair를 반환한다.
- `POST /api/auth/password/reset-request`: 가입 여부와 관계없이 같은 `202`를 반환하며, EMAIL
  계정에만 30분짜리 일회용 링크를 발송한다. `/reset-password` 웹 페이지가 새 비밀번호를 받는다.
- `POST /api/me/reauth`: EMAIL은 비밀번호, KAKAO는 새 SDK access token의 회원번호를 검증해
  탈퇴 전용 5분 reauth token을 발급한다.
- `DELETE /api/me`: `X-Reauth-Token`을 검증하고 전체 refresh token과 사용자 종속 데이터를
  삭제한다. 탈퇴 후 기존 access/refresh token은 모두 사용할 수 없다.

비밀번호 원문·재설정 token·refresh token은 저장하거나 로그에 남기지 않는다. 비밀번호 변경·
재설정·탈퇴는 모두 전체 세션 무효화 계약을 따른다.

`KAKAO_REST_KEY`는 지오코딩·POI 등 카카오 로컬 프록시에만 사용하며 앱이나 저장소에
포함하지 않는다. 키가 없으면 대회 공개 조회는 계속 동작하지만 카카오 로컬 프록시는
`502 EXTERNAL_API_ERROR`를 반환한다.

`KAKAO_APP_ID`는 카카오 로그인 액세스 토큰이 런닝구 카카오 앱에서 발급됐는지 검증하는
숫자형 앱 ID다. REST API 키·네이티브 앱 키와 다른 값이며, 카카오 Developers의 같은 앱에서
확인해 서버 환경변수로 넣는다. 누락하거나 0 이하이면 서버는 기동하지 않는다.

`KTO_SERVICE_KEY`는 홈·대회 인근 축제, POI, 두루누비 코스 메타 동기화의 한국관광공사 클라이언트가 함께 사용하며,
HTTP 클라이언트가 쿼리를 인코딩하므로 디코딩 키를 사용한다. 키가 없으면 홈·대회 인근
축제 API는 `502 EXTERNAL_API_ERROR`를 반환한다. POI는 KTO 원천을 실패로 처리하고, 다른
원천에서 한 건 이상 표시할 수 있을 때만 부분 성공 `200`을 반환한다. 다른 공개 API는 계속
동작한다. 두루누비 키가 없거나 전체 페이지 동기화가 실패하면 현재 코스 snapshot을 유지한다.
홈 축제는 전국 월간 결과를 5분, 대회 인근 축제는 대회별 결과를 하루 캐시한다.

## GraphHopper 러닝 경로

`GET /api/courses/near`는 적격 큐레이션 경로가 없을 때만 별도 GraphHopper 11 프로세스에
`run` 프로파일의 순환 경로를 요청한다. 한국 OSM PBF를 저장소 밖의 ignore 대상 경로에 내려받고
`routing` 프로필을 켜서 실행한다.

```powershell
New-Item -ItemType Directory -Force ..\.cache\osm
curl.exe -L -o ..\.cache\osm\korea.osm.pbf https://download.geofabrik.de/asia/south-korea-latest.osm.pbf
docker compose --profile routing up -d --build postgres graphhopper
```

첫 실행은 OSM 그래프와 SRTM 고도 캐시를 만드는 데 수분이 걸린다. `runninggu-graphhopper-graph`와
`runninggu-graphhopper-srtm` 볼륨은 컨테이너를 재기동해도 유지된다. PBF를 갱신해 그래프를 다시
만들 때는 기존 그래프와의 버전 일관성을 확인한 뒤 별도 배포 절차로 볼륨을 교체한다.

Spring Boot에는 `GRAPHHOPPER_ENABLED=true`와 호스트 실행 기준
`GRAPHHOPPER_BASE_URL=http://localhost:8989`를 설정한다. GraphHopper가 꺼져 있거나 호출에
실패하면 OSM만 `degradedSources`로 표시하고 큐레이션·카카오 장소 결과는 유지한다. 품질 상한을
통과한 후보가 없는 경우는 장애가 아니라 정상 0건이다.

## 두루누비 코스 catalog

빌드 시 저장소 루트의 `data/courses.json`을 JAR의 `data/courses.json`으로 포함한다. 서버는 이
번들을 동기 로드·검증하고 261개 미만이거나 스키마·좌표·유일키 계약이 깨지면 시작하지 않는다.
준비 완료 후 KTO `courseList` 전체 페이지를 한 번 읽고, 성공한 전체 결과만 새 불변 snapshot으로
원자 교체한다. 이후 실행은 직전 완료 시점부터 24시간 뒤다. 외부 실패와 API에서 사라진 코스는
기존 GPX 번들 조회를 막거나 삭제하지 않는다.

테스트나 외부 호출 없는 로컬 확인에서는 `COURSE_SYNC_ENABLED=false`로 시작 동기화만 끌 수 있다.
번들 스키마와 결합 정책은 `docs/course-bundle-contract.md`를 따른다.

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>

아직 구현되지 않은 API 경로는 보안 설정에서 기본 거부한다. 공개·인증 경로는 각 기능을 구현할 때
API 계약에 맞춰 명시적으로 연다.

## 리버스 프록시와 클라이언트 IP

이메일·닉네임 중복 확인은 클라이언트 IP별 요청 제한을 적용한다. 로컬 또는 서버 직접 연결은
`FORWARD_HEADERS_STRATEGY=none`을 유지한다. 신뢰 가능한 로드밸런서·리버스 프록시 뒤에
배포할 때는 `FORWARD_HEADERS_STRATEGY=framework`를 반드시 설정해야 한다. 설정하지 않으면
모든 사용자가 프록시 IP 하나의 30회/분 버킷을 공유한다.

`framework`는 원본 서버로의 직접 접근을 막고 프록시가 외부에서 들어온 `Forwarded`와
`X-Forwarded-*` 헤더를 제거한 뒤 신뢰한 값으로 다시 설정하는 환경에서만 사용한다. 그렇지
않으면 클라이언트가 전달 헤더를 위조해 IP 제한을 우회할 수 있다. 애플리케이션 코드는 전달
헤더를 직접 파싱하지 않고 Spring의 전달 헤더 처리 후 `request.getRemoteAddr()`만 사용한다.

## 대회 snapshot 적재

Importer는 서버 시작 때 자동 실행하지 않는다. DB 환경변수를 설정한 뒤 저장소 루트의
`data/contest_snapshot.json`을 명시적으로 적재한다.

```powershell
cd backend
.\gradlew.bat contestImport
```

다른 파일을 적재할 때는 저장소 루트 기준 또는 절대 경로를 넘긴다.

```powershell
.\gradlew.bat contestImport -PsnapshotPath=C:\snapshots\contest_snapshot.json
```

검증 실패, 과거 snapshot, 동일 기준 시각의 다른 snapshot 파일 hash는 전체 롤백한다. 같은
`snapshot_sha256 + checked_at_max`를 다시 실행하면 성공 no-op다. `source_sha256`은 입력 CSV
출처 추적용이며 snapshot 식별에는 사용하지 않는다.

Gradle `contestImport`는 로컬 개발용이다. EC2는 소스·Gradle·Playwright를 설치해 빌드하지 않고,
CI가 검증한 `runninggu-contest-import.jar`를 사용한다. 이 JAR은 비웹 컨텍스트라 JWT·카카오
로그인 설정을 로드하지 않으며, 배포용 one-shot 서비스는 `COURSE_SYNC_ENABLED=false`를 강제한다.
빈 DB에서 먼저 실행하면 Flyway 마이그레이션 후 snapshot을 적재한다.

CI와 같은 EC2 배포 디렉터리를 로컬에서 만들 때는 다음 태스크를 사용한다.

```powershell
cd backend
.\gradlew.bat clean test ec2Artifact --console=plain
```

산출물은 `backend/build/ec2-artifact/`에 생성된다. 실제 EC2 배포 순서와 롤백은
[`docs/deploy/aws-ec2-staging-runbook.md`](../docs/deploy/aws-ec2-staging-runbook.md)를 따른다.

## 검증

통합 테스트는 Testcontainers로 PostgreSQL 17을 실행하므로 Docker가 켜져 있어야 한다.
비밀번호 재설정 공개 웹 페이지는 Playwright Chromium으로 실제 입력·제출 동작을 검증한다.
최초 한 번 브라우저 바이너리를 설치한다.

```powershell
cd backend
.\gradlew.bat playwright --args="install chromium"
```

```powershell
cd backend
.\gradlew.bat clean test bootJar --console=plain
```

테스트 리포트는 `backend/build/reports/tests/test/index.html`, 실행 JAR은
`backend/build/libs/runninggu-server-0.0.1-SNAPSHOT.jar`에 생성된다.
