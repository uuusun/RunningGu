# 런닝구 백엔드 배포·복원 요약

> 2026-11-23 AWS 운영 자원을 정리하면서 다시 배포할 때 필요한 내용을 한 곳에 모았다.
> 세부 절차의 원본은 [`deploy/aws-ec2-production-runbook.md`](deploy/aws-ec2-production-runbook.md)다.
> 이 문서와 런북이 다르면 런북이 맞다. 이 문서는 요약과 진입점 역할만 한다.

## 1. 현재 상태 (2026-11-23)

- AWS 서울 리전의 운영 EC2(`i-0c9f040b65d41d6c1`)·EBS·스냅샷·AMI는 삭제됐다. 서버를 다시
  띄우려면 아래 4장 순서로 새로 만들어야 한다.
- 운영 DB는 2026-11-02 EC2에서 받은 **논리 백업(custom dump) 한 벌**만 남아 있다. 백엔드 담당자 PC의
  저장소 밖에 보관하며(묶음 `runninggu-backup-20261102.tar`, DB 객체 목록 읽기만 확인, 실제 복원 미실행),
  자세한 내용은 그 PC의 Git 제외 기록 `docs/local/aws-recovery-20261102.md`에 있다.
- pgBackRest 전체 백업·연속 WAL(S3 bucket `runninggu-production-backup-987622176638-seoul`),
  graph bucket `runninggu-staging-artifacts-987622176638`, 고객 관리형 KMS key `b18b8374-…`는
  2026-11-04 정리에서 삭제됐다. 2026-11-02 이후 시점이나 과거 시점으로는 복원할 수 없다.
- 승인된 GraphHopper graph artifact(`gh11-korea-20260901-2ff6731b181a-2b8515dd29fc`)는 같은 PC의
  저장소 밖에 SHA256SUMS와 함께 보관한다.
- 로컬 개발 DB(`backend-postgres-1` 컨테이너)의 2026-11-23 덤프도 저장소 밖에 보관한다. 이 DB에는
  대회 snapshot 데이터(contest 153건 등)만 있고 사용자 데이터는 0건이다.
- 같은 점검에서 과금 리소스는 0개였다(활성 리전 17개 전체 조회). 남겨 둔 무료 항목은 서울 리전의
  SSM `SecureString` 6개(`/runninggu/production/*`, KMS key가 없어 복호화 불가), SNS topic 3개,
  SES 인증 ID(`runninggu.store` 등), 월 예산 3개다. 다시 배포할 때 SSM 비밀값은 새로 만들어야 한다.
- 스키마의 원본은 Flyway 마이그레이션 `backend/src/main/resources/db/migration/V1~V8`이다.
  [`db/schema.sql`](../db/schema.sql)은 2026-11-23 로컬 DB에서 뽑은 참고용 스냅샷이며 실행 기준이 아니다.

## 2. 기술 스택

| 항목 | 버전 | 근거 |
|---|---|---|
| Java | 21 | `backend/build.gradle.kts` toolchain |
| Spring Boot | 3.5.16 | `backend/build.gradle.kts` |
| 빌드 도구 | Gradle 8.14.5 (wrapper) | `backend/gradle/wrapper/gradle-wrapper.properties` |
| PostgreSQL | 17.10 + pgBackRest 2.55.1 | `backend/postgres/Dockerfile` |
| DB 마이그레이션 | Flyway | `build.gradle.kts` |
| 경로 엔진 | GraphHopper 11.0 (Temurin 21 JRE) | `backend/graphhopper/Dockerfile` |
| 운영 OS | Ubuntu 24.04 LTS x86_64, `c7i-flex.large` 4GiB | 운영 런북 §1 |
| 프록시·TLS | Nginx + Certbot | 운영 런북 §5 |

## 3. 로컬 실행

자세한 내용은 [`backend/README.md`](../backend/README.md)를 본다.

```powershell
cd backend
Copy-Item .env.example .env      # 값 채우기. .env는 커밋하지 않는다
docker compose up -d postgres    # GraphHopper가 필요하면 서비스 이름 없이 up
.\gradlew.bat bootRun            # DB_URL 등은 셸 환경변수나 IDE 실행 구성으로 주입
```

`.env`는 Docker Compose만 읽고 Spring Boot는 직접 읽지 않는다.

### 3.1 환경변수가 쓰이는 곳

| 파일 | 읽는 변수 |
|---|---|
| `backend/src/main/resources/application*.yml` | `SERVER_PORT` `RUNNINGGU_DEPLOYMENT_ENVIRONMENT` `FORWARD_HEADERS_STRATEGY` `DB_URL` `DB_USERNAME` `DB_PASSWORD` `JWT_SECRET` `SMTP_HOST` `SMTP_PORT` `SMTP_USERNAME` `SMTP_PASSWORD` `SMTP_FROM_ADDRESS` `SMTP_FROM_NAME` `MAIL_ENABLED` `PASSWORD_RESET_URL` `KTO_SERVICE_KEY` `KAKAO_REST_KEY` `KAKAO_APP_ID` `COURSE_SYNC_ENABLED` `GRAPHHOPPER_ENABLED` `GRAPHHOPPER_BASE_URL` `UPSTREAM_LOAD_GUARD_*` |
| `backend/compose.yaml` | `DB_NAME` `DB_PORT` `DB_USERNAME` `DB_PASSWORD` `GRAPHHOPPER_PBF_PATH` `GRAPHHOPPER_PORT` `GRAPHHOPPER_SERVER_IMAGE` `GRAPHHOPPER_XMS` `GRAPHHOPPER_XMX` |
| `backend/compose.ec2.yaml` | `GRAPHHOPPER_MEMORY_LIMIT` `GRAPHHOPPER_MEMORY_RESERVATION` `GRAPHHOPPER_XMS` `GRAPHHOPPER_XMX` `PGBACKREST_REPO1_PATH` `PGBACKREST_S3_BUCKET` `PGBACKREST_S3_KMS_KEY_ID` `PGBACKREST_S3_ROLE` |
| `backend/compose.recovery.yaml` | `DB_*` `PGBACKREST_*` (복구 리허설 전용) |
| `backend/postgres/Dockerfile` · `backend/graphhopper/Dockerfile` | 빌드 인자 `PGBACKREST_VERSION` `GRAPHHOPPER_VERSION` `GRAPHHOPPER_JAR_SHA256`만 사용 |
| `.github/workflows/backend.yml` | 테스트용 `DB_URL` `DB_USERNAME` `DB_PASSWORD` `COURSE_SYNC_ENABLED` (GitHub secret 없음) |
| `backend/deploy/env/*.env.example` | EC2 전용. 위 변수에 `SERVER_ADDRESS` `BACKEND_XMS` `BACKEND_XMX` `SPRINGDOC_*` `GRAPHHOPPER_S3_*` `AWS_REGION` `*_ALERT_SNS_TOPIC_ARN` 추가 |

Java 코드에는 `@Value`나 `System.getenv` 호출이 없다. 모든 값은 `application*.yml`을 거친다.
예시 파일은 로컬용 [`backend/.env.example`](../backend/.env.example)과 EC2용
`backend/deploy/env/*.env.example`이다.

## 4. DB 백업과 복원

### 4.1 로컬 Docker DB

PowerShell에서 실행한다. Git Bash에서는 `MSYS_NO_PATHCONV=1`을 먼저 설정해야 컨테이너 안 `/tmp`
경로가 Windows 경로로 바뀌지 않는다. `docker exec -t ... > 파일` 형태는 TTY가 줄바꿈을 바꿔
바이너리 덤프를 망가뜨릴 수 있어서 쓰지 않는다.

```powershell
# 백업: 컨테이너 안에 만든 뒤 꺼낸다
docker exec backend-postgres-1 sh -c 'pg_dump -U "$POSTGRES_USER" -d runninggu -F c -f /tmp/runninggu.dump'
docker cp backend-postgres-1:/tmp/runninggu.dump "$HOME\backups\runninggu\<날짜>\runninggu.dump"

# 검증
docker cp "$HOME\backups\runninggu\<날짜>\runninggu.dump" backend-postgres-1:/tmp/verify.dump
docker exec backend-postgres-1 pg_restore --list /tmp/verify.dump

# 복원: 빈 DB에 복원한다. --clean은 기존 객체를 지우므로 대상 DB를 먼저 확인한다
docker exec backend-postgres-1 sh -c 'pg_restore -U "$POSTGRES_USER" -d runninggu --no-owner --clean --if-exists /tmp/verify.dump'
```

2026-11-23 백업 파일은 `%USERPROFILE%\backups\runninggu\2026-11-23\runninggu.dump`
(100,728 bytes, SHA-256 `374caeb9…e362c`, TOC 119개)다. 저장소에는 넣지 않는다.

### 4.2 운영 DB (pgBackRest)

운영은 `pg_dump`가 아니라 pgBackRest 전체 백업(매일 03:20 KST)과 연속 WAL을 S3에 보관했다.
복원은 `backend/compose.recovery.yaml`과 `/etc/runninggu/recovery-compose.env`
(`PGBACKREST_REPO1_PATH=/runninggu/production`)로 별도 volume에서 진행한다.
절차는 운영 런북 §4·§6을 따른다. S3 bucket과 KMS key가 둘 다 남아 있어야 복원할 수 있는데,
2026-11-04에 둘 다 삭제됐다. 지금 운영 데이터를 되살리는 방법은 2026-11-02 논리 백업을 4.1의
`pg_restore`로 빈 DB에 복원하는 것뿐이다. 이때 Flyway·Importer를 신규 설치처럼 바로 돌리지 말고
복원한 스키마 버전(`flyway_schema_history`)과 현재 마이그레이션이 맞는지 먼저 확인한다.

## 5. EC2에 다시 배포하는 순서

배포 방식은 **소스를 EC2에서 빌드하지 않고**, `develop`·`main` push CI가 올린 artifact
`runninggu-backend-<sha>`(bootJar + checksum + release manifest)를 가져와 실행하는 방식이다.
PostgreSQL과 GraphHopper는 Docker Compose로, Spring Boot는 호스트 Java 21에서 systemd로 띄운다.

1. **인스턴스 생성** — 서울 리전, Ubuntu 24.04 x86_64, `c7i-flex.large`(4GiB), 암호화 gp3 30GiB,
   IMDSv2 강제, 종료 방지 켬. IAM role에 SSM 관리 권한과
   `backend/deploy/aws/runninggu-production-runtime-access.json` inline policy를 붙인다.
2. **보안 그룹** — 인바운드 TCP 80·443만 연다. 22번은 열지 않고 SSM Session Manager로 접속한다.
   5432·8080·8989는 loopback에만 바인딩한다.
3. **고정 주소** — Elastic IP를 연결하고 가비아 DNS의 `api`·`@` A 레코드를 새 주소로 바꾼다.
   이전 주소 `3.37.39.89`는 반납됐으면 다시 받을 수 없다(추정).
4. **호스트 설치** — Git, Java 21, Docker Engine·Compose 2.24.4 이상, Nginx, Certbot, AWS CLI.
   `runninggu` system user, `/opt/runninggu`, `/opt/runninggu-data`, `/etc/runninggu`, 스왑 4GiB.
5. **비밀값과 `.env`** — `backend/deploy/env/compose.production.env.example` →
   `/etc/runninggu/compose.env`, `application.production.env.example` →
   `/etc/runninggu/application.env` (모드 0640). 비밀값은 Parameter Store
   `/runninggu/production/*` `SecureString`(고객 관리형 KMS)에서 주입했다. Parameter Store·KMS를
   지웠다면 새로 만들거나, 비용을 피하려면 env 파일에 직접 넣는 방식으로 바꿔야 한다(추정).
6. **DB와 백업** — `docker compose -f compose.yaml -f compose.ec2.yaml`로 PostgreSQL을 띄우고
   pgBackRest stanza·WAL archive를 검증한다. 백업 bucket을 지웠다면 bucket을 새로 만들거나
   `compose.ec2.yaml`을 빼고 WAL 아카이빙 없이 운영한다(추정).
7. **빌드 산출물 실행** — CI artifact checksum을 확인하고 `/opt/runninggu/current/runninggu-server.jar`에
   둔다. 빈 DB면 Flyway가 V1~V8을 적용한다. 대회 데이터는 Importer(`runninggu-contest-import.service`)로 적재한다.
8. **GraphHopper** — 원래는 `runninggu-staging-artifacts-987622176638/graphhopper/production/`에서
   설치했지만 이 bucket은 삭제됐다. 로컬에 보관한 승인 artifact를 새 S3 경로나 EC2로 옮기고
   SHA256SUMS와 `graph-release.production.json`의 manifest 해시를 대조한 뒤 설치한다(추정).
   설치 스크립트가 S3 경로를 전제하므로 경로 조정이 필요할 수 있다.
9. **프로세스 유지** — `backend/deploy/systemd/*.service`·`*.timer`를 설치하고
   `runninggu-graphhopper` → `runninggu-backend` 순서로 시작한다. systemd가 재시작과 재부팅 복구를 맡는다.
10. **Nginx·TLS** — 운영 런북 §5 순서로 HTTP challenge → Certbot → 최종 설정. 완료 기준은 런북 §7.

## 6. 비용 주의사항

서버(EC2)를 중지하거나 종료해도 아래 항목은 계속 과금된다. 2026-11 청구서에 나온 항목 기준이다.

| 항목 | 과금 방식 (대략, 서울 리전) |
|---|---|
| 공인 IPv4 | 연결 여부와 관계없이 IP당 시간당 $0.005 (약 $3.6/월). 쓰지 않는 Elastic IP는 반납한다 |
| EBS 볼륨·스냅샷 | 볼륨 GB당 약 $0.0912/월(gp3), 스냅샷 GB당 약 $0.05/월. 인스턴스를 종료해도 "종료 시 삭제"가 꺼진 볼륨과 스냅샷은 남는다 |
| KMS 고객 관리형 키 | 키당 $1/월. 삭제 예약 후 대기 기간(7~30일)에는 과금이 멈춘다 |
| S3 | 저장 GB당 약 $0.025/월 + 요청 비용. 버전 관리를 켜면 이전 버전도 과금된다 |
| Secrets Manager | 시크릿당 $0.40/월 |
| Parameter Store | Standard 파라미터는 무료. Advanced는 파라미터당 $0.05/월 |
| CloudWatch Logs | 저장 GB당 약 $0.03/월 |
| AWS Budgets | 예산과 알림은 개수와 관계없이 무료. 액션을 건 예산은 2개까지 무료이고 그 뒤로 예산당 하루 $0.10, 정기 보고서는 1건에 $0.01 |

다시 배포할 때는 처음부터 월 예산 알림을 만들고, 내릴 때는 위 항목이 남지 않았는지 모든 리전에서 확인한다.
