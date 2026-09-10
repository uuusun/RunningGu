# 운영 EC2 부트스트랩 증거 — 2026-09-10

> 상태: **기반 자원·호스트 설치 완료, 애플리케이션 배포 전**. 운영 DNS, 외부 API 키,
> 정식 `develop`/`main` CI artifact가 준비되기 전에는 백엔드와 데이터베이스를 시작하지 않는다.

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
- 운영 SNS topic `runninggu-production-alerts`를 만들었다. 이메일 구독은 확인 메일 승인 대기다.
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

## 아직 완료하지 않은 항목

1. SNS 구독 확인 메일 승인과 시험 알림 실제 수신
2. 가비아 `api.runninggu.store` A record를 EIP로 연결
3. 운영 전용 KTO service key, Kakao app·REST key·app ID 발급과 SecureString 저장
4. 이 배포 변경 PR을 리뷰·머지한 뒤 성공한 `develop` 또는 `main` push CI의 exact commit
   artifact 설치
5. 운영 env 생성, PostgreSQL·pgBackRest·GraphHopper·Spring Boot systemd 시작
6. Flyway·Importer, 내부 readiness, DNS 전파, TLS 발급과 외부 API smoke
7. 최초 full backup·WAL archive·복구 목록·재부팅 자동 복구·실패 알림 검증

PR head artifact는 staging 전용 계약이므로 운영 서버에 설치하지 않는다.
