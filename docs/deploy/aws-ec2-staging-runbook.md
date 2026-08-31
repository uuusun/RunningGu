# AWS EC2 스테이징 배포 실행서

> 이 문서는 [`development-release-contest-guide.md` §7](../development-release-contest-guide.md#7-백엔드데이터베이스-배포-지침)의
> AWS EC2 스테이징 구현 실행서다. 정책이나 절차가 충돌하면 상위 §7이 우선한다.

이 실행서는 `staging-api.runninggu.store` 단일 EC2에 Spring Boot JAR, PostgreSQL 17,
GraphHopper 11, Nginx를 배포하는 순서다. 새 `/health` API나 Actuator를 추가하지 않으며 기존
`GET /api/contests?size=1`로 애플리케이션 준비 상태를 확인한다.

## 1. 고정 구조와 운영 결정

| 항목 | 스테이징 기준 |
|---|---|
| 호스트 | AWS EC2, Ubuntu 24.04 LTS x86_64 |
| 리전 | 서울 `ap-northeast-2` |
| 도메인 | `staging-api.runninggu.store` |
| 프로세스 | Spring Boot는 host systemd, PostgreSQL·GraphHopper는 Docker Compose |
| 인스턴스 크기 | 스테이징 정상 기동은 x86_64 8GiB를 목표로 하고, 첫 GraphHopper import 또는 메모리 부족 때 16GiB로 임시 증설 |
| 메모리 | 현재 GraphHopper `-Xmx6g` + Spring Boot `-Xmx2g`는 16GiB가 필요. 8GiB 전환 전 힙 환경변수화·실측 필수, swap 4GB는 긴급 완충용 |
| 외부 포트 | Nginx 80·443만 허용. 5432·8080·8989는 loopback |
| 접속 | SSM Session Manager. SSH 22는 열지 않음 |
| 운영책임자 | 유선경 — AWS·결제·도메인·Google Play·인프라 |
| 운영 연락처 | `runninggu.play@gmail.com` — Certbot·예산 알림 |
| 월 예산 | 총 100,000원, 실제 비용 80,000원·예상 비용 100,000원 도달 알림 |
| 백업 | pgBackRest 일일 전체 백업 + 연속 WAL, 서울 리전 암호화 S3, 7일 보존, 운영책임자만 접근 |

월 100,000원은 스테이징 전체 예산이다. 24시간 상시 기동을 전제로 승인한 값이 아니므로
검증하지 않는 시간에는 EC2를 중지하고, 계획한 월 기동 시간을 비용 계산에 넣는다. 중지 중에도
EBS·공인 IPv4·S3·KMS 등 남는 비용을 포함해 생성 직전 계산한다. 공개 앱이 사용할 프로덕션
상시 서버는 출시 전에 실측 사양과 별도 월 예상비용을 다시 승인한다. 8GiB x86_64 인스턴스는
GraphHopper·Spring Boot 힙을
환경변수화하고 첫 import·cold start·대표 API 부하에서 PostgreSQL과 OS 여유 메모리까지 실측한
뒤에만 사용한다. 현재 고정값 `-Xmx6g` + `-Xmx2g` 상태에서 8GiB를 만들지 않는다.

첫 GraphHopper import나 운영 중 메모리 부족에는 EBS와 Elastic IP를 유지한 채 EC2를 중지하고
16GiB x86_64 유형으로 바꿔 다시 시작할 수 있다. 이때 짧은 중단을 공지하고 §15 검증을 전부
다시 수행한다. 16GiB를 일시 사용해도 월 예상비용이 100,000원을 넘으면 먼저 예산을 다시
승인한다. 16GiB 상시 운영이나 프로덕션 사용이 필요하면 #230의 월 예산과 80% 알림값을 같은
결정으로 올린다.

swap은 RAM 대체재가 아니다. 지속적으로 사용되면 인스턴스를 늘린다.

## 2. 배포 artifact 원칙

EC2에서 Gradle 테스트나 빌드를 실행하지 않는다. 백엔드 CI가 다음을 모두 통과한 동일 commit의
`runninggu-backend-<Git SHA>` artifact만 배포한다.

1. Playwright Chromium 설치
2. 단위·Testcontainers 통합·브라우저 테스트
3. 서버 Boot JAR와 Importer Boot JAR 생성
4. 빈 PostgreSQL에서 Importer 첫 실행 `APPLIED`
5. 같은 snapshot 재실행 `NO_OP`
6. `SHA256SUMS`와 `release-manifest.txt` 생성

artifact 구조는 다음과 같다.

```text
runninggu-server.jar
runninggu-contest-import.jar
data/contest_snapshot.json
release-manifest.txt
SHA256SUMS
```

EC2의 저장소 checkout은 Compose와 GraphHopper Docker build context를 위한 것이다. JAR을 다시
빌드하거나 CI artifact를 다른 commit의 checkout과 섞지 않는다.

## 3. AWS 선행 조건

다음 조건을 만족하기 전에는 Certbot을 실행하지 않는다.

1. EC2에 Elastic IP를 연결한다.
2. 보안 그룹 inbound는 TCP 80·443만 인터넷에 허용한다.
3. 22·5432·8080·8989는 열지 않는다.
4. 가비아 DNS에 `staging-api` A 레코드를 만들고 Elastic IP를 지정한다.
5. 외부 DNS 조회에서 `staging-api.runninggu.store`가 해당 Elastic IP를 반환하는지 확인한다.

AWS 계정에는 월 총예산 100,000원, 실제 비용 80,000원 도달 알림, 예상 비용 100,000원 도달
알림을 `runninggu.play@gmail.com`으로 설정하고, MFA와 복구 수단을 먼저 설정한다. 종료 방지와
EBS 암호화를 켜고 인스턴스, 볼륨, Elastic IP에 환경·담당자 태그를 붙인다. AWS 콘솔의 결제
통화가 원화가 아니면 생성 시점 환산값과 환율 기준일을 운영 기록에 함께 남긴다.

## 4. 호스트 패키지와 사용자

SSM으로 접속한 Ubuntu 24.04에서 기본 패키지를 설치한다.

```bash
sudo apt-get update
sudo apt-get install -y \
  openjdk-21-jre-headless \
  nginx \
  certbot \
  git \
  curl \
  unzip \
  dnsutils
```

Docker Engine과 Compose plugin은 Docker의 Ubuntu 공식 저장소 절차로 설치한다. 편의 설치
스크립트를 운영 서버에 바로 실행하지 않는다.

설치 직후 버전을 기록한다.

```bash
java -version
sudo docker version
sudo docker compose version
nginx -v
certbot --version
```

Compose의 특정 버전 확장 문법에 기대지는 않지만 `docker compose` v2 plugin이 실제 EC2에
설치됐는지 이 단계에서 확인한다.

애플리케이션 전용 사용자를 만들고 Docker 그룹에는 추가하지 않는다. Docker 그룹은 host root와
동등한 권한을 주므로 Compose 명령은 SSM 배포 운영자가 `sudo docker compose`로 실행한다.

```bash
sudo useradd \
  --system \
  --create-home \
  --home-dir /opt/runninggu \
  --shell /usr/sbin/nologin \
  runninggu

sudo install -d -o runninggu -g runninggu -m 0755 /opt/runninggu
sudo install -d -o runninggu -g runninggu -m 0755 /opt/runninggu/repository
sudo install -d -o root -g runninggu -m 0750 /opt/runninggu/releases
sudo install -d -o root -g root -m 0755 /opt/runninggu-data/osm
sudo install -d -o root -g runninggu -m 0750 /etc/runninggu
```

이미 사용자가 있으면 `useradd`는 다시 실행하지 않고 소유권과 권한만 확인한다.

## 5. 메모리 완충과 journal 상한

먼저 현재 swap을 확인한다.

```bash
swapon --show
free -h
```

swap이 전혀 없을 때만 4GB 파일을 한 번 생성하고 `vm.swappiness=10`을 적용한다.

```bash
sudo fallocate -l 4G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-runninggu-memory.conf
sudo sysctl --system
```

저장소의 journal 제한을 설치한다. 이는 Spring Boot만이 아니라 host 전체 journal에 적용된다.

```bash
sudo install -d -m 0755 /etc/systemd/journald.conf.d
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/systemd/journald-runninggu.conf \
  /etc/systemd/journald.conf.d/runninggu.conf
sudo systemctl restart systemd-journald
```

현재 정책은 디스크 용량만 제한한다. 정확한 로그 보존 일수는 운영·개인정보 정책을 확정한 뒤
별도로 추가한다.

## 6. exact commit checkout과 환경파일

저장소를 clone한 뒤 배포 artifact의 `git_commit`과 같은 commit을 detached checkout한다.

```bash
cd /opt/runninggu/repository
sudo -u runninggu git clone https://github.com/uuusun/RunningGu.git .
sudo -u runninggu git checkout --detach <artifact의_git_commit>
sudo -u runninggu git rev-parse HEAD
```

배포 환경 예시를 `/etc`에 복사한 뒤 `sudoedit`으로 실제 값을 채운다.

```bash
sudo install -m 0640 -o root -g runninggu \
  backend/deploy/env/compose.env.example \
  /etc/runninggu/compose.env
sudo install -m 0640 -o root -g runninggu \
  backend/deploy/env/application.env.example \
  /etc/runninggu/application.env

sudoedit /etc/runninggu/compose.env
sudoedit /etc/runninggu/application.env
```

- 두 파일의 `DB_PASSWORD`에는 같은 URL-safe 값을 넣는다.
- 실제 값은 shell history, 명령 인자, Git, CI 로그에 남기지 않는다.
- `JWT_SECRET`은 Base64 디코딩 결과가 32바이트 이상이어야 한다.
- 스테이징과 운영의 DB·JWT·SMTP·외부 API 키를 공유하지 않는다.
- `SERVER_ADDRESS=127.0.0.1`과 `FORWARD_HEADERS_STRATEGY=framework`를 유지한다.
- springdoc 두 항목은 기본 `false`다.

## 7. 고정 OSM PBF 설치

`latest` URL을 배포 기록 없이 사용하지 않는다. 날짜가 고정된 대한민국 PBF URL, 다운로드 시각,
배포처, SHA-256을 릴리스 기록에 남긴 뒤 검증한 파일을 설치한다.

```bash
sha256sum <다운로드한_고정버전_PBF>
sudo install -m 0444 \
  <다운로드한_고정버전_PBF> \
  /opt/runninggu-data/osm/korea.osm.pbf
```

PBF 교체 시 기존 그래프 볼륨을 즉시 삭제하지 않는다. 새 그래프를 별도로 검증하고 교체·복구
절차를 기록한 작업에서만 처리한다.

## 8. Compose 사전 검증과 기동

모든 Compose 명령은 `/opt/runninggu/repository/backend`에서 base와 EC2 파일을 함께 사용한다.
`config` 전체 출력에는 DB 비밀번호가 포함될 수 있으므로 화면이나 로그로 출력하지 않는다.

```bash
cd /opt/runninggu/repository/backend

sudo docker compose \
  --env-file /etc/runninggu/compose.env \
  --profile routing \
  -f compose.yaml \
  -f compose.ec2.yaml \
  config --quiet

sudo docker compose \
  --env-file /etc/runninggu/compose.env \
  --profile routing \
  -f compose.yaml \
  -f compose.ec2.yaml \
  config --format json \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); p=d["services"]["postgres"]["ports"]; g=d["services"]["graphhopper"]["ports"]; assert len(p)==1 and p[0].get("host_ip")=="127.0.0.1", "PostgreSQL loopback binding 실패"; assert len(g)==1 and g[0].get("host_ip")=="127.0.0.1", "GraphHopper loopback binding 실패"'
```

PostgreSQL과 GraphHopper를 시작한다.

```bash
sudo docker compose \
  --env-file /etc/runninggu/compose.env \
  --profile routing \
  -f compose.yaml \
  -f compose.ec2.yaml \
  up -d --build postgres graphhopper
```

PostgreSQL이 준비될 때까지 확인한다.

```bash
until sudo docker compose \
  --env-file /etc/runninggu/compose.env \
  -f compose.yaml \
  -f compose.ec2.yaml \
  exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
do
  sleep 2
done
```

GraphHopper 첫 그래프와 SRTM 캐시 생성은 수분 이상 걸릴 수 있다. 로그와 메모리를 별도로 본다.

```bash
sudo docker compose \
  --env-file /etc/runninggu/compose.env \
  --profile routing \
  -f compose.yaml \
  -f compose.ec2.yaml \
  ps
sudo docker compose \
  --env-file /etc/runninggu/compose.env \
  --profile routing \
  -f compose.yaml \
  -f compose.ec2.yaml \
  logs --tail 200 graphhopper
free -h
sudo docker stats --no-stream
```

`docker compose down -v`는 실행하지 않는다.

## 9. CI artifact 설치

성공한 GitHub Actions run에서 exact commit의 artifact를 승인된 경로로 전달한다. GitHub token을
명령 인자나 shell history에 넣지 않는다. EC2에서 압축을 푼 뒤 checksum부터 검증한다.

```bash
cd <artifact를_푼_임시_디렉터리>
sha256sum -c SHA256SUMS
```

검증 성공 후 release 디렉터리에 읽기 전용으로 설치한다.

```bash
sudo install -d -o root -g runninggu -m 0750 \
  /opt/runninggu/releases/<artifact의_git_commit>
sudo install -d -o root -g runninggu -m 0750 \
  /opt/runninggu/releases/<artifact의_git_commit>/data

sudo install -m 0440 -o root -g runninggu \
  runninggu-server.jar \
  /opt/runninggu/releases/<artifact의_git_commit>/runninggu-server.jar
sudo install -m 0440 -o root -g runninggu \
  runninggu-contest-import.jar \
  /opt/runninggu/releases/<artifact의_git_commit>/runninggu-contest-import.jar
sudo install -m 0440 -o root -g runninggu \
  data/contest_snapshot.json \
  /opt/runninggu/releases/<artifact의_git_commit>/data/contest_snapshot.json
sudo install -m 0440 -o root -g runninggu \
  SHA256SUMS release-manifest.txt \
  /opt/runninggu/releases/<artifact의_git_commit>/

sudo ln -sfn \
  /opt/runninggu/releases/<artifact의_git_commit> \
  /opt/runninggu/current
```

`release-manifest.txt`, checkout HEAD, 배포 요청 commit이 모두 같은지 확인한다.

## 10. systemd 설치와 최초 Flyway·Importer

두 unit을 설치한다. Importer는 배포 때 명시 실행하는 one-shot이며 enable하지 않는다.

```bash
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/systemd/runninggu-contest-import.service \
  /etc/systemd/system/runninggu-contest-import.service
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/systemd/runninggu-backend.service \
  /etc/systemd/system/runninggu-backend.service
sudo systemctl daemon-reload
sudo systemd-analyze verify \
  /etc/systemd/system/runninggu-contest-import.service \
  /etc/systemd/system/runninggu-backend.service
```

빈 DB의 첫 배포에서는 Importer 비웹 컨텍스트가 Flyway V1부터 적용한 뒤 snapshot을 적재한다.
Importer에는 `COURSE_SYNC_ENABLED=false`가 강제되며 JWT·Kakao·SMTP 시크릿을 전달하지 않는다.

```bash
sudo systemctl start runninggu-contest-import.service
sudo systemctl status --no-pager runninggu-contest-import.service
sudo journalctl \
  -u runninggu-contest-import.service \
  --since "10 minutes ago" \
  --no-pager
```

로그에서 Flyway 성공과 `status=APPLIED` 또는 같은 snapshot 재실행의 `status=NO_OP`를 확인한다.
실패하면 새 애플리케이션을 시작하지 않는다. DB를 임의로 되돌리지 말고 실패 원인과 적용된
Flyway 버전을 먼저 기록한다.

## 11. Spring Boot 기동과 내부 준비 확인

첫 배포에서는 enable과 start를 함께 수행한다. 이후 배포는 artifact 링크를 바꾼 다음 restart한다.

```bash
sudo systemctl enable --now runninggu-backend.service
sudo systemctl status --no-pager runninggu-backend.service
```

`Type=simple`의 active 상태는 HTTP 준비 완료를 뜻하지 않는다. 기존 공개 대회 API를 최대 2분간
재시도하고 끝까지 실패하면 배포 실패로 처리한다.

```bash
ready=0
for attempt in $(seq 1 60); do
  if curl --fail --silent --show-error \
    --output /dev/null \
    'http://127.0.0.1:8080/api/contests?size=1'
  then
    ready=1
    break
  fi
  sleep 2
done
test "$ready" -eq 1
```

내부 포트가 모두 loopback인지 확인한다.

```bash
sudo ss -lntp
```

5432·8080·8989는 `127.0.0.1`이어야 한다.

## 12. Nginx bootstrap과 인증서 발급

DNS A 레코드와 보안 그룹 80이 준비됐는지 다시 확인한다.

```bash
dig +short staging-api.runninggu.store
```

ACME webroot와 HTTP bootstrap 설정을 설치한다. Ubuntu 기본 site는 symlink만 제거한다.

```bash
sudo install -d -m 0755 /var/www/certbot
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/nginx/staging-api.bootstrap.conf \
  /etc/nginx/sites-available/runninggu-staging-bootstrap
sudo ln -sfn \
  /etc/nginx/sites-available/runninggu-staging-bootstrap \
  /etc/nginx/sites-enabled/runninggu-staging-bootstrap
if test -L /etc/nginx/sites-enabled/default; then
  sudo unlink /etc/nginx/sites-enabled/default
fi
sudo nginx -t
sudo systemctl reload nginx
```

Certbot 운영 이메일 `runninggu.play@gmail.com`으로 인증서를 발급한다.

```bash
sudo certbot certonly \
  --webroot \
  --webroot-path /var/www/certbot \
  --domain staging-api.runninggu.store \
  --email runninggu.play@gmail.com \
  --agree-tos \
  --no-eff-email
```

## 13. 최종 HTTPS·기본 거부 설정

TLS parameter, named site, 알 수 없는 Host 기본 거부를 설치한다.

```bash
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/nginx/runninggu-ssl-params.conf \
  /etc/nginx/snippets/runninggu-ssl-params.conf
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/nginx/staging-api.conf \
  /etc/nginx/sites-available/runninggu-staging
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/nginx/default-reject.conf \
  /etc/nginx/sites-available/00-runninggu-default-reject

sudo ln -sfn \
  /etc/nginx/sites-available/runninggu-staging \
  /etc/nginx/sites-enabled/runninggu-staging
sudo ln -sfn \
  /etc/nginx/sites-available/00-runninggu-default-reject \
  /etc/nginx/sites-enabled/00-runninggu-default-reject
sudo unlink /etc/nginx/sites-enabled/runninggu-staging-bootstrap

sudo nginx -t
sudo systemctl reload nginx
```

Nginx는 외부 `Forwarded`와 `X-Forwarded-*`를 제거·재작성하며 `X-Forwarded-Prefix`를 빈 값으로
지운다. Spring Boot는 loopback만 listen하므로 Nginx를 우회할 수 없다.

## 14. 인증서 자동 갱신

갱신 성공 뒤 설정 검증과 Nginx reload를 수행하는 hook을 설치한다.

```bash
sudo install -d -m 0755 /etc/letsencrypt/renewal-hooks/deploy
sudo install -m 0755 \
  /opt/runninggu/repository/backend/deploy/certbot/reload-nginx.sh \
  /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
sudo systemctl enable --now certbot.timer
sudo systemctl status --no-pager certbot.timer
sudo certbot renew --dry-run --run-deploy-hooks
```

dry-run 성공과 deploy hook의 `nginx -t`, reload 성공을 모두 확인한다.

## 15. 외부 스모크·재부팅 검증

외부 HTTPS에서 기존 API를 확인한다.

```bash
curl --fail --silent --show-error \
  --output /dev/null \
  'https://staging-api.runninggu.store/api/contests?size=1'
```

다음도 확인한다.

- HTTP가 HTTPS로 redirect되는가
- Swagger와 `/v3/api-docs`가 비활성인가
- 외부에서 5432·8080·8989에 연결할 수 없는가
- Nginx access log가 쿼리스트링을 제외한 `runninggu_noqs` 포맷을 사용하는가
- GraphHopper 그래프·SRTM 볼륨이 재시작 후 재사용되는가
- `free -h`, `docker stats --no-stream`에서 swap을 지속 사용하지 않는가

마지막으로 EC2를 한 번 재부팅해 PostgreSQL·GraphHopper의 `unless-stopped`, Docker 의존 systemd,
Spring Boot 재시도, Nginx, certbot timer가 모두 복구되는지 확인한다.

## 16. 백업·복구

### 16.1 확정 구성

운영 PostgreSQL은 pgBackRest로 매일 한 번 전체 백업하고 WAL을 연속 보관한다. 저장소는
서울 `ap-northeast-2`의 전용 비공개 S3 버킷이며 고객 관리형 KMS 키로 암호화한다. EC2
instance profile은 해당 버킷 prefix와 KMS 키에 필요한 최소 권한만 받고 정적 access key를
파일에 두지 않는다. 전체 백업과 복구에 필요한 WAL은 7일 보존 후 pgBackRest `expire`로
자동 삭제한다.

pgBackRest가 Docker 안에서 instance profile 임시 자격 증명을 받도록 EC2 metadata option은
IMDSv2 token 필수·response hop limit 2로 설정한다. 이 인스턴스에는 저장소에서 관리하는
신뢰된 컨테이너만 실행하고 `pgbackrest check`로 임시 자격 증명 갱신까지 확인한다.

EBS snapshot은 추가적인 장애 대응 수단으로는 쓸 수 있지만 PostgreSQL 기준 백업과 WAL을
대체하지 않는다. `pg_dump`도 논리 점검용 보조 산출물일 뿐 이 복구 절차의 기준 백업으로
삼지 않는다.

후속 #227 구현 PR은 다음을 함께 추가해야 한다. 하나라도 없으면 TOS 1.1·PRIVACY 1.2를
활성화하지 않는다.

1. PostgreSQL 17과 버전을 고정한 pgBackRest를 포함하는 저장소 관리 이미지
2. `/var/lib/postgresql/data`와 pgBackRest 설정을 연결한 Compose 구성
3. 일일 전체 백업 systemd service·timer와 실패 알림
4. 아래 설정·초기화·백업·복원 명령의 실행 검증

S3 버킷은 public access block을 모두 켜고, TLS가 아닌 요청을 거부하며, bucket versioning을
켠다면 현재·비현재 객체가 모두 7일 정책을 지키도록 lifecycle을 함께 설정한다. 불완전한
multipart upload는 1일 뒤 중단한다. 버킷 이름·KMS key ARN·role 이름은 운영 환경에서 채우고
저장소에는 넣지 않는다.

pgBackRest 설정 기준은 다음과 같다. 실제 bucket·KMS key·role 값은 운영 설정으로 주입한다.

```ini
[runninggu]
pg1-path=/var/lib/postgresql/data

[global]
repo1-type=s3
repo1-path=/runninggu/staging
repo1-s3-bucket=<BACKUP_BUCKET>
repo1-s3-endpoint=s3.ap-northeast-2.amazonaws.com
repo1-s3-region=ap-northeast-2
repo1-s3-key-type=auto
repo1-s3-role=<INSTANCE_PROFILE_ROLE_NAME>
repo1-s3-kms-key-id=<KMS_KEY_ARN>
repo1-retention-full-type=time
repo1-retention-full=7
repo1-retention-history=0
start-fast=y
```

PostgreSQL은 다음 기준으로 WAL archive를 켠다. 낮은 트래픽에서도 최근 변경을 오래 로컬에만
남기지 않도록 최대 5분마다 segment 전환을 요청한다.

```conf
archive_mode = on
archive_command = 'pgbackrest --stanza=runninggu archive-push %p'
archive_timeout = '300s'
```

최초 한 번 stanza를 만들고 archive·repository 연결을 검사한다.

```bash
docker compose --env-file /etc/runninggu/compose.env \
  -f compose.yaml -f compose.ec2.yaml \
  exec -T postgres pgbackrest --stanza=runninggu stanza-create

docker compose --env-file /etc/runninggu/compose.env \
  -f compose.yaml -f compose.ec2.yaml \
  exec -T postgres pgbackrest --stanza=runninggu check
```

timer는 매일 03:20 KST에 다음 순서로 실행한다. 백업이나 `check`가 실패하면 성공으로 기록하지
않고 운영책임자에게 알린다.

```bash
docker compose --env-file /etc/runninggu/compose.env \
  -f compose.yaml -f compose.ec2.yaml \
  exec -T postgres pgbackrest --stanza=runninggu --type=full backup

docker compose --env-file /etc/runninggu/compose.env \
  -f compose.yaml -f compose.ec2.yaml \
  exec -T postgres pgbackrest --stanza=runninggu expire

docker compose --env-file /etc/runninggu/compose.env \
  -f compose.yaml -f compose.ec2.yaml \
  exec -T postgres pgbackrest --stanza=runninggu check
```

`pgbackrest info --output=json`의 마지막 성공 시각, backup label, WAL archive 범위만 운영 기록에
남긴다. 사용자 개인정보·S3 자격 증명은 로그에 남기지 않는다.

### 16.2 복구 리허설

실제 서비스 공개 전 한 번, 이후 분기마다 격리된 별도 Compose project·새 volume에서 복구를
리허설한다. #227은 운영 포트를 열지 않고 별도 volume만 사용하는 `compose.recovery.yaml`도
추가한다. 운영 volume에 `restore --delta`를 시험하지 않는다. 복구 명령의 project 이름과
volume을 먼저 확인한 뒤 다음처럼 최신 안전 시점을 복원한다.

```bash
docker compose --project-name runninggu-recovery \
  --env-file /etc/runninggu/recovery-compose.env \
  -f compose.yaml -f compose.recovery.yaml \
  run --rm --no-deps --entrypoint pgbackrest postgres \
  --stanza=runninggu restore
```

백업 시각·복원 지점·검증 결과만 운영 기록에 남기고 사용자 개인정보는 기록에 복사하지 않는다.

1. 외부 접근을 차단한 복구 환경에 백업을 복원한다.
2. pgBackRest가 보관한 가능한 최신 안전 시점까지 WAL을 재생한다.
3. 백업 이후 발생한 회원 탈퇴 삭제를 다시 반영한다.
4. 만료된 이메일 인증 기록과 Refresh Token 정리 작업을 실행한다.
5. 탈퇴 회원 데이터와 만료 데이터가 API·DB 조회에서 노출되지 않는지 검증한다.
6. 검증이 끝난 뒤에만 외부 접근을 연다.

WAL에 공백이 있거나 탈퇴 이후 시점까지 복구하지 못했거나 검증이 실패하면 복원본을 공개하지
않는다. 복구 환경을 폐기할 때도 운영 project·volume 이름과 다른지 먼저 확인한다.
`docker compose down -v`나 볼륨 삭제는 백업·복구 절차가 아니다.

근거 문서는 [PostgreSQL 연속 아카이빙·PITR](https://www.postgresql.org/docs/17/continuous-archiving.html)과
[pgBackRest 공식 사용자 가이드](https://pgbackrest.org/user-guide.html)다.

## 17. 이후 배포

1. exact commit CI artifact와 checksum을 검증한다.
2. 배포 직전 DB 백업과 현재 release SHA를 기록한다.
3. 새 release 디렉터리를 만들고 `current` 링크를 바꾼다.
4. `runninggu-contest-import.service`를 명시 실행한다.
5. Flyway·Importer 성공 뒤 `runninggu-backend.service`를 restart한다.
6. 내부 재시도와 외부 HTTPS 스모크를 통과시킨다.
7. 직전 artifact와 DB 백업을 정해진 보존 정책에 따라 유지한다.

DB 마이그레이션은 이전 서버와 역호환되게 설계한다. Importer 또는 앱 기동 실패 시 DB migration을
임의로 되돌리지 않는다.

## 18. 애플리케이션 artifact 롤백

호환되는 직전 release를 명시적으로 선택한다.

```bash
sudo ln -sfn \
  /opt/runninggu/releases/<직전_git_commit> \
  /opt/runninggu/current
sudo systemctl restart runninggu-backend.service
```

11장의 내부 준비 확인과 15장의 외부 스모크를 다시 실행한다. 이전 JAR가 이미 적용된 DB migration과
호환되지 않으면 JAR만 되돌리지 않고 §7에서 정한 복구 절차를 따른다. `docker compose down -v`,
PostgreSQL volume 삭제, GraphHopper cache 삭제는 롤백 명령이 아니다.

## 19. 출시 차단 항목 갱신 기준

저장소에 이 실행서와 템플릿이 생긴 것만으로 배포 BLOCKER를 해소하지 않는다. 다음 근거가 모두
있을 때 [`development-release-contest-guide.md` §8](../development-release-contest-guide.md#8-현재-저장소의-출시-차단-항목)을
갱신한다.

- 실제 EC2·Elastic IP·DNS·HTTPS 연결
- CI artifact와 EC2 release SHA 일치 기록
- Flyway·Importer·앱·GraphHopper·SMTP 스모크 결과
- certbot 자동 갱신 dry-run
- DB 백업과 실제 복원 리허설
- 재부팅 자동 복구와 메모리 측정
- Android 스테이징 `BASE_URL` E2E
