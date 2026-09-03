# AWS EC2 스테이징 배포 실행서

> 이 문서는 [`development-release-contest-guide.md` §7](../development-release-contest-guide.md#7-백엔드데이터베이스-배포-지침)의
> AWS EC2 스테이징 구현 실행서다. 정책이나 절차가 충돌하면 상위 §7이 우선한다.
>
> **PR 2 구현 주의:** GraphHopper 관련 목표 구조는
> [`graphhopper-artifact-contract.md`](graphhopper-artifact-contract.md)에 정의했다. PR 2 구현만으로
> EC2 크기가 승인되는 것은 아니다. 실제 운영 release descriptor를 확정하고 8GiB 합격 기준을
> 통과하기 전에는 이 실행서의 GraphHopper 활성화 단계를 운영에 적용하지 않는다.

이 실행서는 `staging-api.runninggu.store` 단일 EC2에 Spring Boot JAR, PostgreSQL 17,
GraphHopper 11, Nginx를 배포하는 순서다. 새 `/health` API나 Actuator를 추가하지 않으며 기존
`GET /api/contests?size=1`로 애플리케이션 준비 상태를 확인한다.

## 1. 고정 구조와 운영 결정

| 항목 | 스테이징 기준 |
|---|---|
| 호스트 | AWS EC2, Ubuntu 24.04 LTS x86_64 |
| 리전 | 서울 `ap-northeast-2` |
| 도메인 | `staging-api.runninggu.store` |
| 프로세스 | Spring Boot는 host systemd. PostgreSQL은 Docker `unless-stopped`, GraphHopper container는 systemd가 foreground Compose로 단일 소유 |
| 인스턴스 크기 | PR 2 구현 뒤 x86_64 8GiB에서 먼저 검증. 4GiB는 계약 시나리오 3회 연속 통과 뒤에만 사용 |
| GraphHopper | graph import는 저장소 고정 Linux builder로 EC2 밖에서 수행. EC2는 검증된 graph server만 실행 |
| 메모리 | 현재 고정값 GraphHopper `-Xmx6g` + Spring Boot `-Xmx2g`는 그대로 배포하지 않음. heap 환경변수화·재시작 상한·실측이 선행, swap 4GB는 긴급 완충용 |
| 외부 포트 | Nginx 80·443만 허용. 5432·8080·8989는 loopback |
| 접속 | SSM Session Manager. SSH 22는 열지 않음 |
| 운영책임자 | 유선경 — AWS·결제·도메인·Google Play·인프라 |
| 운영 연락처 | `runninggu.play@gmail.com` — Certbot·예산 알림 |
| 월 예산 | 총 100,000원, 실제 비용 80,000원·예상 비용 100,000원 도달 알림 |
| 백업 | pgBackRest 일일 전체 백업 + 연속 WAL, 서울 리전 암호화 S3, 7일 보존, 운영책임자만 접근 |

월 100,000원은 스테이징 전체 예산이다. 24시간 상시 기동을 전제로 승인한 값이 아니므로
검증하지 않는 시간에는 EC2를 중지하고, 계획한 월 기동 시간을 비용 계산에 넣는다. 중지 중에도
EBS·공인 IPv4·S3·KMS 등 남는 비용을 포함해 생성 직전 계산한다. 공개 앱이 사용할 프로덕션
상시 서버는 출시 전에 실측 사양과 별도 월 예상비용을 다시 승인한다. GraphHopper import peak는
EC2 사양에 포함하지 않는다. 8GiB에서 heap 환경변수화, cold start, 30분 부하, 전체 백업 동시
실행, 재부팅을 먼저 검증하고, 4GiB는 같은 시나리오를 3회 연속 통과한 뒤에만 사용한다. 현재
고정값 `-Xmx6g` + `-Xmx2g` 상태에서는 8GiB·4GiB 어느 쪽에도 배포하지 않는다.

메모리 부족을 16GiB 임시 증설로 먼저 숨기지 않는다. 합격 기준을 통과하지 못하면 heap·실제
working set·GC·동시 프로세스를 기록하고 사양 또는 구조를 다시 결정한다. 더 큰 사양을 승인할
때는 §15 검증과 월 예상비용·80% 알림값을 같은 결정으로 갱신한다.

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

EC2의 저장소 checkout은 Compose와 GraphHopper **server** Docker build context를 위한 것이다.
EC2에서 graph import image를 실행하거나 JAR을 다시 빌드하지 않고, CI artifact를 다른 commit의
checkout과 섞지 않는다.

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
  awscli \
  git \
  curl \
  unzip \
  dnsutils \
  python3 \
  python3-requests
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
aws --version
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
sudo install -d -o root -g root -m 0755 /opt/runninggu-data
sudo install -d -o root -g root -m 0755 /opt/runninggu-data/graph
sudo install -d -o root -g runninggu -m 0770 /opt/runninggu-validation
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
별도로 추가한다. GraphHopper runtime의 기준 저장소는 Compose의 크기 제한된 Docker `local`
driver다. 주 service의 stdout은 버리고 stderr는 container 생성 전 Compose 오류와 `ExecStartPre`
실패 이유를 위해 journal에 둔다. journal에는 그 밖에 Spring Boot·Importer·백업·WAL 감시와
GraphHopper 검증·알림 같은 control-plane 기록을 보존한다.

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
sudo install -m 0600 -o root -g root \
  backend/deploy/env/backup-alert.env.example \
  /etc/runninggu/backup-alert.env
sudo install -m 0600 -o root -g root \
  backend/deploy/env/graphhopper-alert.env.example \
  /etc/runninggu/graphhopper-alert.env
sudo install -m 0600 -o root -g root \
  backend/deploy/env/backend-alert.env.example \
  /etc/runninggu/backend-alert.env

sudoedit /etc/runninggu/compose.env
sudoedit /etc/runninggu/application.env
sudoedit /etc/runninggu/backup-alert.env
sudoedit /etc/runninggu/graphhopper-alert.env
sudoedit /etc/runninggu/backend-alert.env
```

- 두 파일의 `DB_PASSWORD`에는 같은 URL-safe 값을 넣는다.
- 실제 값은 shell history, 명령 인자, Git, CI 로그에 남기지 않는다.
- `JWT_SECRET`은 Base64 디코딩 결과가 32바이트 이상이어야 한다.
- 스테이징과 운영의 DB·JWT·SMTP·외부 API 키를 공유하지 않는다.
- `SERVER_ADDRESS=127.0.0.1`과 `FORWARD_HEADERS_STRATEGY=framework`를 유지한다.
- springdoc 두 항목은 기본 `false`다.
- `compose.env`의 pgBackRest 항목에는 S3 bucket 이름, 고객 관리형 KMS key ARN, EC2
  instance profile role 이름을 넣는다. 정적 access key는 넣지 않는다.
- `backup-alert.env`에는 운영책임자 이메일을 구독시킨 SNS topic ARN을 넣는다. instance
  profile에는 해당 topic의 `sns:Publish` 최소 권한만 추가한다.
- `graphhopper-alert.env`는 같은 SNS topic을 쓸 수 있지만 알림 unit과 메시지는 백업 실패와
  구분한다.
- `backend-alert.env`도 topic 공유는 가능하지만 Spring Boot 기동·OOM·start-limit 실패로 메시지를
  구분한다. 세 alert env에는 정적 AWS access key를 넣지 않는다.

## 7. GraphHopper graph artifact 설치 준비

EC2에는 대한민국 PBF와 SRTM cache를 설치하지 않는다. 권한 있는 팀원이
[`graphhopper-artifact-contract.md`](graphhopper-artifact-contract.md)의 저장소 고정 builder로
만들어 비공개 S3에 올린 graph artifact ID를 배포 입력으로 받는다.

배포 기록에는 다음을 먼저 남긴다.

- artifact ID와 S3 key
- GraphHopper version·JAR hash·builder image digest
- PBF 파일명·기준일·SHA-256
- SRTM tile 목록 hash와 import 설정 정규화 hash
- archive·압축 해제 graph file 목록 SHA-256
- 생성 시각·생성 주체, 배포 승인자

PR 2는 계약 §3에 정의된 다음 명령 인터페이스를 구현해야 한다. 스크립트가 저장소에 없거나
`runninggu-graphhopper-verify.service` 검증이 실패하면 수동 압축 해제·symlink 전환으로 우회하지
않는다.

```bash
sudo /bin/sh \
  /opt/runninggu/repository/backend/deploy/graphhopper/install-graph-artifact.sh \
  --artifact-id '<검증할_artifact_id>'
```

이 명령은 S3 다운로드, archive·manifest·압축 해제 파일 hash 검증, 최종 version directory
rename까지만 수행한다. 실행 중인 GraphHopper를 중지하고 상대 symlink를 바꾸는 활성화 절차는
최초 배포는 §10, 이후 갱신은 §17.1을 따른다. install script는 첫 배포에서도 `current`를 만들거나
service를 제어하지 않는다.

```text
/opt/runninggu-data/graph/
├── <artifact_id>/
└── current -> <artifact_id>
```

현재와 직전 성공 세대를 유지한다. 새 artifact가 스모크를 통과하기 전에는 이전 세대를 삭제하지
않는다.

기존 EC2가 named volume import 구조로 이미 실행 중이면 계약 §5.2의 일회성 전환 절차를 먼저
적용한다. 실제 Compose project와 graph·SRTM volume 이름을 inspect해 기록하고 새 artifact의
스모크·재부팅·롤백 세대 확보 전에는 지우지 않는다. PostgreSQL volume과 구분하지 않은
`docker compose down -v` 또는 glob 기반 volume 삭제는 금지한다.

## 8. Compose 사전 검증과 PostgreSQL 기동

모든 Compose 명령은 `/opt/runninggu/repository/backend`에서 base와 EC2 파일을 함께 사용한다.
`config` 전체 출력에는 DB 비밀번호가 포함될 수 있으므로 화면이나 로그로 출력하지 않는다.
EC2의 `docker compose version --short`는 `!override`를 지원하는 **2.24.4 이상**이어야 한다.

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

PR 2의 Compose는 이 검증에 더해 다음을 만족해야 한다.

- EC2 GraphHopper service에 PBF·SRTM mount와 import profile이 없다.
- host `/opt/runninggu-data/graph` 상위 directory 하나만 `/data/graph`에 bind mount한다.
- server 설정의 `graph.location`은 `/data/graph/current`다.
- server profile과 LM은 `run` 하나다.
- base `compose.yaml`의 로컬용 `restart: unless-stopped`를 `compose.ec2.yaml`이
  GraphHopper에 한해 `restart: "no"`로 override하며 systemd만 lifecycle을 소유한다.
- GraphHopper logging은 Docker `local`, `max-size: "10m"`, `max-file: "3"`이고 주 systemd
  service는 `StandardOutput=null`, `StandardError=journal`이다. container runtime stderr가
  foreground Compose를 거쳐 journal로 중복되는지는 PR 2에서 실측하고, 중복되면 계약 §7의
  failure-only wrapper를 적용한다.
- PostgreSQL·GraphHopper 포트는 loopback이다.

먼저 PostgreSQL만 시작한다. GraphHopper는 §10에서 주 service와 검증 unit을 설치하고
`current`를 활성화한 뒤 systemd로 시작한다. 운영자가 직접 `docker compose up -d graphhopper`를
실행하지 않는다.

```bash
sudo docker compose \
  --env-file /etc/runninggu/compose.env \
  -f compose.yaml \
  -f compose.ec2.yaml \
  up -d --build postgres
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

저장소 이미지에 고정된 버전을 확인하고, 최초 한 번 stanza를 만든 뒤 WAL archive와 S3 연결을
검사한다. 이 단계가 실패하면 애플리케이션을 시작하지 않는다.

```bash
sudo docker compose \
  --env-file /etc/runninggu/compose.env \
  -f compose.yaml \
  -f compose.ec2.yaml \
  exec -T --user postgres postgres pgbackrest version

sudo docker compose \
  --env-file /etc/runninggu/compose.env \
  -f compose.yaml \
  -f compose.ec2.yaml \
  exec -T --user postgres postgres pgbackrest --stanza=runninggu stanza-create

sudo docker compose \
  --env-file /etc/runninggu/compose.env \
  -f compose.yaml \
  -f compose.ec2.yaml \
  exec -T --user postgres postgres pgbackrest --stanza=runninggu check

sudo /bin/sh deploy/backup/check-wal-archive.sh
```

`pgbackrest version`은 저장소가 고정한 `2.55.1`이어야 한다. PostgreSQL 기동부터 stanza 생성
사이에는 archive 명령이 실패할 수 있으므로 애플리케이션과 감시 timer를 아직 시작하지 않는다.
최초 `check`와 `check-wal-archive.sh`가 모두 성공한 뒤에만 다음 단계로 진행한다.

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

## 10. systemd·GraphHopper 검증 설치와 최초 Flyway·Importer

백엔드·Importer, GraphHopper 주 service·검증·알림, 백업·WAL 감시 service·timer·실패 알림 unit을
설치한다. GraphHopper 주 service만 enable하고 EC2 override의 Docker restart policy는 `"no"`로
둔다. Importer와
검증·백업·WAL 감시 service는 one-shot이며 직접 enable하지 않는다. §7의 최초 archive 검증이
끝났으므로 WAL 감시 timer는 이 절에서 켜고, 백업 timer만 §16의 실제 백업 검증 뒤 enable한다.

```bash
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/systemd/runninggu-contest-import.service \
  /etc/systemd/system/runninggu-contest-import.service
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/systemd/runninggu-backend.service \
  /etc/systemd/system/runninggu-backend.service
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/systemd/runninggu-backend-alert@.service \
  /etc/systemd/system/runninggu-backend-alert@.service
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/systemd/runninggu-graphhopper.service \
  /etc/systemd/system/runninggu-graphhopper.service
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/systemd/runninggu-graphhopper-verify.service \
  /etc/systemd/system/runninggu-graphhopper-verify.service
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/systemd/runninggu-graphhopper-alert@.service \
  /etc/systemd/system/runninggu-graphhopper-alert@.service
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/systemd/runninggu-postgres-backup.service \
  /etc/systemd/system/runninggu-postgres-backup.service
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/systemd/runninggu-postgres-backup.timer \
  /etc/systemd/system/runninggu-postgres-backup.timer
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/systemd/runninggu-postgres-wal-archive-check.service \
  /etc/systemd/system/runninggu-postgres-wal-archive-check.service
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/systemd/runninggu-postgres-wal-archive-check.timer \
  /etc/systemd/system/runninggu-postgres-wal-archive-check.timer
sudo install -m 0644 \
  /opt/runninggu/repository/backend/deploy/systemd/runninggu-backup-alert@.service \
  /etc/systemd/system/runninggu-backup-alert@.service
sudo systemctl daemon-reload
sudo systemd-analyze verify \
  /etc/systemd/system/runninggu-contest-import.service \
  /etc/systemd/system/runninggu-backend.service \
  /etc/systemd/system/runninggu-backend-alert@.service \
  /etc/systemd/system/runninggu-graphhopper.service \
  /etc/systemd/system/runninggu-graphhopper-verify.service \
  /etc/systemd/system/runninggu-graphhopper-alert@.service \
  /etc/systemd/system/runninggu-postgres-backup.service \
  /etc/systemd/system/runninggu-postgres-backup.timer \
  /etc/systemd/system/runninggu-postgres-wal-archive-check.service \
  /etc/systemd/system/runninggu-postgres-wal-archive-check.timer \
  /etc/systemd/system/runninggu-backup-alert@.service

cd /opt/runninggu/repository/backend
sudo docker compose \
  --env-file /etc/runninggu/compose.env \
  --profile routing \
  -f compose.yaml \
  -f compose.ec2.yaml \
  build graphhopper

cd /opt/runninggu-data/graph
test -d '<최초_artifact_id>'
test ! -e current
test ! -L current
sudo ln -s '<최초_artifact_id>' 'current.next.<배포_식별자>'
sudo mv -Tf 'current.next.<배포_식별자>' current

graphhopper_activation_started_ms=$(date +%s%3N)
graphhopper_verify_started_ms=$(date +%s%3N)
sudo systemctl start runninggu-graphhopper-verify.service
graphhopper_verify_finished_ms=$(date +%s%3N)
sudo systemctl status --no-pager runninggu-graphhopper-verify.service
sudo systemctl enable runninggu-graphhopper.service
graphhopper_prestart_started_ms=$(date +%s%3N)
sudo systemctl start runninggu-graphhopper.service
graphhopper_prestart_finished_ms=$(date +%s%3N)
sudo systemctl status --no-pager runninggu-graphhopper.service

graphhopper_ready=0
for attempt in $(seq 1 60); do
  if curl --fail --silent --show-error \
    --output /dev/null \
    'http://127.0.0.1:8989/route?point=37.5665,126.9780&profile=run&algorithm=round_trip&round_trip.distance=5000&round_trip.seed=0'
  then
    graphhopper_ready=1
    break
  fi
  sleep 2
done
test "$graphhopper_ready" -eq 1
graphhopper_ready_ms=$(date +%s%3N)
test $((graphhopper_ready_ms - graphhopper_activation_started_ms)) -le 120000

echo "verify_ms=$((graphhopper_verify_finished_ms - graphhopper_verify_started_ms))"
echo "start_job_with_exec_start_pre_ms=$((graphhopper_prestart_finished_ms - graphhopper_prestart_started_ms))"
echo "activation_to_ready_ms=$((graphhopper_ready_ms - graphhopper_activation_started_ms))"

cd /opt/runninggu/repository/backend
sudo docker compose \
  --env-file /etc/runninggu/compose.env \
  --profile routing \
  -f compose.yaml \
  -f compose.ec2.yaml \
  logs --tail 200 graphhopper

sudo systemctl start runninggu-postgres-wal-archive-check.service
sudo systemctl status --no-pager runninggu-postgres-wal-archive-check.service
sudo systemctl enable --now runninggu-postgres-wal-archive-check.timer
sudo systemctl list-timers runninggu-postgres-wal-archive-check.timer --no-pager
```

`docker compose logs graphhopper`는 GraphHopper runtime 로그의 기준 저장소다. 여기에는
manifest의 artifact ID와 기존 graph load가 있어야 하고 PBF 읽기·SRTM download·import 시작
로그가 없어야 한다. 주 service의 Compose·`ExecStartPre` 실패는
`journalctl -u runninggu-graphhopper.service`, 검증·실패 알림은
`journalctl -u runninggu-graphhopper-verify.service`와
`journalctl -u 'runninggu-graphhopper-alert@*'`에서 확인한다. 주 service journal에 foreground
Compose가 릴레이한 container runtime stdout·stderr가 있으면 실패다. 주 service는
`start-graphhopper-compose.sh` wrapper로 runtime stderr를 내부 임시 파일에만 받고, 실패했을 때만
민감정보를 제거한 종료 code와 마지막 stderr 일부를 journal에 남긴다.

`systemctl show runninggu-graphhopper.service`에서 `NRestarts`, `StartLimitIntervalUSec`,
`StartLimitBurst`, `TimeoutStartUSec`, `TimeoutStopUSec`, `StandardOutput`, `StandardError`도
확인한다. `TimeoutStopUSec`는 container의 `stop_grace_period=30s`보다 긴 45초여야 Compose stop이
중간에 잘리지 않는다. 초기
`TimeoutStartSec=60s`는 검증 전용 후보이고 전체 readiness 120초와 같은 뜻이 아니다. 검증
unit·주 service의
`ExecStartPre` 또는 내부 스모크가 실패하면 Spring Boot를 시작하지 않는다. 운영자가 직접
`docker compose up -d graphhopper`로 systemd를 우회하지 않는다.

첫 8GiB baseline에서는 `docker inspect`의 GraphHopper `HostConfig.Memory`가 `0`,
`systemctl show runninggu-backend.service -p MemoryHigh -p MemoryMax`가 `infinity`인지 확인한다.
이는 상한 누락이 아니라 실제 peak를 얻기 위한 명시적 무제한 상태다. 계약 §9.3 합격 뒤에만
`compose.env`의 `GRAPHHOPPER_MEMORY_RESERVATION`·`GRAPHHOPPER_MEMORY_LIMIT`과 backend unit의
`MemoryHigh`·`MemoryMax`를 관측값으로 함께 바꾸고 `daemon-reload` 후 전체 시험을 반복한다.

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

첫 배포에서는 enable과 start를 함께 수행한다. 이후 배포는 §17에 따라 백엔드를 먼저 중지하고
artifact 링크와 대회 snapshot을 적용한 뒤 다시 시작한다.

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

### 15.1 고정 부하·5초 자원·GC 기록

먼저 품질 회귀와 JVM warm-up을 한 번 수행한다. 이 결과는 30분 부하 시간에 포함하지 않는다.

```bash
cd /opt/runninggu/repository
python3 scripts/osm/roundtrip.py --preset caps --zone all
```

시험 전에 batch 도착률과 동시성을 운영 기록에 확정한다. 이 값은 8GiB와 4GiB에서 같아야 하며
아래 placeholder를 채우지 않은 명령은 실행하지 않는다. `operational_load.py`의 한 batch는 실제
백엔드와 같이 한 지점·한 목표 거리의 seed 0~15를 직렬 호출한다. worker 포화로 예정 시각에
batch를 시작하지 못하면 기다려 요청량을 줄이지 않고 missed start로 남겨 실패한다.

첫 SSM session에서 5초 자원 수집기와 30분 부하를 실행한다.

```bash
RUN_ID='<8g-baseline-YYYYMMDD-HHMM>'
BATCHES_PER_MINUTE='<사전_확정값>'
CONCURRENCY='<사전_확정값>'

sudo install -d -o root -g runninggu -m 0770 \
  "/opt/runninggu-validation/$RUN_ID"

TEST_STARTED_AT=$(date --iso-8601=seconds)
printf '%s\n' "$TEST_STARTED_AT" \
  | sudo tee "/opt/runninggu-validation/$RUN_ID/started-at.txt" >/dev/null

sudo /bin/sh \
  /opt/runninggu/repository/backend/deploy/validation/collect-runtime-metrics.sh \
  --duration-seconds 1800 \
  --interval-seconds 5 \
  --output "/opt/runninggu-validation/$RUN_ID/runtime-metrics.log" &
METRICS_PID=$!

sudo -u runninggu python3 \
  /opt/runninggu/repository/scripts/osm/operational_load.py \
  --duration-seconds 1800 \
  --batches-per-minute "$BATCHES_PER_MINUTE" \
  --concurrency "$CONCURRENCY" \
  --seeds 16 \
  --timeout-seconds 5 \
  --output "/opt/runninggu-validation/$RUN_ID/graphhopper-load.jsonl"
LOAD_EXIT=$?

wait "$METRICS_PID"
test "$LOAD_EXIT" -eq 0
tail -n 1 "/opt/runninggu-validation/$RUN_ID/graphhopper-load.jsonl"

sudo python3 \
  /opt/runninggu/repository/backend/deploy/validation/summarize-runtime-metrics.py \
  --metrics "/opt/runninggu-validation/$RUN_ID/runtime-metrics.log" \
  --output "/opt/runninggu-validation/$RUN_ID/runtime-summary.json"
```

둘째 SSM session에서는 부하 중 수동 full backup을 한 번 실행한다.

```bash
sudo systemctl start runninggu-postgres-backup.service
sudo systemctl status --no-pager runninggu-postgres-backup.service
```

같은 session에서 부하 시작 후 서로 다른 세 시점(예: 5분·15분·25분)에 WAL 검사를 실행한다.

```bash
sudo systemctl start runninggu-postgres-wal-archive-check.service
sudo systemctl status --no-pager runninggu-postgres-wal-archive-check.service
```

부하 종료 뒤 `graphhopper-load.jsonl` 마지막 summary의 `passed=true`, `missedBatchStarts=0`,
`failedDirectRequests=0`, `requestsOverTimeout=0`을 확인하고 직접 요청과 seed batch의 p50·p95·max를
기록한다. `runtime-summary.json`도 `passed=true`여야 한다. 이 요약기는 표본 누락,
`MemAvailable` 20% 미만, warm-up 뒤 swap counter·사용량 증가, systemd 재시작, container
OOM·비정상 상태를 실패시킨다.

GraphHopper와 Spring Boot는 운영 시작 명령에 JVM unified GC 로그를 켠다. 시험 시작 뒤 Full GC를
다음 두 기준 저장소에서 확인한다. readiness 이후 반복 Full GC가 있으면 실패다.

```bash
TEST_STARTED_AT=$(sudo cat "/opt/runninggu-validation/$RUN_ID/started-at.txt")

cd /opt/runninggu/repository/backend
sudo docker compose \
  --env-file /etc/runninggu/compose.env \
  --profile routing \
  -f compose.yaml \
  -f compose.ec2.yaml \
  logs --since "$TEST_STARTED_AT" graphhopper \
  | grep -E 'Pause Full|Full GC' || true

sudo journalctl \
  -u runninggu-backend.service \
  --since "$TEST_STARTED_AT" \
  --no-pager \
  | grep -E 'Pause Full|Full GC' || true
```

정상 부하 OOM 여부도 같은 시간 범위에서 확인한다.

```bash
sudo journalctl -k --since "$TEST_STARTED_AT" --no-pager \
  | grep -Ei 'oom|out of memory|killed process' || true

cd /opt/runninggu/repository/backend
graphhopper_container=$(sudo docker compose \
  --env-file /etc/runninggu/compose.env \
  --profile routing \
  -f compose.yaml \
  -f compose.ec2.yaml \
  ps -q graphhopper)
sudo docker inspect "$graphhopper_container" \
  --format 'OOMKilled={{.State.OOMKilled}} ExitCode={{.State.ExitCode}} Status={{.State.Status}}'
```

### 15.2 외부 스모크·재부팅

외부 HTTPS에서 기존 API를 확인한다.

```bash
curl --fail --silent --show-error \
  --output /dev/null \
  'https://staging-api.runninggu.store/api/contests?size=1'
```

GraphHopper 인스턴스 사양 시험은 계약 §9.1의 두 요청 세트를 먼저 고정한다. EC2에서 처음 실행하기
전에 동일한 graph artifact와 server image로 로컬 운영 호환 container를 띄우고 다음 근거를 release
evidence에 남긴다.

1. `roundtrip.py --preset caps --zone all` 전체 지점·거리·seed별 HTTP status와 셀별 품질 상한 통과
   후보 수
2. 위 결과에서 HTTP 200·비어 있지 않은 `paths`가 확인된 정상 직접 요청의 exact
   `point`·`round_trip.distance`·`round_trip.seed`·요청 옵션 목록
3. graph artifact ID·server image digest·실행 명령·시작/종료 시각

정상 직접 요청 목록은 로컬 회귀 기준선에서 합격한 모든 지점·거리 셀을 최소 하나씩 포함한다.
EC2 결과를 본 뒤 실패한 요청을 목록에서 빼거나 다른 seed로 교체하지 않는다. artifact·image·profile
또는 요청 옵션이 바뀌면 로컬 고정부터 다시 수행한다.

30분 메모리·처리량 부하는 정상 직접 요청 세트만 고정 도착률로 반복한다. 이 세트에서는 모든 요청이
5초 안에 HTTP 200과 비어 있지 않은 `paths`를 반환해야 하며 `no valid point` 400도 예외 없이 시험
실패다. 별도로 전체 `caps/all` 회귀를 실행해 개별 400을 라우팅 실패로 집계하고, 로컬에서 합격한
지점·거리 셀이 EC2에서도 seed 16개 중 품질 상한 통과 경로를 하나 이상 유지하는지 확인한다.
`no valid point`를 성공으로 기록하거나 실패 건수에서 빼지 않되, 그 응답만으로 OOM이나 instance
부족이라고 판정하지 않는다. OOM·5xx·timeout·재시작과 라우팅 실패 수를 분리해 결과에 남긴다.

다음도 확인한다.

- HTTP가 HTTPS로 redirect되는가
- Swagger와 `/v3/api-docs`가 비활성인가
- 외부에서 5432·8080·8989에 연결할 수 없는가
- Nginx access log가 쿼리스트링을 제외한 `runninggu_noqs` 포맷을 사용하는가
- GraphHopper가 같은 graph artifact ID를 재사용하고 PBF·SRTM download·import를 시작하지 않는가
- GraphHopper runtime stdout은 Docker `local`에만 있고 주 service journal에 중복되지 않는가
- container runtime stderr가 주 service journal로 릴레이되지 않고, failure-only wrapper가
  Compose 실패의 정제된 마지막 stderr만 남기는가
- GraphHopper 검증·알림 journal과 Docker runtime log 모두에 PBF 내용·AWS 자격 증명·사용자 정보가 없는가
- `free -h`, `docker stats --no-stream`, `systemd-cgtop`에서 swap·메모리 합격 기준을 만족하는가

마지막으로 EC2를 한 번 재부팅해 PostgreSQL의 `unless-stopped`, Docker 의존
`runninggu-graphhopper.service`, Spring Boot, Nginx, certbot timer가 모두 복구되는지 확인한다.
GraphHopper는 Docker가 직접 자동 시작하면 실패다. systemd 주 service의 `ExecStartPre`가 활성
artifact를 먼저 검증한 뒤 같은 artifact ID로 server를 시작했고 `NRestarts`가 불필요하게 늘지
않았는지 확인한다. 재부팅에서는 GraphHopper unit activation부터, artifact 배포에서는 verify
oneshot 시작부터 GraphHopper·Spring readiness까지 2분이며, verify와 `ExecStartPre` 포함 start job
소요시간을 각각 기록한다. 활성 graph를 고의로 손상시키는 시험은 운영 재부팅 검증과 섞지 않고
계약 §9.3의 격리 환경에서만 수행한다.

PR 2 unit 검증에서는 다음 여섯 경로를 별도 Compose project로 확인한다.

1. `systemctl stop`은 container를 정상 종료하고 자동 재시작하지 않는다.
2. GraphHopper 또는 foreground Compose가 예상하지 않게 exit 0이면 `Restart=always`가 다시 시작한다.
3. cgroup OOM/exit 137은 5초 간격으로 재시도하되 PostgreSQL을 종료하지 않는다.
4. 10분 window의 start 시도 3회를 소진하면 무한 반복하지 않고 failed 상태와 전용 알림을 남긴다.
5. 격리된 시험 env에서 Compose required 변수를 하나 누락하면 container 생성 전 보간 실패 이유가
   주 service journal에 남고 시크릿 값은 남지 않는다.
6. container stderr 표식은 Docker `local`에 남되 주 service journal에는 중복되지 않는다. 직접
   Compose에서 중복되면 계약 §7의 wrapper 적용 뒤 다시 통과해야 한다.

인스턴스 크기 판정은 [`graphhopper-artifact-contract.md` §9](graphhopper-artifact-contract.md#9-사전-합격-기준)의
고정 정상 요청 30분 부하, 전체 라우팅 회귀, 백업·OOM·GC·swap·재부팅 기준을 모두 사용한다.
8GiB 한 번 통과를 4GiB 승인 근거로 쓰지 않으며 4GiB는 같은 요청 목록·요청률·동시성과 전체
시나리오를 3회 연속 통과해야 한다.

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

저장소의 #227 구현은 다음을 제공한다. 실제 S3·KMS·instance profile·SNS topic을 만들고 아래
명령을 검증하기 전에는 TOS 1.1·PRIVACY 1.2를 활성화하지 않는다.

1. PostgreSQL `17.10-trixie`와 pgBackRest `2.55.1-1`을 고정한 저장소 관리 이미지
2. `/var/lib/postgresql/data`와 pgBackRest 설정을 연결한 Compose 구성
3. 일일 전체 백업 systemd service·timer와 실패 알림
4. 아래 설정·초기화·백업·복원 명령의 실행 검증

S3 버킷은 public access block을 모두 켜고, TLS가 아닌 요청을 거부하며, bucket versioning을
켠다면 현재·비현재 객체가 모두 7일 정책을 지키도록 lifecycle을 함께 설정한다. 불완전한
multipart upload는 1일 뒤 중단한다. 버킷 이름·KMS key ARN·role 이름은 운영 환경에서 채우고
저장소에는 넣지 않는다.

pgBackRest 설정 기준은 다음과 같다. 공통 값은 `backend/deploy/pgbackrest/pgbackrest.conf`에
있고 실제 bucket·KMS key·role 값은 `compose.env`에서 `PGBACKREST_REPO1_*` 환경변수로
주입한다.

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

stanza 생성과 최초 `check`는 §8에서 수행한다. 백업 timer는 매일 03:20 KST에 다음 순서로
실행한다. 별도 WAL 감시 timer는 10분마다 `pg_stat_archiver`의 마지막 실패가 이후 성공으로
회복됐는지와 10분 넘게 `.ready` 상태인 segment가 있는지 확인한다. 백업·`check`·WAL 감시가
실패하면 해당 service가 실패하고 `runninggu-backup-alert@.service`가 SNS로 운영책임자에게
알린다.

```bash
docker compose --env-file /etc/runninggu/compose.env \
  -f compose.yaml -f compose.ec2.yaml \
  exec -T --user postgres postgres pgbackrest --stanza=runninggu --type=full backup

docker compose --env-file /etc/runninggu/compose.env \
  -f compose.yaml -f compose.ec2.yaml \
  exec -T --user postgres postgres pgbackrest --stanza=runninggu expire

docker compose --env-file /etc/runninggu/compose.env \
  -f compose.yaml -f compose.ec2.yaml \
  exec -T --user postgres postgres pgbackrest --stanza=runninggu check
```

백업 timer를 켜기 전에 백업 service를 수동 실행하고 백업 정보를 확인한다. WAL 감시는 §10에서
수동 실행과 timer 활성화를 마쳤으므로 현재 상태를 함께 확인한다.

```bash
sudo systemctl start runninggu-postgres-backup.service
sudo systemctl status --no-pager runninggu-postgres-backup.service
sudo docker compose --env-file /etc/runninggu/compose.env \
  -f compose.yaml -f compose.ec2.yaml \
  exec -T --user postgres postgres pgbackrest --stanza=runninggu info --output=json
sudo systemctl enable --now runninggu-postgres-backup.timer
sudo systemctl status --no-pager runninggu-postgres-wal-archive-check.timer
sudo systemctl list-timers \
  runninggu-postgres-backup.timer \
  runninggu-postgres-wal-archive-check.timer \
  --no-pager
```

`pgbackrest info --output=json`의 마지막 성공 시각, backup label, WAL archive 범위만 운영 기록에
남긴다. 사용자 개인정보·S3 자격 증명은 로그에 남기지 않는다.

### 16.2 복구 리허설

실제 서비스 공개 전 한 번, 이후 분기마다 격리된 별도 Compose project·새 volume에서 복구를
리허설한다. `compose.recovery.yaml`은 운영 포트를 열지 않는 독립 파일이며
`runninggu-postgres-recovery-data` volume만 사용한다. 운영 volume에 `restore --delta`를
시험하지 않는다. `/etc/runninggu/recovery-compose.env`는 운영 DB 비밀번호를 재사용하지 않고
S3·KMS·role 값만 같은 저장소를 가리키게 만든다. 복구 명령의 project 이름과 volume을 먼저
확인한 뒤 다음처럼 최신 안전 시점을 복원한다.

```bash
docker compose --project-name runninggu-recovery \
  --env-file /etc/runninggu/recovery-compose.env \
  -f compose.recovery.yaml \
  run --rm --no-deps --entrypoint sh postgres \
  -c 'install -d -o postgres -g postgres -m 0700 "$PGDATA"'

docker compose --project-name runninggu-recovery \
  --env-file /etc/runninggu/recovery-compose.env \
  -f compose.recovery.yaml \
  run --rm --no-deps --user postgres --entrypoint pgbackrest postgres \
  --stanza=runninggu restore
```

복원이 끝난 뒤 같은 독립 파일로 PostgreSQL을 기동한다. `archive_mode=off`를 강제하므로 복구
리허설이 운영 S3 repository에 새 WAL을 밀어 넣지 않는다.

```bash
docker compose --project-name runninggu-recovery \
  --env-file /etc/runninggu/recovery-compose.env \
  -f compose.recovery.yaml \
  up -d postgres
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
3. 새 release 디렉터리를 만든다.
4. `runninggu-backend.service`를 중지한다.
5. 애플리케이션 `current` 링크를 새 release로 바꾼다.
6. `runninggu-contest-import.service`를 명시 실행한다.
7. Flyway·Importer 성공 또는 `NO_OP` 뒤 `runninggu-backend.service`를 시작한다.
8. 내부 readiness와 외부 HTTPS 스모크를 통과시킨다.
9. 직전 artifact와 DB 백업을 정해진 보존 정책에 따라 유지한다.

DB 마이그레이션은 이전 서버와 역호환되게 설계한다. Importer 또는 앱 기동 실패 시 DB migration을
임의로 되돌리지 않는다. Importer 실패 시 직전 애플리케이션 symlink로 복원해 기존 백엔드를
다시 시작할 수 있는지 확인하고, 적용된 Flyway와 실패 원인을 기록한다.

### 17.1 GraphHopper graph artifact 갱신

애플리케이션 배포와 graph 갱신은 독립 배포 단위다. PBF·GraphHopper version·import 영향 설정이
바뀐 릴리스에서만 새 graph artifact를 활성화한다.

1. §7의 install script로 새 세대를 다운로드·검증하고 현재 artifact ID를 기록한다.
2. 새 version directory와 기존 `current` 상대 symlink를 확인한다.
3. `runninggu-graphhopper.service`를 중지한다. container를 직접 제어하지 않는다.
4. 새 상대 symlink를 임시 이름으로 만든 뒤 rename해 `current`를 원자적으로 교체한다.
5. `runninggu-graphhopper-verify.service`를 실행한다.
6. 검증 성공 뒤 `runninggu-graphhopper.service`를 시작하고 내부 round-trip 스모크를 실행한다.
   주 service의 `ExecStartPre`가 같은 검증을 다시 수행한다.
7. 실패하면 주 service를 중지하고 직전 symlink로 복원한 뒤 검증·시작·스모크한다.

5~6의 두 검증은 배포 게이트와 모든 기동 게이트로 각각 유지한다. 두 검증 시간과 5번 시작부터
readiness까지의 전체 시간을 기록하며 전체 2분 기준에 포함한다.

```bash
sudo systemctl stop runninggu-graphhopper.service

cd /opt/runninggu-data/graph
sudo ln -s '<새_artifact_id>' 'current.next.<배포_식별자>'
sudo mv -Tf 'current.next.<배포_식별자>' current

sudo systemctl start runninggu-graphhopper-verify.service
sudo systemctl start runninggu-graphhopper.service
```

실행 중 symlink를 먼저 바꾸거나 `current` 자체를 bind source로 바꾸지 않는다. GraphHopper server
image 또는 server 설정도 바뀐 배포라면 service 시작 전에 exact commit의 image를 명시적으로
build한다. 주 service의 foreground Compose가 필요한 container recreate를 수행하며, 검증기는 새
server image의 version·JAR hash label과 활성 manifest도 대조한다.

## 18. 애플리케이션 artifact 롤백

호환되는 직전 release를 명시적으로 선택한다.

```bash
sudo systemctl stop runninggu-backend.service
sudo ln -sfn \
  /opt/runninggu/releases/<직전_git_commit> \
  /opt/runninggu/current
sudo systemctl start runninggu-backend.service
```

11장의 내부 준비 확인과 15장의 외부 스모크를 다시 실행한다. 이전 JAR가 이미 적용된 DB migration과
호환되지 않으면 JAR만 되돌리지 않고 상위 배포 가이드 §7에서 정한 복구 절차를 따른다.
`docker compose down -v`,
PostgreSQL volume 삭제, GraphHopper version directory 삭제는 롤백 명령이 아니다.

GraphHopper graph rollback은 §17.1과 같은 stop → 직전 상대 symlink 원자 교체 → verify → start →
스모크 순서를 사용한다. 현재·직전 성공 세대가 아닌 임의 PBF 재import로 롤백하지 않는다.

## 19. 출시 차단 항목 갱신 기준

저장소에 이 실행서와 템플릿이 생긴 것만으로 배포 BLOCKER를 해소하지 않는다. 다음 근거가 모두
있을 때 [`development-release-contest-guide.md` §8](../development-release-contest-guide.md#8-현재-저장소의-출시-차단-항목)을
갱신한다.

- 실제 EC2·Elastic IP·DNS·HTTPS 연결
- CI artifact와 EC2 release SHA 일치 기록
- GraphHopper builder image digest·manifest·S3 artifact checksum·활성/직전 세대 기록
- GraphHopper Docker local 로그 단일 저장·검증/알림 journal 분리, verify·`ExecStartPre` 시간과 exit 0/137·정상 stop·start limit 시험 기록
- Flyway·Importer·앱·GraphHopper·SMTP 스모크 결과
- certbot 자동 갱신 dry-run
- DB 백업과 실제 복원 리허설
- 8GiB 재부팅 자동 복구와 30분 메모리·백업 동시 부하 합격 기록
- 4GiB를 사용한다면 같은 시나리오 3회 연속 합격 기록
- Android 스테이징 `BASE_URL` E2E
