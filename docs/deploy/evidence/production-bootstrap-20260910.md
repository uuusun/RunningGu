# 운영 EC2 부트스트랩 증거 — 2026-09-10

> 상태: **기반 자원·PostgreSQL·GraphHopper·백업/복구 리허설 완료, Spring Boot 공개 전**.
> 운영 DNS와 외부 API 운영 키, 로그 보안 변경이 머지되기 전에는 Spring Boot를 시작하지 않는다.

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
- 세 값은 지정 KMS key를 쓰는 Parameter Store `SecureString`으로 저장했다. 경로 이름만
  `/runninggu/production/db-password`, `jwt-secret`, `smtp-password`로 기록하고 값은 출력하지 않았다.
- 운영 SNS topic `runninggu-production-alerts`를 만들었다. 이메일 구독 상태 `Confirmed` 1건과
  시험 알림 전송·수신을 확인했다.
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

## CI 산출물·DB·그래프·복구 리허설

- `develop` push CI가 성공한 exact commit `da4799089b9cf774adf89ef590cadde83b644eb5`의
  backend artifact를 내려받아 네 파일의 `SHA256SUMS`를 확인하고 운영 서버에 설치했다.
- 운영 PostgreSQL은 loopback에서 healthy다. 최초 `stanza-create`에서 확인한 DB 역할·TLS CA
  누락은 PR #331의 설정을 운영 서버에 임시 적용해 바로잡았고, full backup·WAL archive·
  `pgbackrest check`가 성공했다. 운영 S3 객체가 고객 관리형 KMS로 암호화된 것도 확인했다.
- 승인된 GraphHopper artifact를 production prefix에서 설치했고 route readiness와 systemd active를
  확인했다. backup·WAL 감시 timer도 active다.
- SSM `76654c7a-109d-4247-9cc3-13c08226c600`과
  `38a8cca8-e07f-4ba2-b00d-479c746bc0cd`로 full backup을
  `runninggu-recovery` 전용 볼륨에 복원했다. 복원 DB는 `runninggu`, recovery 종료 상태 `false`,
  public table 0개로 확인됐다. 운영 DB 볼륨 이름이 바뀌지 않은 것을 확인한 뒤 복구 컨테이너·
  네트워크·볼륨·임시 환경 파일을 삭제했고 운영 PostgreSQL health가 유지됐다.
- 운영 Nginx HTTP bootstrap에 URI·query·Host·User-Agent를 기록하지 않는 최소 로그 형식을
  적용했다. 가짜 이메일·좌표 요청의 시험 표식 0건과 method allowlist 기록을 확인했다.

## 아직 완료하지 않은 항목

1. 가비아 `api.runninggu.store` A record를 EIP로 연결
2. 운영 KTO service key 정책 확정, Kakao app·REST key·app ID 발급과 SecureString 저장
3. PR #331과 로그 보안 PR #332를 리뷰·머지한 뒤 성공한 `develop` 또는 `main` push CI의 exact
   commit artifact로 임시 운영 설정을 교체
4. Flyway·Importer와 Spring Boot 시작, 내부 readiness 확인
5. DNS 전파, TLS 발급, 외부 API·포트·문서 비활성·로그 개인정보 smoke
6. 재부팅 자동 복구와 실제 실패 알림 검증

PR head artifact는 staging 전용 계약이므로 운영 서버에 설치하지 않는다.
