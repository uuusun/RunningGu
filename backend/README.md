# 런닝구 백엔드

Java 21 · Spring Boot · PostgreSQL 기반 런닝구 API 서버다. API 계약은
`docs/files/런닝구_API_명세서.md`, 제품 정책은 `SPEC.md`를 기준으로 한다.

## 준비물

- JDK 21
- Docker Desktop 또는 PostgreSQL 17

## 로컬 PostgreSQL

```powershell
cd backend
Copy-Item .env.example .env
# .env의 DB_PASSWORD를 로컬 값으로 채운다.
docker compose up -d
```

`.env`는 Docker Compose가 읽지만 Spring Boot가 직접 읽지는 않는다. `bootRun`을 실행할 때는
IDE 실행 구성 또는 셸 환경변수에 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 주입한다.

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:DB_URL = 'jdbc:postgresql://localhost:5432/runninggu'
$env:DB_USERNAME = 'runninggu'
$env:DB_PASSWORD = '<로컬 비밀번호>'
$env:JWT_SECRET = '<Base64로 인코딩한 32바이트 이상 키>'
$env:KAKAO_REST_KEY = '<서버용 REST API 키>'
$env:KTO_SERVICE_KEY = '<디코딩된 한국관광공사 서비스 키>'
.\gradlew.bat bootRun
```

`JWT_SECRET`은 HS256 서명 키이며 Base64 디코딩 결과가 최소 32바이트여야 한다. 로컬용 키는
`[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`로 생성할 수
있고 저장소에 커밋하지 않는다. 누락·Base64 형식 오류·길이 미달이면 서버는 기동하지 않는다.

`KAKAO_REST_KEY`는 지오코딩·POI 등 카카오 로컬 프록시에만 사용하며 앱이나 저장소에
포함하지 않는다. 키가 없으면 대회 공개 조회는 계속 동작하지만 카카오 로컬 프록시는
`502 EXTERNAL_API_ERROR`를 반환한다.

`KTO_SERVICE_KEY`는 홈·대회 인근 축제와 POI의 한국관광공사 클라이언트가 함께 사용하며,
HTTP 클라이언트가 쿼리를 인코딩하므로 디코딩 키를 사용한다. 키가 없으면 홈·대회 인근
축제 API는 `502 EXTERNAL_API_ERROR`를 반환한다. POI는 KTO 원천을 실패로 처리하고, 다른
원천에서 한 건 이상 표시할 수 있을 때만 부분 성공 `200`을 반환한다. 다른 공개 API는 계속
동작한다. 홈 축제는 전국 월간 결과를 5분, 대회 인근 축제는 대회별 결과를 하루 캐시한다.

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

## 검증

통합 테스트는 Testcontainers로 PostgreSQL 17을 실행하므로 Docker가 켜져 있어야 한다.

```powershell
cd backend
.\gradlew.bat clean test bootJar --console=plain
```

테스트 리포트는 `backend/build/reports/tests/test/index.html`, 실행 JAR은
`backend/build/libs/runninggu-server-0.0.1-SNAPSHOT.jar`에 생성된다.
