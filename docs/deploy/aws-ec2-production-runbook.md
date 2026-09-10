# AWS EC2 운영 배포 실행서

> 이 문서는 [`development-release-contest-guide.md` §7](../development-release-contest-guide.md#7-백엔드데이터베이스-배포-지침)과
> [`aws-ec2-staging-runbook.md`](aws-ec2-staging-runbook.md)의 운영 환경 차이만 고정한다.
> 공통 설치·검증 명령과 합격 기준은 스테이징 실행서를 따르며, 아래 값이 충돌하면 이 문서의
> 운영 값이 우선한다.

## 1. 운영 자원과 공개 주소

2026-09-10 서울 리전에 다음 운영 전용 자원을 만들었다.

| 항목 | 운영 값 |
|---|---|
| EC2 | `i-0c9f040b65d41d6c1` (`runninggu-production-4g-2b`) |
| 사양 | `c7i-flex.large`, 2 vCPU, 4GiB, Ubuntu 24.04 LTS x86_64 |
| 가용 영역·VPC | `ap-northeast-2b`, `vpc-068918dc475f40175` |
| Elastic IP | `3.37.39.89` |
| 보안 그룹 | `sg-08477f351ef86f7fe`, 인바운드 TCP 80·443만 허용 |
| IAM role | `runninggu-production-ec2`, SSM 관리 권한 적용 |
| root EBS | `vol-04d095c35ef87133b`, 암호화 gp3 30GiB, 종료 시 삭제 |
| 공개 주소 | `https://api.runninggu.store/api/` |

SSH key pair와 22번 포트는 사용하지 않는다. SSM Session Manager로 접속하며 IMDSv2를 강제한다.
종료 방지는 활성화 상태를 유지한다. 스테이징 EC2·DB·시크릿과 운영 값을 공유하지 않는다.

## 2. 운영 환경 파일

최초 설치에서는 다음 운영 예시를 사용한다.

```bash
sudo install -m 0640 -o root -g runninggu \
  backend/deploy/env/compose.production.env.example \
  /etc/runninggu/compose.env
sudo install -m 0640 -o root -g runninggu \
  backend/deploy/env/application.production.env.example \
  /etc/runninggu/application.env
```

운영에서 고정할 차이는 다음과 같다.

| 설정 | 운영 값 |
|---|---|
| `RUNNINGGU_DEPLOYMENT_ENVIRONMENT` | `production` |
| `GRAPHHOPPER_ENVIRONMENT` | `production` |
| `PGBACKREST_REPO1_PATH` | `/runninggu/production` |
| GraphHopper descriptor | `backend/graphhopper/graph-release.production.json` |
| 비밀번호 재설정 URL | `https://api.runninggu.store/reset-password` |
| SMTP | Resend `smtp.resend.com:587`, username `resend`, 발신 `no-reply@runninggu.store` |
| 문서 API | springdoc·Swagger 모두 비활성 |
| 부하 시험 가드 | 항상 비활성 |

`DB_PASSWORD`, `JWT_SECRET`, Resend API key, KTO service key, Kakao REST key는 운영 전용으로
생성한다. 값은 명령 인자·shell history·Git·CI·문서에 남기지 않는다. 두 env 파일의
`DB_PASSWORD`만 같은 값을 사용한다.

운영 비밀값은 고객 관리형 KMS key로 암호화한 Parameter Store `SecureString`으로 보관한다.
경로는 `/runninggu/production/<name>`으로 제한하며, EC2 role이 복호화해 접근 제한된 env 파일을
만든 뒤 애플리케이션을 시작한다. 현재 이름은 `db-password`, `jwt-secret`, `smtp-password`,
`kto-service-key`, `kakao-rest-key`, `kakao-app-id`다. 조회 명령이나 배포 증거에는 복호화한 값을
출력하지 않는다.

## 3. S3·KMS·IAM

백업 bucket은 `runninggu-production-backup-987622176638-seoul`로 분리한다. AWS가
`--<region>-an` 형태의 접미사를 계정·리전 네임스페이스용으로 예약하므로 사용하지 않는다.
버전 관리는 끄고 public access는 모두 차단한다. 기본 암호화는 기존 고객 관리형 KMS key
`b18b8374-922a-46bb-bda9-78c98a22d679`을 사용하고 Bucket Key를 켠다. 별도 KMS key의 고정
월 비용을 추가하지 않되 key user에 운영 role을 추가한다.

운영 role의 inline policy는
`backend/deploy/aws/runninggu-production-runtime-access.json`을 사용하며 다음 최소 범위만 허용한다.

- graph 원본: `runninggu-staging-artifacts-987622176638/graphhopper/production/*` 읽기
- 운영 백업: 운영 bucket의 `runninggu/production/*` 목록·읽기·쓰기·삭제
- KMS: 위 key의 Encrypt·Decrypt·ReEncrypt·GenerateDataKey·DescribeKey
- Parameter Store: `parameter/runninggu/production/*`의 GetParameter·GetParameters
- 알림: 운영 SNS topic에 Publish

정적 AWS access key는 서버에 두지 않는다. 현재 승인된 graph artifact 세 파일은 payload를
바꾸지 않고 `graphhopper/production/<artifact-id>/` 경로로 복사한 뒤 운영 descriptor와 함께
검증한다.

## 4. 호스트 설치와 배포 순서

공통 실행서 §4~§16의 순서를 유지한다.

1. Ubuntu 보안 업데이트 후 Git, Java 21, Docker Engine·Compose 2.24.4 이상, Nginx, Certbot,
   AWS CLI를 설치한다.
2. `runninggu` system user와 `/opt/runninggu`, `/opt/runninggu-data`, `/etc/runninggu`를 만들고
   스왑 4GiB·`vm.swappiness=10`·journal 상한을 적용한다.
3. 성공한 `develop` 또는 `main` push CI artifact의 exact commit을 detached checkout한다.
4. 운영 env를 접근 제한된 파일로 설치하고 비밀값을 주입한다.
5. Compose config가 loopback 포트, 운영 백업 path, 4GiB 메모리 계약을 만족하는지 확인한다.
6. PostgreSQL을 만들고 pgBackRest stanza·WAL archive를 검증한다.
7. CI artifact checksum과 release manifest를 확인하고 빈 DB에 Flyway·Importer를 실행한다.
8. 승인된 GraphHopper artifact를 운영 S3 prefix에서 설치·검증하고 `current`를 활성화한다.
9. systemd unit을 설치한 뒤 GraphHopper, Spring Boot, WAL 감시를 시작한다.
10. 내부 readiness 성공 뒤 Nginx·DNS·TLS를 순서대로 연다.
11. 전체 백업과 복구 목록, 실패 알림, 재부팅 복구를 검증한 뒤 백업 timer를 활성화한다.

EC2에서 소스를 빌드하거나, 검증 전 graph symlink를 수동으로 우회하거나, PostgreSQL volume을
지우는 `docker compose down -v`를 실행하지 않는다.

pgBackRest의 `pg1-user`는 Compose의 고정 DB 역할 `runninggu`와 같아야 한다. 공식 PostgreSQL
이미지는 `POSTGRES_USER=runninggu`일 때 `postgres` 역할을 별도로 만들지 않으므로, 이 값을
생략하면 첫 `stanza-create`가 `role "postgres" does not exist`로 실패한다.
PostgreSQL 이미지에는 S3 TLS 인증서를 검증할 `ca-certificates`도 포함한다. 인증서 검증을 끄는
설정으로 우회하지 않는다.

복구 전용 `/etc/runninggu/recovery-compose.env`에도
`PGBACKREST_REPO1_PATH=/runninggu/production`을 명시한다. 복구 Compose는 이 값을 필수로 받아
staging 백업을 운영 복구 리허설에 잘못 사용하는 것을 막는다.

## 5. DNS·Nginx·TLS

가비아 DNS에 `api` A 레코드 `3.37.39.89`를 추가한다. 외부 DNS 전파가 확인되기 전에는
인증서를 요청하지 않는다.

```bash
dig +short api.runninggu.store
```

먼저 HTTP challenge 전용 설정을 설치한다.

```bash
sudo install -d -m 0755 /var/www/certbot
sudo install -m 0644 \
  backend/deploy/nginx/production-api.bootstrap.conf \
  /etc/nginx/sites-available/runninggu-production
sudo ln -sfn /etc/nginx/sites-available/runninggu-production \
  /etc/nginx/sites-enabled/runninggu-production
sudo nginx -t
sudo systemctl reload nginx
```

DNS 전파 뒤 인증서를 발급하고 최종 설정을 설치한다.

```bash
sudo certbot certonly --webroot \
  --webroot-path /var/www/certbot \
  --domain api.runninggu.store \
  --email runninggu.play@gmail.com \
  --agree-tos --no-eff-email

sudo install -m 0644 backend/deploy/nginx/runninggu-ssl-params.conf \
  /etc/nginx/snippets/runninggu-ssl-params.conf
sudo install -m 0644 backend/deploy/nginx/production-api.conf \
  /etc/nginx/sites-available/runninggu-production
sudo install -m 0644 backend/deploy/nginx/default-reject.production.conf \
  /etc/nginx/sites-available/default-reject
sudo ln -sfn /etc/nginx/sites-available/default-reject \
  /etc/nginx/sites-enabled/default-reject
sudo nginx -t
sudo systemctl reload nginx
```

Certbot 갱신 hook과 timer를 설치한 뒤 `certbot renew --dry-run --run-deploy-hooks`까지 성공해야
HTTPS 완료로 기록한다.

## 6. 백업·장애·비용 알림

운영 SNS topic은 `runninggu-production-alerts`로 분리하고 `runninggu.play@gmail.com`을 구독시킨다.
이메일의 Confirm subscription을 완료한 뒤 백업·WAL·GraphHopper·Spring Boot 실패 알림 unit에서
같은 topic을 사용한다. 테스트 알림 1건의 실제 수신을 확인한다.

AWS Budget은 운영 자원용 월 USD 70 기준으로 만든다. 실제 비용 80%와 예상 비용 100%에서
같은 운영 메일로 알린다. 환율과 상시 기동 비용을 매달 재확인하며 전체 월 100,000원 상한을
넘길 가능성이 있으면 사양·기동 정책을 다시 승인한다.

PostgreSQL은 매일 03:20 KST 전체 백업, 연속 WAL, 7일 보존을 유지한다. 최초 수동 full backup,
`pgbackrest info --output=json`, WAL check, 운영 bucket 객체·SSE-KMS 상태를 확인한 뒤에만
`runninggu-postgres-backup.timer`를 활성화한다. 백업 존재만으로 복구 완료로 보지 않으며 별도
volume을 사용하는 복구 리허설을 출시 전 수행한다.

## 7. 완료 확인

- `https://api.runninggu.store/api/contests?size=1`이 HTTP 200인가
- HTTP가 HTTPS로 이동하고 TLS hostname·chain 검증이 성공하는가
- 외부에서 5432·8080·8989에 연결할 수 없는가
- Swagger와 `/v3/api-docs`가 비활성인가
- access log에 query string이 남지 않는가
- 운영 DB·JWT·SMTP·KTO·Kakao 값이 스테이징과 다른가
- GraphHopper가 승인된 기존 graph를 불러오며 import·SRTM download를 시작하지 않는가
- full backup·WAL archive·실패 알림·예산 알림 구독이 확인됐는가
- 재부팅 뒤 PostgreSQL, GraphHopper, Spring Boot, Nginx, certbot timer가 자동 복구되는가

각 항목의 명령·시각·결과를 배포 증거 문서에 남긴다. 로그와 증거에는 토큰·비밀번호·인증 코드·
이메일 주소·사용자 좌표를 기록하지 않는다.
