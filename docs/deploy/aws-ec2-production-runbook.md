# AWS EC2 운영 배포 실행서

> 이 문서는 [`development-release-contest-guide.md` §7](../development-release-contest-guide.md#7-백엔드데이터베이스-배포-지침)의
> 운영 구현 SSOT다. 스테이징은 2026-09-18 폐기했으며, 과거 스테이징 실행서를 현재 절차로
> 사용하지 않는다(SPEC 결정-71). 배포는 머지된 push CI artifact와 이 문서의 운영 값만 따른다.

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
| 공개 API | `https://api.runninggu.store/api/` |
| 공개 개인정보처리방침 | `https://runninggu.store/privacy/` (웹 버전 1.0, 2026-09-13 시행) |

SSH key pair와 22번 포트는 사용하지 않는다. SSM Session Manager로 접속하며 IMDSv2를 강제한다.
종료 방지는 활성화 상태를 유지한다. KTO service key와 카카오 앱
(`KAKAO_APP_ID`·`KAKAO_REST_KEY`)은 운영에서 검증한 값을 사용한다. 폐기된 스테이징과의
공유·합산 정책은 종료됐다(결정-63·66의 결정-71 개정). 운영계정 승인 뒤 실제 쿼터와 별도 키
발급 필요성만 다시 확인한다.

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

`DB_PASSWORD`, `JWT_SECRET`, Resend API key는 운영 전용으로 생성한다.
KTO service key와 카카오 앱 `KAKAO_REST_KEY`·`KAKAO_APP_ID`는 운영에서 검증한 값을 쓴다.
운영계정 승인 뒤 실제 쿼터와 별도 키 발급 필요성을 다시 확인한다. 값은 명령 인자·shell
history·Git·CI·문서에 남기지 않는다. 두 env 파일의 `DB_PASSWORD`만 같은 값을 사용한다.

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
다른 prefix의 백업을 운영 복구 리허설에 잘못 사용하는 것을 막는다.

## 5. DNS·Nginx·TLS

가비아 DNS에 `api`와 루트(`@`) A 레코드 `3.37.39.89`를 추가한다. 루트에는 IPv6 서비스가
없으므로 AAAA 레코드를 만들지 않는다. 가비아 관리 화면에서 선택 가능한 최소 TTL인 600초를
사용하며 외부 DNS 전파가 확인되기 전에는 인증서를 요청하지 않는다.

```bash
dig +short api.runninggu.store
dig +short runninggu.store
```

먼저 HTTP challenge 전용 설정을 설치한다.

```bash
sudo install -d -m 0755 /var/www/certbot
sudo install -m 0644 backend/deploy/nginx/runninggu-log-privacy.conf \
  /etc/nginx/conf.d/runninggu-log-privacy.conf
sudo install -m 0644 \
  backend/deploy/nginx/production-api.bootstrap.conf \
  /etc/nginx/sites-available/runninggu-production
sudo ln -sfn /etc/nginx/sites-available/runninggu-production \
  /etc/nginx/sites-enabled/runninggu-production
sudo nginx -t
sudo systemctl reload nginx
```

DNS 전파 뒤 기존 `api.runninggu.store` 인증서 lineage를 루트 도메인까지 확장한다. 새 인증서가
성공적으로 발급되기 전에는 기존 인증서와 최종 nginx 설정을 바꾸지 않는다. 개인정보처리방침은
저장소의 정적 HTML 한 파일만 `/var/www/runninggu-web/privacy/index.html`에 설치한다.

이미 API TLS가 운영 중이면 DNS 변경 전에 루트 도메인의 HTTP challenge server만 별도
설치한다. 이 파일에는 443 server가 없으므로 기존 API TLS를 건드리지 않는다.

```bash
sudo install -m 0644 backend/deploy/nginx/production-root.bootstrap.conf \
  /etc/nginx/sites-available/runninggu-production-root-bootstrap
sudo ln -sfn /etc/nginx/sites-available/runninggu-production-root-bootstrap \
  /etc/nginx/sites-enabled/runninggu-production-root-bootstrap
sudo nginx -t
sudo systemctl reload nginx
```

```bash
sudo certbot certonly --webroot --cert-name api.runninggu.store --expand \
  --webroot-path /var/www/certbot \
  --domain api.runninggu.store \
  --domain runninggu.store \
  --email runninggu.play@gmail.com \
  --agree-tos --no-eff-email

sudo install -d -m 0755 -o root -g root /var/www/runninggu-web/privacy
sudo install -m 0644 web/privacy/index.html \
  /var/www/runninggu-web/privacy/index.html
sudo install -m 0644 backend/deploy/nginx/runninggu-ssl-params.conf \
  /etc/nginx/snippets/runninggu-ssl-params.conf
sudo install -m 0644 backend/deploy/nginx/production-api.conf \
  /etc/nginx/sites-available/runninggu-production
sudo install -m 0644 backend/deploy/nginx/default-reject.production.conf \
  /etc/nginx/sites-available/default-reject
sudo ln -sfn /etc/nginx/sites-available/default-reject \
  /etc/nginx/sites-enabled/default-reject
sudo unlink /etc/nginx/sites-enabled/runninggu-production-root-bootstrap
sudo nginx -t
sudo systemctl reload nginx
```

Certbot 갱신 hook과 timer를 설치한 뒤 `certbot renew --dry-run --run-deploy-hooks`까지 성공해야
HTTPS 완료로 기록한다.

루트 `/`는 향후 홈페이지가 생길 수 있으므로 브라우저에 영구 저장되지 않는 `302`로
`/privacy/`에 이동시킨다. `/privacy`만 canonical trailing slash 주소로 `301` 이동하고,
`/privacy/`는 인증 없이 정적 HTML을 `200`으로 반환한다. 그 밖의 루트 도메인 경로는 `404`다.
API host의 프록시 설정과 `/api` 인증 계약은 바꾸지 않는다.

```bash
curl --fail --silent --show-error --output /dev/null \
  https://api.runninggu.store/api/contests?size=1
curl --silent --show-error --output /dev/null \
  --write-out '%{http_code} %{redirect_url}\n' \
  https://runninggu.store/
curl --fail --silent --show-error --output /dev/null \
  --write-out '%{http_code}\n' \
  https://runninggu.store/privacy/
```

정적 문서 또는 nginx 적용이 실패하면 먼저 직전 site 설정과 정적 파일 백업을 복원하고
`nginx -t` 통과 뒤 reload한다. 인증서 확장만 실패했다면 기존 lineage와 API 설정을 그대로
유지하고 루트 도메인 HTTPS server를 설치하지 않는다.

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
volume을 사용하는 복구 리허설을 출시 전 수행한다. 리허설에서는 합성 사용자와 종속 데이터를
만들어 백업한 뒤 사용자를 탈퇴시키고 WAL을 보관한다. 백업 시점만 복원하면 사용자가 존재하고,
최신 WAL까지 적용하면 사용자와 종속 데이터가 모두 사라지는지 확인한다. 리허설 컨테이너는
네트워크를 차단하고 운영 데이터 volume을 마운트하지 않는다.

## 7. 완료 확인

- `https://api.runninggu.store/api/contests?size=1`이 HTTP 200인가
- 외부 두 네트워크에서 준비 엔드포인트를 10초 간격으로 30회 호출했을 때 전부 HTTP 200인가
- nginx 또는 backend 재시작 뒤 같은 준비 엔드포인트를 10초 간격으로 10회 호출했을 때 전부 HTTP 200인가
- HTTP가 HTTPS로 이동하고 TLS hostname·chain 검증이 성공하는가
- `https://runninggu.store/`가 `/privacy/`로 302 이동하고 `/privacy/`가 인증 없이 HTTPS 200인가
- 공개 처리방침에 초안·`noindex`·`[확인 필요]`가 없고 웹 버전 1.0과 2026-09-13 시행일이 표시되는가
- 외부에서 5432·8080·8989에 연결할 수 없는가
- Swagger와 `/v3/api-docs`가 비활성인가
- access log에 URI·query·Host·User-Agent가 남지 않고 method가 허용 목록 값으로 축약되는가
- 운영 DB·JWT·SMTP·KTO·Kakao 값이 Parameter Store의 운영 경로에서만 주입되는가
- KTO·Kakao 운영 호출량과 쿼터를 모니터링하는가
- 운영계정 승인 뒤 실제 쿼터·별도 키 발급 필요성을 다시 판단했는가
- GraphHopper가 승인된 기존 graph를 불러오며 import·SRTM download를 시작하지 않는가
- full backup·WAL archive·실패 알림·예산 알림 구독이 확인됐는가
- 합성 사용자 탈퇴 전 백업과 탈퇴 후 최신 WAL 복원에서 삭제 상태가 유지되는가
- 재부팅 뒤 PostgreSQL, GraphHopper, Spring Boot, Nginx, certbot timer가 자동 복구되는가

각 항목의 명령·시각·결과를 배포 증거 문서에 남긴다. 로그와 증거에는 토큰·비밀번호·인증 코드·
이메일 주소·사용자 좌표를 기록하지 않는다.

### 7.1 운영 단일 QA 안전선

- 머지 전 테스트와 CI가 성공한 커밋만 배포한다.
- 공개 HTTPS 스모크는 변경 범위의 최소 요청만 실행하고 요청 수·시각·commit을 기록한다.
- 쓰기 E2E는 전용 QA 계정과 식별 가능한 가역 데이터만 사용한다.
- 실제 사용자 계정·데이터를 fixture로 사용하지 않는다.
- 혼합 부하, 장애 주입, 의도적 OOM, 대량 메일·가입, 파괴적 DB·복구 시험은 운영에서 금지한다.
- 데이터 정리가 필요하면 제품의 정상 삭제 흐름과 보존 정책을 사용한다. 운영 DB 직접 수정은
  별도 장애 대응 승인 없이는 하지 않는다.

## 8. API 연결 거부 진단과 복구

TCP 연결 거부는 HTTP 401·502와 구분한다. 외부 `443` 연결이 거부되면 다음 순서로 범위를
좁힌다.

1. EC2가 `running`, 시스템·인스턴스 상태 검사가 모두 통과하며 Elastic IP가 이 인스턴스에
   연결됐는지 확인한다.
2. `ss -ltn`으로 80·443·8080·8989·5432 리스너를 확인한다. 443이 없으면 nginx,
   8080이 없으면 backend를 우선 조사한다.
3. nginx·backend·GraphHopper·Docker의 `ActiveState`, `Result`, `ExecMainStatus`,
   `NRestarts`, `StartLimit*`, `Restart`를 확인한다.
4. `nginx -t`, loopback nginx 요청, loopback backend 준비 요청, 외부 HTTPS 준비 요청을
   차례로 실행해 네트워크·프록시·애플리케이션 경계를 분리한다.
5. 커널 OOM·프로세스 강제 종료·systemd start-limit·PostgreSQL/GraphHopper 준비 실패 건수를
   확인한다. 개인정보가 포함될 수 있는 요청·예외 원문은 출력하지 않는다.

nginx 설정이 유효하지만 inactive이면 nginx만 시작하고, backend 준비 요청만 실패하면 DB와
GraphHopper 상태를 먼저 복구한 뒤 backend를 시작한다. 원인 확인 없이 EC2 전체 재부팅부터 하지
않는다. 자동 재시작 설정을 바꿀 때는 현재 unit과 drop-in을 백업하고 한 서비스씩 적용한다.
