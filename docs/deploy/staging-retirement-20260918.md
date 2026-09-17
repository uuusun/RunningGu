# 스테이징 폐기와 운영 단일 검증 전환 — 2026-09-18

## 결정

스테이징 EC2와 `staging-api.runninggu.store` DNS는 폐기했다. 현재 원격 실행 환경은 운영
`https://api.runninggu.store/api/` 하나다(SPEC 결정-71).

- 머지 전: 로컬 Docker·Testcontainers, 단위/통합 테스트, CI
- 머지 후: push CI artifact의 exact commit을 운영에 배포
- 실서버·서명 APK: 운영에서 저부하 스모크와 변경 범위 E2E
- 운영 금지: 혼합 부하, 장애 주입, 의도적 OOM, 대량 가입·메일, 파괴적 DB·복구 시험
- 쓰기 검증: 전용 QA 계정과 식별 가능한 가역 데이터만 사용

과거 스테이징 문서와 증거는 당시 판단의 근거이므로 삭제하거나 운영 결과로 고쳐 쓰지 않는다.
실행 가능한 설정과 과거 기록을 분리하고, 과거 문서 첫머리에 폐기 상태를 표시한다.

## 저장소 전수조사 판정

2026-09-18 `git grep -l -i -E 'staging|스테이징'` 기준으로 추적 파일 127개에 참조가 있었다.
문자열 개수로 삭제 범위를 정하지 않고 다음처럼 분류한다.

| 분류 | 처리 |
|---|---|
| SPEC·현재 개발/출시 지침·운영 실행서 | 운영 단일 정책으로 개정 |
| PR 전용 staging 배포 artifact | 생성 job과 전용 packaging 코드 제거 |
| staging nginx·env·GraphHopper release descriptor | 활성 배포 경로에서 제거 |
| staging 전용 부하 도구·upstream guard | 운영에서 실행 금지. 과거 용량 검증 재현 코드로 분류하고 후속 제거는 담당 코드 변경과 분리 |
| `docs/deploy/evidence/**`, 과거 APK 검증·WBS | 이력 보존, 현재 실행 금지 표식 추가 |
| `Python → 스테이징 테이블 → 승격` | DB 적재 패턴의 일반명이라 서버 환경과 무관. 유지 |
| `.staging-<artifact-id>` | 원자적 설치 중간 디렉터리의 일반명. 환경과 무관하지만 혼동 방지를 위해 `installing`으로 변경 |

## 운영이 아직 사용하는 자원 주의

이름에 staging이 포함된 기존 artifact bucket을 운영 EC2가 `graphhopper/production/*` 경로로
사용하고 있다. 이름만 보고 bucket을 변경하거나 삭제하지 않는다.

안전한 변경은 새 운영 전용 bucket 생성 → 객체 checksum 대조 복사 → IAM·env 전환 → 운영
GraphHopper 재검증 → 관찰 뒤 기존 bucket 정리 순서다. 이 마이그레이션은 비용·IAM·운영 재기동이
수반되므로 이슈 #367에서 팀 합의 후 별도 수행한다.

## 2026-09-18 읽기 전용 조사 결과

AWS 서울 리전과 GitHub 저장소를 조회했다. 비밀값과 S3 객체 본문은 읽지 않았고 자원을 변경하거나
삭제하지 않았다. 공개 저장소에는 AWS 계정·자원 ID를 기록하지 않으며, 삭제 실행 직전에 콘솔과
CLI에서 정확한 대상을 다시 조회한다.

| 자원 | 확인 결과 | 운영 의존 | 권장 조치 |
|---|---|---|---|
| EC2·EBS·EIP | staging 실행 인스턴스 없음. 확인된 연결 자원은 운영 인스턴스 소속 | 있음 | 유지 |
| AMI·snapshot | staging AMI 1개와 관련 snapshot 2개가 남음 | 없음 | 보존 필요 여부 합의 후 정리 |
| 보안 그룹 | staging 보안 그룹 1개, 연결 ENI 없음 | 없음 | 별도 승인 후 삭제 |
| IAM | staging role·instance profile·policy가 남았고 실행 인스턴스 연결 없음 | 없음 | 정책 분리 후 profile·role 삭제 |
| 공유 암호화 key | staging·production runtime policy가 같은 key를 참조 | 있음 | 삭제 금지 |
| GraphHopper artifact bucket | 운영 role이 production prefix를 명시적으로 읽음 | **있음** | 운영 전용 bucket 이관 전까지 유지 |
| staging GraphHopper prefix | 공유 bucket에 staging 전용 객체가 남음 | 없음 | staging IAM 제거 후 prefix만 별도 승인 삭제 가능 |
| staging 백업 bucket | 과거 pgBackRest 객체가 남았고 만료 lifecycle 없음 | 없음 | 백업 보존 기간 합의 후 정리 |
| 알림 | staging topic과 email 구독이 남음 | 없음 | 수신자 공지 후 정리 |
| DNS·GitHub | staging DNS가 해석되지 않으며 staging environment·secret·variable·유효 artifact 없음 | 없음 | 추가 조치 없음 |
| 기타 AWS | staging 관련 SSM parameter, log group, alarm, stack, ECR, secret, LB, RDS, NAT, Lambda 없음 | 없음 | 추가 조치 없음 |

가비아 DNS와 Resend·KTO·Kakao 콘솔의 staging 등록값은 해당 외부 콘솔 권한으로 별도 교차
확인한다. AWS 잔여 자원 삭제와 artifact bucket 이관은 이슈 #367에서 결정한다.

## 운영 QA 결과 기록 형식

```text
commit/artifact  배포한 Git commit, workflow run, checksum
기기             APK 버전·서명·AVD/실기기·ABI
읽기 스모크      호출 경로·status·횟수·실행 시각
쓰기 E2E         QA 계정 구분값·생성 데이터 종류·정상 삭제 가능 여부
서비스 상태      backend·GraphHopper·DB·nginx 상태와 비정상 재시작 수
롤백 준비        직전 artifact와 backup/WAL 상태
미검증           운영에서 금지돼 수행하지 않은 부하·장애·파괴 시험
```
