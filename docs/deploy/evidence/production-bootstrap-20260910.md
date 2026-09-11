# 운영 EC2 부트스트랩 증거 — 2026-09-10

> 상태: **운영 환경 구성·배포 완료**. 성공한 `develop` push의 exact artifact를 설치했고,
> DNS·HTTPS·백업 복구·로그 개인정보·재부팅 자동 복구까지 검증했다.

## 자원 정리와 운영 자원

- 기존 4GiB 스테이징 서버와 그 종속 자원은 유지했다.
- 사용하지 않는 8GiB 시험 EC2, root EBS, Elastic IP를 삭제했다.
- 사용하지 않는 Aurora MySQL cluster·instance와 수동 MySQL snapshot을 삭제했다.
- 서울 `ap-northeast-2b`에 `c7i-flex.large` 2 vCPU·4GiB 운영 EC2
  `i-0c9f040b65d41d6c1`을 만들고 EIP `3.37.39.89`를 연결했다.
- root volume은 암호화 gp3 30GiB이고 종료 방지를 켰다. key pair는 만들지 않았으며 보안 그룹은
  외부 TCP 80·443만 허용한다.

## 백업·그래프·권한

- 운영 백업 bucket `runninggu-production-backup-987622176638-seoul`을 만들었다.
- public access 네 항목을 모두 차단하고 Bucket Owner Enforced, 고객 관리형 KMS 기본 암호화,
  Bucket Key, `Environment=production` 비용 태그를 확인했다. 버전 관리는 사용하지 않는다.
- 기존 승인 GraphHopper artifact 세 파일을 staging prefix에서 production prefix로 server-side
  copy했다. `SHA256SUMS`와 manifest byte 비교, archive 크기 비교가 모두 일치했다.
- 운영 EC2 role은 production graph 읽기, 운영 backup prefix 관리, 운영 Parameter Store 읽기,
  지정 KMS key 사용, 운영 SNS publish만 허용한다.
- 인스턴스 역할로 운영 SecureString 세 개를 복호화하고 graph archive를 조회했다. 이어서 운영
  backup prefix에 KMS 암호화 시험 객체를 쓰고 암호화 상태를 확인한 뒤 시험 객체를 삭제했다.
  SSM 명령 `72bb80c6-865a-4efd-87e1-474f925efaa0`은 `ROLE_ACCESS_VERIFY_OK`로 성공했다.

## 시크릿·알림·비용

- 운영 DB password와 JWT secret을 새로 생성했다.
- Resend에는 `runninggu.store` 전송만 허용한 운영 전용 Sending key를 생성했다.
- 운영 Kakao 앱 `런닝구 운영`을 만들고 운영 전용 REST key·app ID를 저장했다.
- KTO service key는 결정-63에 따라 staging과 공유했다. 원문을 출력하지 않고 staging 서버에서
  KMS로 암호화해 전달했으며, 두 환경의 호출 쿼터를 합산 관리한다. 운영계정 승인 뒤 실제
  쿼터와 별도 키 발급 가능 여부를 확인해 필요하면 환경별 키로 분리한다.
- 여섯 값은 지정 KMS key를 쓰는 Parameter Store `SecureString`으로 저장했다. 경로 이름만
  `/runninggu/production/db-password`, `jwt-secret`, `smtp-password`, `kto-service-key`,
  `kakao-rest-key`, `kakao-app-id`로 기록하고 값은 출력하지 않았다.
- 운영 SNS topic `runninggu-production-alerts`의 이메일 구독을 확인하고 시험 알림의 실제 수신을
  확인했다.
- AWS Budget `runninggu-production-monthly`를 월 USD 70, 비용 필터
  `Environment=production`으로 만들었다. 실제 비용 80%, 예상 비용 100% 알림 두 건을 확인했다.

## 호스트 설치·보안 확인

SSM `683ba0c8-44ae-4d72-a51c-1217ee3bbdba`로 다음 버전을 설치했다.

| 항목 | 확인값 |
|---|---|
| Docker Engine | `29.1.3` |
| Docker Compose | `2.40.3` |
| Java | OpenJDK `21.0.12` |
| Nginx | `1.24.0` |
| AWS CLI | `2.36.42` |

- `/opt/runninggu`, `/opt/runninggu-data`는 `runninggu:runninggu` 0750,
  `/etc/runninggu`는 `root:runninggu` 0750이다.
- 4GiB swap과 `vm.swappiness=10`을 적용했다.
- journal은 500MiB 상한, 파일당 50MiB 상한, 디스크 1GiB 보존을 적용했다.
- 호스트 SSH service·socket을 중지·비활성·mask했다. 확인 시 외부 listener는 80번뿐이었다.
- Nginx는 ACME challenge 경로 외 요청을 404로 거부한다. 외부 `http://3.37.39.89/`도 HTTP 404였다.

## 백업·복구 리허설

- 운영 PostgreSQL은 loopback에서 healthy다. 최초 `stanza-create`에서 확인한 DB 역할·TLS CA
  누락은 PR #331의 설정으로 바로잡았고, full backup·WAL archive·`pgbackrest check`가 성공했다.
  운영 S3 객체의 고객 관리형 KMS 암호화와 backup·WAL 검사 timer active 상태도 확인했다.
- 승인된 GraphHopper artifact를 production prefix에서 설치했고 route readiness와 systemd active를
  확인했다.
- SSM `76654c7a-109d-4247-9cc3-13c08226c600`과
  `38a8cca8-e07f-4ba2-b00d-479c746bc0cd`로 full backup을 `runninggu-recovery` 전용 볼륨에
  복원했다. 복원 DB는 `runninggu`, recovery 종료 상태 `false`, public table 0개였다. 운영 DB
  볼륨이 바뀌지 않은 것을 확인하고 복구 컨테이너·네트워크·볼륨·임시 환경 파일을 정리했다.

## 애플리케이션 릴리스

- 최초 기동에는 `develop` push CI run `34465367313`의 exact commit
  `801fa7d5ce8bf8df3e831d72df60a55e7a827618` artifact를 사용했다.
- Importer의 Flyway·초기 snapshot 적재가 종료 코드 0으로 끝났다. 백엔드는 11초 뒤 내부
  `GET /api/contests?size=1`에 200을 반환했고 `NRestarts=0`이었다.
- PR #331 승인·머지 후 성공한 `develop` push CI run `34487652241`의 exact commit
  `a2c7700671f6c9ca60c67687b9e7863cfeb144a6` artifact로 최종 전환했다.
- CloudShell·S3·EC2에서 bundle과 내부 `SHA256SUMS`, `release-manifest.txt`를 검증했다.
  최종 bundle은 119,563,022바이트이고 SHA-256은
  `34aee9319f1034009a0741199f9ff85fbb3800dad1511ccca043a868d144eb51`다.
- SSM `a1a32bc5-8fe9-4d90-8989-a70299b44ba8`에서 현재 release symlink와 서버 저장소 HEAD를
  같은 commit으로 맞추고 저장소가 clean인지 확인했다. API는 기동 후 200, `NRestarts=0`이었다.

## DNS·TLS·공개 경계

- 가비아에 `api.runninggu.store` A record를 `3.37.39.89`, TTL 600으로 저장했다. 가비아 권한
  DNS 세 곳과 Google·KT 공개 resolver가 모두 같은 값을 반환했다.
- Let's Encrypt 인증서를 발급하고 최종 Nginx 설정을 적용했다. 인증서 만료일은 2026-12-09이며
  certbot·logrotate timer가 active다. SSM `94063a98-bcf2-4d85-9dff-fe6844c21a85`에서
  HTTP 301·HTTPS API 200과 `nginx -t`를 확인했다.
- SSM `7b103e33-a943-4111-9710-62183d2a50dd`에서 인증서 갱신 모의 실행과 deploy hook이
  성공했다. 같은 명령에서 운영 로그 개인정보 검사도 통과했다.
- CloudShell 외부 요청에서 HTTPS 인증서 검증 결과 0, API 200, HTTP 301과 정확한 HTTPS
  redirect를 확인했다. `/v3/api-docs`와 Swagger는 404였고 22·5432·8080·8989는 닫혀 있었다.

## 재부팅 자동 복구

- 운영 EC2를 재부팅한 뒤 SSM이 다시 Online이 됐고, SSM
  `fe88ddbe-4768-4f7a-a292-99285ae83c68`에서 Docker·Nginx·GraphHopper·백엔드와
  certbot·logrotate·PostgreSQL backup·WAL 검사 timer가 모두 active인지 확인했다.
- 현재 release와 저장소 HEAD는 최종 exact commit이고 저장소는 clean이었다. API 200,
  문서 API 404, `NRestarts=0`, `nginx -t`도 다시 통과했다.

PR head artifact는 staging 전용 계약이므로 운영 서버에 설치하지 않는다.
