# PR #255 머지 후 staging 배포 — 2026-09-04

> `develop` 머지 commit의 push CI 묶음으로 staging 전환과 배포 스모크를 완료했다.
> **앱 API 30분 부하·4GiB 시험·프로덕션 공개·`main` 릴리스는 수행하지 않았다.**

## 배포 식별

| 항목 | 값 |
|---|---|
| PR | [#255](https://github.com/uuusun/RunningGu/pull/255), merge `2026-09-04T06:45:13Z` |
| source / release | `1c3f2ef02514a2ab3efcb85bb8edb7196759579f` |
| CI | [33845682814](https://github.com/uuusun/RunningGu/actions/runs/33845682814), push, attempt 1, success |
| CI artifact | `runninggu-backend-1c3f2ef02514a2ab3efcb85bb8edb7196759579f` |
| 이전 release | `b8dafb86e8a608b8ae1fa10e9ff274ca1df5231b`, 삭제하지 않음 |
| EC2 | 서울 staging `m7i-flex.large`, 8GiB, 기존 인스턴스 유지 |
| S3 prefix | 기존 private artifact bucket의 `backend/staging/1c3f2ef02514a2ab3efcb85bb8edb7196759579f/` |

PR head용 묶음의 이름을 바꿔 승격하지 않았다. `develop` push CI에서 새로 받은 JAR 2개·snapshot·
manifest·`SHA256SUMS`의 5개 파일을 묶고, manifest의 commit과 workflow run ID를 대조했다.
CI의 backend test/bootJar 성공을 확인했다. 문서만 추가한 이 작업에서 로컬 backend test를 다시
실행하지 않았으며 Android 전체 검증이나 정식 릴리스 CI를 대신한다고 주장하지 않는다.

| 파일 | SHA-256 |
|---|---|
| 전달 ZIP | `b0451ae851988ba4500016321fe91a62085c84694d00745968570e5230257334` |
| 해당 commit source tar.gz | `6977cdd1ae051e71ee93793220d13e0e185d2547e55169ca292fed34eb89ec9e` |
| runninggu-server.jar | `00c59034cc61d1171ade126cffb31a6615d3e78dbe67818f7fa6661d159c53a0` |
| runninggu-contest-import.jar | `14c231c846c8843193aefc8a2eda7936a521ea899845c9fd5c606ef03d3375dd` |

전달 객체 2개는 기존 승인된 KMS 키로 SSE-KMS 암호화했으며 EC2 `head-object`로 확인했다.
EC2 다운로드 후 전달 hash, 내부 4개 checksum, 5개 허용 파일 목록, symlink 부재를 검사했다.
새 release를 먼저 설치·검증한 뒤 `current`를 전환했다. 소스 archive도 보관했지만 실행 저장소는
소유자 `runninggu`의 Git fetch/checkout으로 머지 commit에 맞췄다.

## 기존 검증과 같은 실행 코드임을 대조

로컬과 EC2 모두 다음 비교가 exit 0, 변경 없음이었다.

```text
git diff --exit-code b8dafb86e8a608b8ae1fa10e9ff274ca1df5231b \
  1c3f2ef02514a2ab3efcb85bb8edb7196759579f -- \
  backend scripts/osm .github/workflows/backend.yml .github/actions/backend-verify
```

따라서 이번 merge SHA 전환만을 이유로 graph를 새로 import하거나 image를 rebuild하지 않았다.
기존 [8GiB 부하·격리·재부팅 결과](graphhopper-ec2-8g-20260904.md)는 이전 source에서 수행한
기록으로 그대로 보존한다. **새 SHA에서 30분 시험이나 호스트 재부팅을 다시 했다고 기록하지 않는다.**

## 실제 실행 결과

시간은 UTC다.

1. DB 전체 백업: `06:56:18` 시작 → `06:57:09` 완료, `Result=success`, `ExecMainStatus=0`.
2. WAL 점검: `06:59:00` 완료, `Result=success`, `ExecMainStatus=0`.
3. 새 release 설치 후에도 기존 `current`가 유지됨을 확인했다.
4. 배포 시작 `07:05:03`, graph verify oneshot **654ms**.
5. Spring 중지 → 소스/현재 release 전환 → 대회 Importer **`status=NO_OP`**, exit 0 → Spring 시작.
6. 내부 대회 API 준비 완료 `07:05:24`. 위 시작 시각부터 약 **21초**.
7. 실제 repository HEAD·`current`·Spring 프로세스 working directory가 새 release와 일치했다.
8. PostgreSQL·GraphHopper의 container 시작 시각은 전환 전후 동일했다. 둘 다 재시작하지 않았다.
9. backend·GraphHopper `Result=success`, `NRestarts=0`; Docker·Nginx도 active.
10. 외부 HTTPS 대회 목록과 코스 지역 목록 **200**, TLS 검증 0(성공), HTTP→HTTPS **301**,
    공개 `/v3/api-docs` **404**. 내부 `/info`에서 `run` profile 확인.

Spring 기동 polling 중 준비되기 전 일시적인 connection refused가 있었고 위 시각에 200으로
수렴했다. 서비스 crash나 부하 시험 실패로 세지 않았으며, 그 출력을 숨겨 전 요청 성공으로 적지도 않는다.

### SSM 감사 식별자와 정정한 명령

| 실행 | Command ID | 결과 |
|---|---|---|
| 최초 사전 점검 | `13e6b2c7-42ed-41ab-bf59-7211648b4c35` | 실패 128: root Git의 소유권 검사; 서비스 변경 없음 |
| 소유자로 재점검 | `654fa2fb-bbe2-4549-afa7-23d7bbb57cf7` | 성공 0; global safe.directory 완화 없이 처리 |
| fetch·백업·WAL 명령 | `491094e6-fad7-458c-a7ef-5058cf3909ce` | 전체 명령 실패 5: 백업 성공 뒤 잘못 적은 WAL unit 이름에서 실패 |
| 실제 WAL unit 재실행 | `c1c62b81-9173-4b1f-bd8e-f3f3f71a561e` | 성공 0, `runninggu-postgres-wal-archive-check.service` |
| 다운로드·새 release 설치 | `e34c61a5-e63c-4600-90f0-9fb3164337b3` | 성공 0 |
| 배포 전환 | `1c49747c-2945-482d-9656-d3bbf920f51c` | 성공 0, 새 버전 readiness와 보존 조건 확인 |
| 배포 후 내부 스모크 | `3d221f09-4576-4115-a12c-d42b1a4a023b` | 성공 0 |

WAL 오타가 포함된 복합 명령 전체를 성공이라고 보고하지 않는다. 백업 unit 자체의 성공과
별도로 재실행한 올바른 WAL unit의 성공을 대조한 뒤 배포했다.

## 유지한 상태와 한계

- graph: `gh11-korea-20260901-2ff6731b181a-2b8515dd29fc`.
- GH image: `sha256:16b3618f3e69a094990e6c9360ee92972beec643ca26bc5d9b61404172910753`.
- GH Xms1g/Xmx4g, reservation 3g, memory/memory+swap 5g 유지.
- Spring Xms256m/Xmx1g, MemoryHigh 1G/MemoryMax 1536M 유지. env·drop-in을 배포 전 백업과 `cmp`.
- PostgreSQL 기존 image·data 유지. 미커밋 `backend/postgres/Dockerfile` 수정도 백업·대조해 보존했다.
  이 수정까지 포함한 clean-checkout 이미지 재현성은 이번 작업으로 해소하지 않았다.
- `07:06:33` 스모크 때 MemAvailable 6035MiB/전체 7776MiB, swap 0/4095MiB.
  **한 시점 관측값이며 부하 시험 peak가 아니다.**
- boot ID는 이전 `ca60323b-eead-4f6f-a427-76a161c8674a` 그대로다. EC2는 실행 중이다.
- KTO·카카오 설정은 값 존재만 확인했다. 키·토큰·이메일·좌표는 출력하지 않았다.
- `MAIL_ENABLED=false`, SMTP 연결/인증 항목은 비어 있다. 메일 포함 전체 앱 API 검증은 미완료다.

다음 단계는 [앱 API 부하 계획 초안](../api-load-test-plan.md)의 요청량·합격 기준·테스트 계정·
외부 호출 상한 확정이다. 그 승인 전에는 앱 부하, 회원 생성, 4GiB 변경을 실행하지 않는다.
