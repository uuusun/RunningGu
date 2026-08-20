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
.\gradlew.bat bootRun
```

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>

아직 구현되지 않은 API 경로는 보안 설정에서 기본 거부한다. 공개·인증 경로는 각 기능을 구현할 때
API 계약에 맞춰 명시적으로 연다.

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
