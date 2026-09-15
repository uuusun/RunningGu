# 스테이징 EC2 중지 확인 (2026-09-07)

사용자는 4GiB 검증 후 스테이징을 중지하기로 한 방침이 이행됐는지 확인을 요청했다.
[배포 실행서 §1](../aws-ec2-staging-runbook.md)의 “검증하지 않는 시간에는 EC2를 중지”
방침에 따라 AWS 서울 리전 콘솔에서 상태를 확인하고 기존 4GiB 인스턴스를 중지했다.

## 확인과 처리

| 대상 | 인스턴스 | 확인 전 | 처리 후 |
|---|---|---|---|
| 현재 4GiB 스테이징 | `i-07aa483968f4daddc`, `runninggu-staging-4g-2b`, `c7i-flex.large`, 서울 2b | Running, 상태 검사 3/3 정상 | Stopped |
| 이전 8GiB 스테이징 | `i-05457509f8383f45c`, `runninggu-staging`, `m7i-flex.large`, 서울 2a | Stopped | 기존 상태 유지 |

- 19:18 KST경 콘솔 조회에서 현재 4GiB 인스턴스가 실행 중임을 확인했다.
- [245건 보완 검증 기록](api-load-ec2-4g-20260907-kto-supplement.md)은 10:59:45 KST에
  최종 확인을 마쳤다. 마지막 절차는 가드 OFF·서비스 복귀·HTTPS 확인이며 EC2 중지 기록은 없었다.
- AWS 콘솔에서 해당 4GiB 인스턴스 하나만 선택하고 **Stop instance**를 실행했다.
  `Skip OS shutdown`은 선택하지 않았다. 삭제·강제 중지·사양 변경은 수행하지 않았다.
- 콘솔 상태 전환 사유: `User initiated (2026-09-07 10:23:03 GMT)`.
  즉 중지 요청 시각은 **2026-09-07 19:23:03 KST**다.
- 19:23 KST 후속 새로고침에서 두 인스턴스 모두 **Stopped**를 확인했다.
- 현재 인스턴스의 Auto Scaling Group은 없고, 종료 방지는 유지했다.

CloudShell 연결이 반복해서 끊겨 중지 직전의 서버 프로세스·시험 service·백업 job 목록은
재확인하지 못했다. 진행 중인 작업이 0개였다고 기록하지 않는다. 검증 완료 기록과 기존 중지
방침을 근거로 정상 OS 종료를 요청했으며, 최종 인스턴스 상태는 EC2 콘솔에서 직접 확인했다.

## 보존 자원과 비용

EC2를 중지해도 전체 AWS 비용이 0원이 되는 것은 아니다.

- 현재 root EBS: `vol-0c3906f0c1d2c5250`, 암호화 gp3 **30GiB**.
- 이전 root EBS: `vol-097d4410b92294bdc`, 암호화 gp3 **30GiB**.
- 두 볼륨 모두 원래 인스턴스에 연결된 채 보존됨을 Volumes 콘솔에서 확인했다.
- 현재 스테이징의 Elastic IP도 연결을 유지했다.
- EBS·Elastic IP 비용은 중지 후에도 발생한다. 기존 S3 백업·artifact, 스냅샷·KMS 등의
  저장·보유 비용도 별도다. 이번 작업에서 전체 청구액·크레딧·다른 리전 자원을 감사하지 않았다.

근거: [AWS EC2 중지/시작 동작과 비용](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/how-ec2-instance-stop-start-works.html),
[공인 IPv4 요금](https://aws.amazon.com/vpc/pricing/).

## 앱과 운영 배포에 미치는 영향

- `staging-api.runninggu.store`를 사용하는 테스트 APK의 온라인 기능은 서버를 다시 켤 때까지
  사용할 수 없다. 다음 서버 검증 시 기동 후 서비스·HTTPS 준비 상태를 확인한다.
- **4GiB 유지**는 사양 결정이며, 스테이징 24시간 기동 승인이나 프로덕션 출시 승인이 아니다.
- 공개 앱은 [기존 주소 결정](../release-preparation-2026-09-05.md#주소-결정)에 따라
  `https://api.runninggu.store/api/`를 사용하고 DB·JWT·SMTP·외부 API 시크릿을 분리한다.
- 운영 서버의 정확한 기동 날짜는 이번에 정하지 않았다. 상시 비용 확정과 운영 환경 구성·
  DNS/TLS·백업/복구 확인 후, 운영 주소의 최종 APK 검증과 스토어 심사 전에 준비해야 한다.

제품 계약·코드·사양 변경은 없으며 기존 파일의 사용자 변경을 보존했다.
