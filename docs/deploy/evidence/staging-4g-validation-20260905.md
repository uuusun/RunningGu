# 4GiB 축소 검증 — 2026-09-05

> 운영책임자가 8GiB 결과를 확인하고 4GiB 시험을 순서대로 진행하도록 승인했다.
> 복구 기준 보존과 후보 상한의 장애 격리는 통과했다. 기존 EC2 유형 변경은 AWS Free Plan의
> `FreeTierRestrictionError`로 거부되어 **4GiB 정상 부하 시험은 시작하지 못했다.**
> 당시 기존 `m7i-flex.large` 8GiB와 원래 설정·readiness·외부 HTTPS로 복구했다. 그 시도는 4GiB 성능 불합격 판정도 아니다.
> 이후 사용자가 **`ap-northeast-2b`에 `c7i-flex.large`를 새로 만들어 이전·검증하는 방식**을 승인했다.
> 새 인스턴스 `i-07aa483968f4daddc` 생성과 EBS 초기화 후 4GiB cold boot·HTTPS 이전은 성공했다.
> 전체 라우팅 회귀는 통과했지만 1회차 직접 부하에서 warm-up 이후 swap 사용량이 40KiB 증가해 **당시 계약상 불합격·중단**했다.
> 앱 API 부하와 2·3회차는 미실행이다. 최신 DB를 가진 새 서버를 **8GiB와 원래 JVM 설정으로 복구했고 HTTPS 확인도 통과**했다.
> 운영책임자가 `capacity-v2` 기준으로 앱 API 시험부터 재개하는 계획을 승인했다.
> 과거 중단 결과는 미완료로 유지하며, 승인 후 재시험 준비 상태는 마지막 절에 기록한다.

## 최초 시도의 고정 기준과 복구 대상

- 기존 인스턴스 `i-05457509f8383f45c`, `ap-northeast-2a`를 재사용한다. EBS root와 기존 Elastic IP 연결을 확인했다.
- 실제 배포는 `673a2f796052f4553113d5cd25608fb3821222ac`의 정식 develop artifact다. 문서 PR #294 머지로 서버 JAR를 교체하지 않는다.
- graph·server image·라우팅 프로파일·요청 목록·비율·도착률·동시성은 유지한다.
- 복구 기준: GraphHopper Xms1g/Xmx4g, reservation3g, memory5g/memory+swap5g; backend Xms256m/Xmx1g, MemoryHigh1G/MemoryMax1536M.
- PostgreSQL 설정·데이터·기존 미커밋 Dockerfile과 호스트 swap 4GiB는 보존한다. cache 강제 비우기·swapoff·요청량 완화는 하지 않는다.
- root 전용 복구 경로: `/opt/runninggu-validation/4g-673a2f7-20260905T0715Z/`. 비밀 환경파일은 0600으로 EC2에만 보관한다.
- 정상 시험의 가드는 매번 새 run ID로 preflight하고, 종료·실패 시 비활성화한 뒤 backend를 재기동한다.

## 인스턴스 후보와 비교 한계

2026-09-05 AWS EC2 API와 Price List API에서 직접 조회했다. Linux·공유 tenancy·온디맨드 단가이며
가격 적용일은 `2026-09-01T00:00:00Z`다. EBS·공인 IPv4·트래픽·세금·할인은 포함하지 않는다.

| 항목 | 기존 | 4GiB 후보 |
|---|---|---|
| 유형 | `m7i-flex.large` | `c7i.large` |
| vCPU / RAM | 2 / 8GiB | 2 / 4GiB |
| CPU | Intel, 지속 클럭 3.2GHz | Intel, 지속 클럭 3.2GHz |
| CPU 공급 방식 | Flex 기준 성능 40%·burst | 일반 C7i |
| 네트워크 상한 | 최대 12.5Gbps | 최대 12.5Gbps |
| EBS 기준 / 최대 대역폭 | 312 / 10,000Mbps | 650 / 10,000Mbps |
| 시간당 USD | 0.11771 | 0.10080 |
| 기존 AZ 제공 | 확인 | 확인 |

가장 가까운 Flex 후보 `c7i-flex.large`는 2 vCPU·4GiB, 시간당 USD 0.09576이지만
현재 계정의 `ap-northeast-2a` offering 조회에서 제공되지 않았다. 가용 영역 이동이나 새 리소스 생성 대신
같은 AZ에서 제공되는 `c7i.large`를 선택했다. CPU 공급 방식과 EBS 기준 성능이 달라 **RAM만 독립적으로 바꾼 실험은 아니다.**
온디맨드 컴퓨팅 단가는 약 14.4% 낮으며, 메모리가 절반이라고 비용도 절반이라고 계산하지 않는다.

공식 사양: [M7i/M7i-flex](https://aws.amazon.com/ec2/instance-types/m7i/),
[C7i/C7i-flex](https://aws.amazon.com/ec2/instance-types/c7i/),
[리전별 제공 유형](https://docs.aws.amazon.com/ec2/latest/instancetypes/ec2-instance-regions.html).
리전 제공과 해당 계정의 AZ 제공은 구분한다.

## 메모리 후보 — 격리 통과·4GiB 정상 부하 중단

| 대상 | 후보 |
|---|---|
| GraphHopper | Xms512m/Xmx2g, reservation2g, memory2560m, memory+swap2560m |
| backend | Xms256m/Xmx512m, MemoryHigh640M, MemoryMax768M |
| PostgreSQL·호스트 swap | 기존 설정 유지 |

4GiB에서 두 JVM·PostgreSQL·OS가 함께 실행될 여유를 확보하는 시험 후보다. 상한의 합이나
앱 warm-cache 사용량만으로 합격을 주장하지 않는다. 기존 GraphHopper 직접 요청의 8GiB
검증에서는 cgroup peak 약 2.80GiB가 관측됐으므로 전체 회귀·직접 부하에서도 검증해야 한다.
GraphHopper는 candidate hard limit과 동일한 별도 격리 시험을 통과한 뒤 적용한다.

## 실행 순서

1. 8GiB 실제 배포·artifact·컨테이너·설정 보존과 변경 전 full backup·WAL 성공 확인.
2. 후보 hard limit으로 격리된 GraphHopper OOM·재시작 3회 제한·PG 생존·백업/WAL·원래 서비스 복구 확인.
3. 후보 메모리 설정 적용 후 기존 EC2를 정지·`c7i.large` 변경·시작. 동일 artifact 검증 및 양쪽 readiness 120초 조건 확인.
4. 같은 83개 합격 셀 기준선의 전체 라우팅 회귀, 30분 직접 부하(60건/분·동시성2·timeout5초), full backup 1회·WAL 3회·5초 자원·GC·개인정보 로그 검사를 수행.
5. 앱 API는 승인된 fixture·schedule로 warm-up 300 + 본 시험 1,800건, 60건/분·최대 동시 진행4, 40분 자원 수집과 backup/WAL 동시 실행.
6. GraphHopper 종료·호스트 재부팅 복구, backend 중지 상태의 Importer 및 재시작을 포함한 기존 계약의 전체 시나리오를 3회 연속 검증.
7. 각 단계 실패 시 다음 부하를 시작하지 않고 원인·미실행 범위를 기록한 뒤 원래 8GiB 사양·설정·readiness·HTTPS로 복구.

앱 fixture canonical SHA-256: `6811484a70d406e555c9bdce8273744ef5be597795a73186c9ed9ea42a818ef1`.
schedule SHA-256: `88bd5580cc00ca57796b6ba98cbe4e54b727e02924f773561e7b4a638abd097d`.
합격 기준은 [앱 부하 계획](../api-load-test-plan.md)과 [artifact 계약 §9](../graphhopper-artifact-contract.md#9-사전-합격-기준)를 따른다.
직접 부하의 기존 GC 감사 기준을 결과에 맞춰 완화하지 않는다. 정상 구간과 의도적 OOM·시작 구간을 구분해 기록한다.

## 변경 전 보존·장애 격리 결과

- 변경 전 full backup·WAL 성공, 실제 backend source SHA·가드 비활성·8GiB 구성 확인.
  SSM `87048929-cc07-4591-95ad-d5799265401c`: Success / exit 0.
- 격리 시험: `2026-09-05T07:32:59Z`~`07:33:58Z`, SSM `2ce2f1e9-de97-4d6c-b793-697ace2b2fc6`: Success / exit 0.
- 실제 memory / memory+swap `2684354560` bytes, reservation `2147483648` bytes, `memory.swap.max=0`.
- 별도 graph 복사본과 Compose project에서 정상 기동·명시 stop 후 재시작 0을 확인했다.
- 고정 공개 fixture 200·의도적 400의 로그 개인정보 검사를 2회 실행: 모두 passed, leakedLogLines 0.
- 의도적 heap 3GiB·AlwaysPreTouch로 container OOMKilled / exit 137 확인.
  재시작 3회 후 failed 상태가 유지됐고 GraphHopper 전용 알림 service가 성공했다. 이메일 수신 확인을 뜻하지 않는다.
- PostgreSQL container ID·시작 시각, backend invocation·재시작 수가 유지됐다. pgBackRest check·WAL 성공.
- 격리 container 종료 후 원래 unit을 byte 단위로 복원하고 기존 artifact의 GraphHopper·backend readiness 성공.
  `productionRestored=true`. 실제 graph·배포 JAR는 교체하지 않았다.
- 후보 설정 적용 SSM `84fade63-5e98-4d33-9d02-fdd962411c73`: Success / exit 0.
  두 JVM을 정지하고 후보 env·backend memory drop-in과 부팅 readiness 관측기를 적용했다.

## 유형 변경 차단과 원복

기존 EC2 정지 완료를 확인한 뒤 EC2 `ModifyInstanceAttribute`로 `m7i-flex.large` → `c7i.large`를
요청했다. AWS가 `FreeTierRestrictionError`와 함께 현재 Free Plan에서 이 작업을 사용할 수 없고
계정 plan 업그레이드가 필요하다고 응답했다. instance type 변경은 적용되지 않았다.
계정의 과금 plan은 변경하지 않았고 새 AWS 리소스도 만들지 않았다.

추가로 AWS 공식 [EC2 Free Tier 대상 기종](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-launch-parameters.html)을
확인했다. 현행 대상에는 `m7i-flex.large`와 4GiB `c7i-flex.large`가 포함되지만 이번 후보인
`c7i.large`는 포함되지 않는다. Free Plan에서 모든 4GiB 기종이나 모든 유형 변경이 금지됐다고
이 응답 하나로 일반화하지 않는다. 실제 확인한 것은 **이번 `c7i.large` 변경 요청의 거부**다.
무료 대상 `c7i-flex.large`는 이번 계정의 기존 AZ offering 조회에 없었으므로 선택하지 않았다.
이후 `DescribeInstanceTypeOfferings`를 서울 리전 전체 AZ에 대해 pagination 포함 재조회했다.
현재 계정의 AZ 이름 기준 `c7i-flex.large` 제공 영역은 `ap-northeast-2b`·`ap-northeast-2d`이고,
`c7i.large`·`m7i-flex.large`는 `2a`·`2b`·`2c`·`2d`에 제공된다.
동시에 `DescribeInstanceTypes`에서 두 C7i 후보의 RAM은 모두 4096MiB,
`FreeTierEligible`은 `c7i-flex.large=true`, `c7i.large=false`임을 확인했다.
위 offering은 위치별 기종 제공 정보이며, 무료 계정만 `2a`에서 Flex 기종을 차단한다는 뜻이 아니다.
후보 선정 시 AZ 제공·가격뿐 아니라 Free Tier 대상 여부도 인스턴스 정지 전에 확인해야 한다.

기존 `m7i-flex.large`를 다시 시작했다. 원복 명령이 실행되기 전에는 **8GiB 호스트에 임시 후보 JVM
설정**으로 부팅됐으므로 그 구간은 4GiB cold boot·부하 증거로 사용하지 않는다.

- 원복 SSM `d2abb09b-ff76-484a-b984-b62838d406b8`: Success / exit 0.
- 원복 구간: `2026-09-05T07:44:15Z`~`07:44:32Z`.
- 보존한 application.env·compose.env·backend memory drop-in을 byte 단위로 복원했다.
  원래 GraphHopper unit과 PostgreSQL Dockerfile도 보존본과 일치한다.
- 실제 프로세스: backend Xmx1g, MemoryHigh 1GiB / MemoryMax 1.5GiB;
  GraphHopper Xmx4g, reservation 3GiB, memory 및 memory+swap 5GiB, `memory.swap.max=0`.
- verify oneshot 766ms, verify 시작부터 양쪽 readiness 16,408ms. 두 service active / success / NRestarts 0.
- 실제 backend 프로세스의 가드 비활성·빈 run ID를 확인했다.
- 실제 current release는 `673a2f796052f4553113d5cd25608fb3821222ac`, graph artifact·server image는 동일하다.
- 원복 시 MemTotal 7,962,904KiB, MemAvailable 6,182,264KiB, swap 사용 0KiB.
  이 한 표본은 정상 부하의 모든 표본 20% 조건을 대신하지 않는다.
- 외부 PC의 `python scripts/api/prepare_api_load.py --year-month 2026-09 --probe-public`:
  exit 0, 공개 HTTPS 7항목 모두 통과. 대회 목록·상세·마감 임박·월간 건수·코스 지역·목록은 200,
  미인증 `/api/me`는 예상한 401. TLS 검증을 유지했으며 `loadExecuted=false`다.
- 최종 SSM `909af48c-61d1-4d75-8ef0-1617e5e6f1d3`: Success / exit 0,
  `2026-09-05T07:47:01Z`에 PostgreSQL 생존·동일 image·pgBackRest check·WAL 성공 확인.
  backend·GraphHopper·Nginx·backup timer·WAL timer·certbot timer 모두 active다.
- EC2 API에서 기존 ID의 `m7i-flex.large` / running, 동일 AZ·Elastic IP allocation을 재확인했다.
  임시 부팅 관측 unit은 비활성화·제거했고 증거 파일은 root 전용 경로에 보존했다.
- 중간 8GiB 부팅의 양쪽 readiness는 20,278ms였지만, 당시 후보 JVM 설정이었으므로
  4GiB 결과나 원래 설정의 부팅 성능으로 전용하지 않는다.

## 기존 인스턴스 변경 시도의 판정과 당시 전제

| 항목 | 판정 |
|---|---|
| 8GiB 복구 기준·full backup·WAL 보존 | 완료 |
| 후보 hard limit 장애 격리 | 통과 |
| 기존 EC2의 4GiB 유형 변경 | AWS 계정 plan 제한으로 차단 |
| 4GiB 전체 라우팅 회귀·30분 직접 부하 | 미실행 |
| 4GiB 앱 API 2,100건·자원·가드·부하 중 백업/WAL | 미실행 |
| 4GiB 전체 계약 3회 연속 | 미실행 |
| 최종 운영 사양 | 기존 8GiB·원래 설정으로 복구 |

당시 `c7i.large` 후보로 다시 진행하려면 AWS가 안내한 **Paid account plan 전환을 사용자가 결정·완료하는 것**이 전제였다.
모든 4GiB 시험에 유료 전환이 필수라는 뜻은 아니다. 무료 대상 `c7i-flex.large`를 검토하려면
현재 AZ 제공 여부를 다시 확인해야 하며, 다른 AZ·새 인스턴스로 이전하는 것은 기존 리소스 재사용 범위 밖이다.
허용되는 후보와 실행 전제가 정해지면 cold boot부터 정상 시험을 시작해야 한다.
8GiB에서의 임시 후보 기동이나 격리 성공을 4GiB 정상 시험의 1회 통과로 세지 않는다.

AWS 공식 [계정 plan 안내](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html)에 따르면
남은 Free Tier credit은 이후 청구에 적용되지만 credit 초과·미적용 사용에는 종량 요금이 발생할 수 있다.
따라서 이번 검증 승인만으로 계정의 과금 plan까지 변경하지 않았다.

## 추가 승인: 2b의 무료 대상 4GiB 서버로 이전

사용자가 `ap-northeast-2b`의 `c7i-flex.large` 신규 서버 생성과 기존 데이터·설정 이전을 승인했다.
이 이전에 필요한 인스턴스·EBS 및 복사 수단의 생성은 앞선 신규 리소스 금지의 명시적 예외다.
기존 8GiB 서버·디스크를 복구용으로 보존하고 IAM 권한·공개 포트·과금 plan은 확대하지 않는다.

새 서버의 실제 4GiB·동일 artifact·후보 상한·readiness를 확인한 후 고정된 전체 시험을 진행한다.
두 PostgreSQL 복사본이 같은 백업 저장소에 동시에 WAL을 쓰지 않도록 이전 중 원본과 복사본의
실행 시점을 분리한다. 외부 트래픽을 옮기기 전까지 기존 EIP의 복구 대상을 기록한다.
실패 시 신규 시험을 중단하고 기존 8GiB를 복구하며, 합격 전 기존 서버를 삭제하지 않는다.

### 생성 전 확인·비용·원본 고정

- VPC `vpc-068918dc475f40175`의 기존 2b subnet `subnet-0f36b63f8f8959070`과
  보안 그룹 `sg-01d8a378bc257acaf`를 재사용한다. inbound 80·443만 공개, 기존 IGW 경로 확인.
- 기존 instance profile `runninggu-staging-ec2`, IMDSv2 필수·hop limit 2를 유지한다.
- 새 root EBS는 기존과 같은 암호화 gp3 30GiB·3000 IOPS·125MiB/s를 사용한다.
- EC2 `CreateImage`와 대상 subnet의 `RunInstances` 사전 요청은 모두 `DryRunOperation`이었다.
  이는 실제 생성 성공이나 가용 용량 보장을 뜻하지 않는다.
- 생성 전 Price List API: `c7i-flex.large` USD 0.09576/h, gp3 USD 0.0912/GB-month,
  snapshot USD 0.05/GB-month. 6시간 검증을 가정한 신규 compute는 USD 0.57456이며,
  추가 디스크는 월 환산 USD 2.736, 전체 30GiB snapshot 보관을 가정한 상한은 월 USD 1.50이다.
  실제 snapshot 과금은 저장된 데이터 기준이며 IPv4·기존 보관 리소스·S3·KMS·세금은 별도다.
- SSM `da242cf3-117c-4510-b4ae-1e6c69840064`: Success / exit 0,
  `2026-09-05T08:23:28Z`~`08:24:17Z`. backend 중지 후 최종 full backup·WAL 성공,
  PostgreSQL exit 0 정상 종료, 가드 비활성 확인. 이후 원본 EC2를 정지했다.
- 복사본의 서비스 자동 실행을 막기 위해 `migration-hold` 조건을 추가한 상태로 이미지를 만든다.
  후보 설정을 검증하기 전에는 새 서버에서 Docker·DB·backend·GraphHopper·Nginx·관련 timer를 실행하지 않는다.
- 이전용 private AMI `ami-072282e14c5bd55d6` 생성 요청 성공. 실제 준비 완료와 새 서버 생성은 후속 확인한다.

### 새 서버 생성과 첫 기동

- AMI `ami-072282e14c5bd55d6` available, 암호화 snapshot `snap-00f3ac5a917a92be0` completed / 100% 확인.
- `2026-09-05T08:43:53Z`에 `i-07aa483968f4daddc` 생성 성공. 실제 `c7i-flex.large`,
  `ap-northeast-2b`, running / SSM Online. Free Plan은 변경하지 않았다.
- 새 root `vol-0c3906f0c1d2c5250`: 암호화 gp3 30GiB. 기존 IAM profile·보안 그룹·IMDSv2 필수 설정 유지.
- 두 Flex 기종의 EC2 API EBS 사양은 동일했다: 기준 312Mbps / 2,500 IOPS, 최대 10,000Mbps / 40,000 IOPS.
  vCPU는 모두 2개이며 RAM은 각각 8,192MiB·4,096MiB다. AZ·새 디스크로의 이전 조건은 비교 한계에 남긴다.
- 첫 기동 SSM `499ba724-9ef1-4010-b259-1032aa46e067`:
  `2026-09-05T08:45:36Z`~`08:47:00Z`, `passed=false`.
  release 체크섬·원본 고정·후보 설정 적용 뒤 `runninggu-graphhopper-verify.service`가 60초 제한으로 timeout됐다.
  GraphHopper·backend JVM은 시작하지 않았다. PostgreSQL만 정상 기동했으며 container OOM은 없었다.
  진단 SSM `69eeae5e-ecb0-40d2-8052-f8e68b479614`에서 verify Result=timeout / ExecMainStatus=15 확인.
- 이 구간에 EIP는 정지된 원본에 연결돼 있고 HTTPS 서비스는 이전 중 중단 상태다. 새 서버의 기동 성공이나
  4GiB 합격으로 기록하지 않는다. 원본 PostgreSQL과 새 PostgreSQL을 동시에 실행하지 않는다.

### EBS 복원 초기화 후 재측정 준비

스냅샷에서 만든 EBS의 첫 블록 읽기 지연이 원인일 가능성을 확인하기 위해
[AWS 권장 초기화 절차](https://docs.aws.amazon.com/ebs/latest/userguide/initalize-volume.html)를 적용한다.
초기화 후 같은 설정의 재부팅은 아래와 같이 통과했다. 최초 timeout은 이전 초기화 단계의 실패로 보존한다.

- SSM `d31d3660-3245-40c3-b738-383b5fabc1a0`: root 장치의 실제 EBS serial·30GiB 크기를 먼저 검증한 뒤
  `dd`의 입력을 해당 장치, 출력을 `/dev/null`로 고정해 `iflag=direct` 읽기 전용 초기화를 실행했다.
- 이 작업은 graph를 수정하거나 page cache를 인위적으로 데우는 절차가 아니다. 완료 뒤 호스트를 재부팅하고
  기존 verify·주 service 시작 제한 및 양쪽 readiness 120초 기준을 그대로 측정한다.
- 이미 새 PostgreSQL이 실행됐으므로 실패 시 정지된 옛 DB를 그대로 공개해 되돌리지 않는다.
  우선 새 서버의 현재 디스크·DB를 유지한 채 검증된 `m7i-flex.large` 8GiB와 원래 JVM 설정으로 복구한다.
  해당 기종의 FreeTierEligible=true 및 유형 변경 사전 요청 `DryRunOperation`을 확인했으나 실제 변경 성공 보장은 아니다.
  원래 8GiB 서버와 디스크는 별도 복구 원본으로 보존한다.

### 초기화 완료·4GiB cold boot·HTTPS 이전 결과

- 순차 읽기는 진행 시간을 줄이기 위해 종료하고 동일 장치를 32개 비중첩 구간으로 나눠 직접 읽었다.
  이는 부하 요청 목록·요청률 변경이 아닌 새 EBS의 이전 초기화다. 출력 장치는 모두 `/dev/null`이다.
- SSM `4d85b2db-d144-4272-a15e-1b9223621f8e`: Success / exit 0.
  `2026-09-05T08:57:55Z`부터 267,119ms, 전체 30GiB 직접 읽기 32개 작업 모두 exit 0.
- 호스트 재부팅 후 SSM `67bf81e6-e5c0-4e6a-828c-e3659a0f50ce`: Success / exit 0.
  boot ID `86123b28-d3eb-4f7f-a774-85c9efdce525`, 관측 `09:03:36Z`~`09:03:57Z`.
  GraphHopper activation부터 양쪽 readiness **21,749ms**로 120초 조건을 통과했다.
- 실제 4GiB: MemTotal 3,903,764KiB. 후속 표본의 MemAvailable 2,179,332KiB (**55.826%**), swap 사용 0KiB.
  이 단일 표본을 30분·40분 전체 자원 합격으로 대신하지 않는다.
- 실제 JVM·cgroup의 후보 heap·hard limit·swap 금지, backend guard=false / 빈 run ID 확인.
  동일 `673a2f7` release, graph 상대 symlink, GraphHopper·PostgreSQL image를 확인했다.
  kernel OOM 0, service 비정상 재시작 0, pgBackRest check 성공.
- 기존 EIP allocation `eipalloc-013dff37668146427`을 새 `i-07aa483968f4daddc`로 연결했다.
  association `eipassoc-0d535235c35f81c96`, 동일 주소를 유지했으므로 DNS 변경·추가 EIP 할당은 없다.
- 외부 PC에서 `prepare_api_load.py --year-month 2026-09 --probe-public`: exit 0, 공개 HTTPS 7항목 통과.
  공개 조회 6개 200, 미인증 `/api/me` 401. `loadExecuted=false`이며 TLS 검증을 유지했다.
  첫 실행의 transport 오류는 로컬 제한 환경의 차단 proxy(`127.0.0.1:9`) 때문이었다.
  승인된 네트워크 실행에서 통과했으므로 서버 HTTP 실패나 부하 시험 요청 실패로 집계하지 않는다.
- 이후 고정 `caps/all`, seed 16개 전체 회귀를 SSM `1a90854e-f07f-4689-a149-6e3cbfaab077`로 시작했다.
  GraphHopper 직접 30분 부하와 앱 API 부하는 이 회귀 결과를 확인한 뒤 진행한다.

### 결과 저장 권한 수정과 1회차 시작

- 첫 회귀 명령은 35초 뒤 `PermissionError`로 exit 1이었다. `routing.json` 저장 시점의 오류로,
  SSM의 umask 때문에 생성 요청 0770이 실제 **0750**이 되어 실행 계정이 폴더에 쓸 수 없었다.
- 진단 `b4a28986-5957-4609-897d-0bbfe6e94dc4`: 요청 read timeout 0, container OOM·restart 0,
  JVM OutOfMemoryError 0, Full GC 완료 0, Docker GC 로그 1,927줄.
  회귀 JSON이 없으므로 이 실행을 회귀 합격이나 4GiB 용량 실패로 판정하지 않았다.
- 권한 수정 `459e28c5-39a8-4df3-8934-3ed92f4b7143`: Success / exit 0.
  증거 폴더를 root:runninggu **0770**으로 명시하고 실행 계정의 실제 쓰기 권한을 확인했다.
  실패한 파일들은 같은 폴더의 `setup-failed-1/`에 보존했다. 비밀 환경파일의 권한은 변경하지 않았다.
- 재부팅부터 다시 실행한 `78ab9f51-ab41-4cd6-9852-00ce17d0b209`: Success / exit 0.
  boot `14c688c9-8923-45ea-b0a5-d32e0a1b9c90`, activation부터 양쪽 readiness **20,852ms**.
- `09:19:45Z`~`09:20:20Z` 전체 회귀: 90개 셀·1,440개 직접 요청, 합격 셀 **83개 유지**,
  기존 NoValidPoint 400 **9개**, 기준선 대비 비회귀, exit 0.
  기준선 파일 SHA-256 `f62263d9e2c7133ecee9c38e3914297f4d7179623e2eec245f8d73224b9c5d51`.
  warm-up 뒤 MemAvailable 29.979%, warm-up 최소 29.016%, swap 사용 8KiB였다.
- 1회차 정상 직접 부하 SSM `deae42fb-a17b-4268-8a83-a7caf8f4df12`를 시작했다.
  제어 스크립트 SHA-256 `df459a6053a10f35b2fe9ccadc3c50227873991fc5fbc8d8281e955aa2a9bae6`.
  기존 정상 요청 83개·60건/분·동시성2·timeout5초·30분/1,800건을 유지한다.
  자원은 5초 간격으로 30분, WAL은 시작 후 5·15·25분, full backup은 10분에 실행한다.
  가용 메모리 20% 미만 표본 또는 service 비정상 재시작을 관측하면 중단·실패로 기록한다.
  앱 API 부하는 아직 시작하지 않았고 외부 호출 가드는 비활성이다.

### 1회차 직접 부하: 당시 swap 증가 조건으로 중단·불합격

- 실제 구간: `2026-09-05T09:27:36.038097Z`~`09:48:06.187362Z`(약 20분 30초).
  목표 30분·1,800건을 완료한 시험이 아니며, 완료·성공 관측치는 **1,227건**, 실패·missed start는 각각 0건이다.
  이 중단 구간의 직접 요청 p50 **9.775ms**, p95 **67.130ms**, 최대 **152.057ms**, 5초 초과 0건이다.
- 자원 표본 **246/360개**, 최소 MemAvailable **27.614%**, service/container 비정상 상태·restart 증가·OOM 표본 0.
  warm-up 이후 첫 표본 swap 사용 **8KiB**, 최대 **48KiB**, 최대 증가 **40KiB**;
  `pswpin` 증가 0, `pswpout` 증가 10페이지였다.
- 최종 시계열 대조에서 `pswpout` 증가는 **`09:27:41Z` 한 구간(첫 표본 다음 5초)**에만 10페이지 발생했다.
  이후 관측 종료까지 추가 swap-out은 없었다. swap 사용량 표본은 8→44→48KiB로 관측됐으며,
  이번 중단 사유는 지속 swap이 아니라 **warm-up 이후 증가량 0 조건 위반**이다.
- 당시 artifact 계약 §9.2의 **"swap 사용량 증가 없음"**과 기존 자원 판정기의 증가량 0 조건을 위반했다.
  증가량이 작다는 이유로 허용치를 새로 만들지 않았다. 이 관측만으로 지속적인 swap thrashing이나
  4GiB의 처리 능력 부족, swap을 유발한 특정 프로세스까지 단정하지 않는다.
- 중단 명령 `65266b22-d830-4234-9b90-6fbfb7018a3a`: Success / exit 0.
  `09:48:03.497804Z`에 위 수치와 원인을 `external-abort.json`에 보존한 뒤 해당 시험의 프로세스 그룹만 종료했다.
  controller가 자원 수집도 정리했고 `direct-result.json`은 `passed=false`, `abortReason=test_process_failed`,
  load/metrics exit `-15`로 남았다. 이는 운영 서비스의 비정상 종료가 아니라 계약 위반을 확인한 운영자 중단이다.
- 관측 구간 안의 full backup **1회 성공**(`09:37:38Z`~`09:38:27Z`), WAL **2회 성공**
  (`09:32:37Z`, `09:42:39Z`). 각 unit의 새 실행·Result=success·ExecMainStatus=0을 확인했다.
  25분 예정 WAL은 중단 때문에 미실행이며 3회 성공으로 세지 않는다.
- 증거 수집·감사 `370a7625-0553-459f-92c5-8159cc07c36c`: Success / exit 0.
  정상 구간 Docker GC 로그 1,695줄, GraphHopper/backend Full GC 완료 0, kernel·heap OOM 0.
  fixture 좌표·요청 query·이메일·credential·graph import·주 service runtime GC 중복 기록 모두 0.
  이 로그 감사의 통과는 중단된 전체 시험을 합격으로 바꾸지 않는다.
- 자원 판정 `passed=false`. 중단으로 표본 114개와 정상 부하 최종 summary가 없으며,
  미실행 요청을 성공·완료로 채우거나 실제 구간을 30분 결과로 환산하지 않았다.
- 종료·재부팅·Importer를 포함한 1회차 전체 계약, 2·3회차, 승인 fixture의 앱 API 2,100건 비교는 미완료/미실행이다.
  가드는 활성화한 적이 없으며 실제 프로세스의 비활성·빈 run ID를 재확인했다.

### 새 서버의 최신 DB를 유지한 8GiB 복구

- 복구 준비 SSM `d54112c9-07a3-4406-aac6-c52f3be37e72`: Success / exit 0,
  `09:57:46.011777Z`~`09:58:35.196738Z`.
  backend를 중지한 뒤 추가 full backup·WAL 성공, PostgreSQL exit 0 정상 종료를 확인했다.
  이 추가 유지보수를 앞선 4GiB 부하 구간의 백업·WAL 횟수에 합산하지 않는다.
- 원래 application.env·compose.env·backend memory drop-in을 보존본과 byte 단위로 일치하게 복원했다.
  비밀 환경파일은 0600, 후보 설정 사본과 복구 결과는 root 전용
  `/opt/runninggu-validation/2b-restore8g-20260905/`에 보존한다.
- 변경 대상은 최신 DB 디스크를 가진 **새 `i-07aa483968f4daddc`**다. 옛 2a 인스턴스의 정지된 DB를
  다시 공개하지 않으며, EIP·새 root EBS·애플리케이션 release·graph artifact는 유지한다.
  재기동 뒤 실제 8GiB·JVM/cgroup·readiness·HTTPS 결과를 확인한다.

- EC2 콘솔에서 새 ID의 `c7i-flex.large` → **`m7i-flex.large` 변경 성공**과 running을 확인했다.
  Free Plan을 변경하지 않았으며 같은 2b subnet·root EBS·EIP를 유지한다.
- 첫 재기동 확인 `1aafc8dc-7d1f-4b1b-a7ae-80275bbb25db`는 DB 시작 직후 `postgres_check` 단계에서 실패했다.
  원래 실패 JSON은 `restore-attempt1-postgres-readiness.json`에 보존했고,
  `pg_isready --quiet`로 DB 준비를 기다린 뒤 같은 복구 검증을 이어갔다.
- 복구 확인 `4a1fd1a4-cdf4-4eab-afa6-1186dc48726b`: **Success / exit 0**,
  `10:11:27.819307Z`~`10:11:51.079172Z`, boot ID `aa9fa484-6ca0-412f-a8e8-60bfd271d60b`.
  verify oneshot **3,519ms**, `ExecStartPre` 포함 start job **744ms**,
  verify 시작부터 GraphHopper·backend readiness **21,893ms**로 120초 기준 통과.
- MemTotal **7,962,904KiB**, MemAvailable **6,200,300KiB / 77.865%**, swap 사용 0KiB.
  복구 직후 단일 표본이며 새 30분 부하의 합격 근거로 전용하지 않는다.
- 실제 backend Xms256m/Xmx1g, MemoryHigh1GiB/MemoryMax1.5GiB;
  GraphHopper Xms1g/Xmx4g, reservation3GiB, memory 및 memory+swap5GiB, `memory.swap.max=0` 확인.
  같은 release·graph artifact·GraphHopper/PG image를 유지했고 graph import·kernel OOM 0,
  backend·GraphHopper·Nginx active/success/NRestarts0, 백업·WAL·certbot timer active다.
- 실제 backend guard=false / 빈 run ID, pgBackRest check와 복구 후 WAL check 성공.
- 외부 PC에서 `python scripts/api/prepare_api_load.py --year-month 2026-09 --probe-public`: exit 0,
  TLS 검증을 유지한 HTTPS 7항목 통과(공개 조회 6개 200, 미인증 `/api/me` 401), `loadExecuted=false`.
- EC2 최종 대조: 새 `i-07aa483968f4daddc`는 2b / 8GiB / running / 상태 검사 **3/3 통과** / 기존 EIP 연결,
  옛 `i-05457509f8383f45c`는 2a / 8GiB / stopped / EIP 미연결이다.
  옛 root EBS·이전 AMI·snapshot은 보존하므로 컴퓨팅 정지와 별개로 저장 비용은 계속 발생한다.
- 최종 SSM `90174aa6-b6da-43f9-92a0-cc86fba5c03a`: Success / exit 0,
  `10:14:59.362908Z`에 실제 가드 비활성, 남은 직접 부하·자원 수집 프로세스 **0개**,
  Docker·backend·GraphHopper·Nginx·backup/WAL/certbot timer의 active 및 enabled를 확인했다.
  부분 요청 통계는 `4g-cycle1-20260905/partial-request-summary.json`, 최종 상태는
  `2b-restore8g-20260905/final-check.json`에 보존했다.

### 최종 판정

| 항목 | 실제 결과 |
|---|---|
| Free Plan에서 2b의 `c7i-flex.large` 생성·이전 | 성공 |
| 동일 artifact의 4GiB cold boot·전체 회귀 | 통과 |
| 4GiB 첫 정상 직접 부하 | 당시 swap 증가 조건 미달로 약 20분 30초에 중단·불합격 |
| 직접 요청 관측치 | 완료·성공 1,227건, 실패·missed start 0; 1,800건 전체 시험 미완료 |
| 4GiB 전체 계약 3회 연속 | 0회 완료, 2·3회차 미실행 |
| 4GiB 앱 API 2,100건 비교 | 미실행 |
| 현재 staging | 새 2b 서버의 8GiB·원래 설정으로 복구, guard 비활성·HTTPS 통과 |

4GiB 사용 승인은 보류한다. 이 결과는 당시의 swap 증가 0 기준 위반이며, 단독으로
지속 swap이나 요청 처리 한계를 입증하지 않는다. 이번 결과를 통과로 바꾸기 위해 swap을 끄거나
cache·부하 조건을 바꾸지 않았다.

## 중단 후 판정 기준 검토 이력

사용자는 swap 증가 0 조건을 어떤 기준으로 대체할지 설명을 요청했다.
이를 기준 변경 승인으로 잘못 해석해 먼저 반영했던 `capacity-v2` 문서·판정기 변경은 되돌렸다.
해당 변경으로 배포하거나 실제 부하를 실행하지 않았다. 기존 실행 증거·중단 결과·8GiB 복구 상태는 보존한다.

swap 사용량 외에 실제 요청 성능과 메모리 압박의 지속 시간을 함께 판단하는 대체 기준을 제안할 예정이다.
수치·관측 기간·합격/불합격 규칙을 확정한 뒤 문서와 계측·판정기를 함께 변경한다.

## capacity-v2 승인 후 재시험 준비

운영책임자는 하루 방문 20회 미만을 예상하며 4GiB 운영·최소 비용을 우선 목표로 설명했고,
이후 제안한 재시험 계획대로 진행하도록 승인했다. swap 증가는 진단용으로 기록하고 지속 활동은
요청·메모리·GC와 대조한다. 기존 요청량·응답시간·메모리 여유 20%·OOM·재시작·반복 Full GC·
백업·WAL·3회 연속 조건은 유지한다. PSI `1%·3분`은 적용하지 않는다.

- 브랜치: 최신 develop `0349f9fa8e12ee3e57435e5822146c0df804af84`에서 생성한
  `fix/staging-4g-capacity-policy`. 이후 develop의 앱·백엔드 기능 변경은 4GiB 비교 대상에 섞지 않고,
  실제 서버의 `673a2f7` JAR와 기존 graph artifact를 유지한다.
- 자원 판정기 테스트 12개 2회 통과, 앱 부하 도구 테스트 49개 통과.
- 현재 단계는 기준·판정기 검증과 서버·복구 경로 사전 대조다. 새 4GiB 전환·부하·24시간 관찰의
  성공 결과는 아직 없으며, 완료한 결과만 아래에 추가한다.
