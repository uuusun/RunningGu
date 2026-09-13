# 운영 API 안정성·공개 개인정보처리방침 검증 — 2026-09-13

## 범위와 원칙

- 운영 인스턴스와 기존 Elastic IP를 그대로 사용했다. 새 EC2·로드 밸런서·유료 DNS 자원은 만들지 않았다.
- 준비 상태는 공개 계약인 `GET /api/contests?size=1`로 판단했다. 인증이 필요한 `GET /api`의
  `401 application/problem+json`은 정상 계약으로 유지했다.
- 조사와 증적에는 요청·응답 본문, 이메일 주소, 토큰, 이용자 좌표를 남기지 않았다.
- 운영 변경 전 nginx 설정을 백업했으며, 매 단계 `nginx -t`와 API 준비 응답을 확인했다.

## API 연결 거부 조사

2026-09-10부터 2026-09-13까지의 상태와 2026-09-13 현장 상태를 다음 경계로 나눠 확인했다.

| 경계 | 확인 결과 |
|---|---|
| EC2 | 실행 중, 시스템·인스턴스·연결 상태 검사 3/3 통과, 예약 이벤트 없음 |
| CloudWatch | `StatusCheckFailed`, `StatusCheckFailed_Instance`, `StatusCheckFailed_System` 최대값 모두 0 |
| Elastic IP·보안 그룹 | 운영 인스턴스 연결 유지, TCP 80·443 공개 허용 |
| 리스너 | nginx 80·443, backend 127.0.0.1:8080, GraphHopper 127.0.0.1:8989, PostgreSQL 127.0.0.1:5432 |
| 서비스 | nginx·backend·GraphHopper·Docker 모두 active, 조사 시 `NRestarts=0` |
| 메모리·디스크 | OOM 기록 0, swap 사용 0MiB, 가용 메모리 약 1.4GiB, 루트 디스크 사용률 38% |
| 방화벽 | UFW·fail2ban 비활성, INPUT reject/drop 카운터 0 |
| 애플리케이션 의존성 | backend loopback 준비 요청 200, GraphHopper 응답 확인, PostgreSQL 사용 가능 |

동일 시각 서울 AWS CloudShell과 EC2에서는 운영 준비 요청이 200이었지만 작업 PC의 제한된 실행
경로에서는 TCP 연결 거부가 재현됐다. 따라서 그 시각의 연결 거부는 EC2·nginx·backend 중단이
아니라 특정 클라이언트 네트워크 또는 실행 격리 경로 문제로 판단한다. 과거 간헐 현상의 정확한
원인은 당시의 네트워크 패킷·서비스 상태 증적이 없어 단정하지 않는다.

backend를 통제된 방식으로 한 차례 재시작했다. 2026-09-13 11:01 KST경 시작해 2초 간격
7번째 검사, 약 12초 뒤 준비 응답 200으로 복구됐다. 새 systemd invocation을 확인했고 nginx와
GraphHopper는 계속 active였다. 외부 준비 요청도 200이었다. 기존 `Restart=on-failure`, 5초
재시작 지연, 메모리 상한을 유지했으며 근거 없는 서비스·EC2 재부팅이나 정책 변경은 하지 않았다.

## 공개 개인정보처리방침 배포

| 항목 | 적용·검증 결과 |
|---|---|
| 공개 문서 | 웹 버전 1.0, 시행일 2026-09-13 |
| DNS | 루트 `A 3.37.39.89`, TTL 600초. 가비아가 제공한 최소 선택값을 사용했고 AAAA는 만들지 않음 |
| DNS 전파 | 가비아 권한 네임서버 3곳과 Cloudflare·Google 공개 리졸버에서 같은 A 응답, AAAA 없음 |
| TLS | 기존 `api.runninggu.store` lineage를 `api.runninggu.store`·`runninggu.store` 두 SAN으로 확장 |
| 인증서 만료 | 2026-12-12, certbot timer active·enabled |
| 자동 갱신 | `certbot renew --dry-run --run-deploy-hooks` 성공 |
| 루트 동작 | HTTP `/`는 HTTPS로 301, HTTPS `/`는 `/privacy/`로 302 |
| 처리방침 | `https://runninggu.store/privacy/` 인증 없이 200 `text/html` |
| 기타 경로 | `/privacy/missing`, `/missing` 모두 404 |
| API 계약 | 준비 엔드포인트 200, `/api`는 401 `application/problem+json` 유지 |
| 문안 | 초안·`noindex`·`[확인 필요]` 없음, Google 국외 처리 국가와 최신 로그 보호 문구 확인 |
| 보안 헤더 | CSP, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff` 확인 |
| 로그 보호 | 무해한 경로·질의 표식을 보낸 뒤 nginx 파일 로그에 표식이 남지 않음을 확인 |

배포 번들은 기존 운영 백업 버킷의 배포 경로에 KMS 암호화로 두었고, EC2에서 SHA-256
`0dd36a59eca795edb0bfe8232316a5a68542d10c918d89a9c4bfec0ce8c8fc91` 일치를 확인한 뒤
설치했다.

## 반복 안정성 검사

최종 nginx reload와 backend 재시작 이후의 결과를 검사한다. 응답 본문은 저장하지 않고 시각과
HTTP 상태만 집계한다.

| 외부 네트워크 위치 | 검사 시각(KST) | 간격·횟수 | 준비 API | 처리방침 |
|---|---|---|---|---|
| AWS CloudShell 서울 | 11:16:28~11:21:20 | 10초 간격 30회 | 30/30 HTTP 200 | 30/30 HTTP 200 |
| AWS CloudShell 도쿄 | 11:17:33~11:22:31 | 10초 간격 30회 | 30/30 HTTP 200 | 30/30 HTTP 200 |

두 검사는 backend 통제 재시작과 최종 nginx reload 뒤에 수행했다. 따라서 각 위치의 첫 10회는
재시작 뒤 10초 간격 10회 기준도 함께 충족한다. 작업 PC의 제한된 명령 실행 경로에서는 세 차례
연결 결과가 `000`이었으나, 같은 시각 서로 다른 두 AWS 리전 외부망과 공개 브라우저에서 정상
응답을 확인했다. 이 제한 경로의 결과는 운영 합격 표본에 포함하지 않았다.

## 롤백 지점

- 최종 적용 직전 nginx 설정: `/var/backups/runninggu-public-20260913T1116Z/runninggu-production.before`
- 초기 root bootstrap 적용 전 nginx 설정: `/var/backups/runninggu-public-20260913T0200Z/runninggu-production.before`
- 정적 파일 또는 최종 가상 호스트 문제 시 직전 설정을 복원하고 root bootstrap symlink를 다시
  연결한 뒤 `nginx -t` 통과 후 reload한다.
- 인증서 lineage 확장은 API 인증서 경로를 유지하므로 API 가상 호스트 경로를 바꾸지 않았다.

## 저장소 검증

- `backend/deploy/nginx/test_log_privacy.py -v`: 4개 테스트 통과
- `backend/deploy/ci/test_production_deploy_contract.py -v`: 9개 테스트 통과
- 공개 페이지 브라우저 렌더링: 제목·목차 13개·버전·시행일·국외 이전 문구 확인

## 비용·중단

- 새 유료 자원 없음. 기존 EC2·Elastic IP·가비아 DNS와 기존 S3 백업 경로를 사용했다.
- 인증서는 Let's Encrypt로 추가 비용이 없다. 정적 HTML과 작은 배포 번들의 저장 비용은 사실상
  미미하다.
- nginx는 reload로 적용해 관측된 API 중단이 없었다. backend 통제 재시작의 준비 복구에는 약
  12초가 걸렸지만 외부 단일 관측에서는 계속 200이었다.
