# PR #263 머지 후 staging 배포 — 2026-09-04

> `develop` push CI 묶음의 배포와 공개 조회·연령 입력 거부 검증을 완료했다.
> 배포 직후에는 정상 가입을 실행하지 않았다. 후속 [첫 계정 가입](signup-success-20260904.md)은 확인했다.
> 후속 별도 로그인도 확인했지만 [마케팅 상태 복원 결함](marketing-relogin-diagnosis-20260904.md)이 있다.
> **계정 기능 전체·30분 앱 혼합 부하·4GiB 시험은 아직 완료하지 않았다.**

## 식별 정보와 변경 범위

| 항목 | 값 |
|---|---|
| PR | [#263](https://github.com/uuusun/RunningGu/pull/263), merge `2026-09-04T08:54:23Z` |
| source / release | `b9e632d768c99f05f4028545b7e265817038d9c8` |
| CI | [33855704615](https://github.com/uuusun/RunningGu/actions/runs/33855704615), develop push, attempt 1, success |
| CI artifact | `runninggu-backend-b9e632d768c99f05f4028545b7e265817038d9c8`, artifact ID `9930171366` |
| 직전 release | `1c3f2ef02514a2ab3efcb85bb8edb7196759579f`, 삭제하지 않음 |
| EC2 | 기존 서울 staging `m7i-flex.large`, 8GiB |
| 전달 경로 | 기존 비공개 artifact bucket의 `backend/staging/b9e632d768c99f05f4028545b7e265817038d9c8/` |

이전 버전과의 차이는 연령 검증·관련 오류 처리·테스트·API 문서 18개 파일이다. 로컬과 EC2에서
다음 경로의 commit 간 차이가 없음을 확인했다: `backend/deploy`, `backend/postgres`,
`backend/compose.yaml`, `backend/compose.ec2.yaml`, `backend/src/main/resources`, `scripts/osm`,
`.github/workflows/backend.yml`, `.github/actions/backend-verify`.
따라서 새 graph 생성·DB image rebuild·약관 활성 버전 변경 없이 백엔드만 전환했다.
`main` 변경이나 프로덕션 배포는 아니다.

| 파일 | SHA-256 |
|---|---|
| 전달 ZIP | `d0535c82f5e54fb6e6bedbe34661ee877d77a9b9a4017c075d725d784dd4c2b8` |
| tracked source tar.gz | `2ec39500f12d24cd3b68349d5b3d8ac6047765fcad36bc9db53de2d5e8737d17` |
| runninggu-server.jar | `4051af672336dc13875d597dfc20ebcf2ef7cf12cfe10f21713daf62ad737739` |
| runninggu-contest-import.jar | `b6959301bf4d7cb560567d927c07321b8ad42536771869727e2762618b766817` |
| data/contest_snapshot.json | `41925bdef25d5b37123913616acd0a1e23e3c515aee275e9987ae52e263b26b4` |
| release-manifest.txt | `75d0b4bb92b2783eae0e42cfdb5db36c4ee06887e9f10c62fec890b2b6602cff` |

CI의 JAR 2개·snapshot·manifest·SHA256SUMS, 총 5개 파일만 묶었다. manifest의 commit·run ID,
내부 checksum 4개를 로컬에서 대조했다. S3에 객체 2개를 기존 승인된 KMS 키로 암호화해 올렸고,
EC2에서도 `head-object`의 SSE-KMS/key, 전달 hash, 내부 checksum, ZIP 허용 파일 목록·중복·
symlink 부재·크기 상한을 확인했다. source archive에는 로컬 미커밋 파일이 포함되지 않는다.

## 실제 배포 결과

시간은 UTC다.

1. 배포 전 source/current가 직전 SHA임을 확인하고 환경 파일·메모리 drop-in·미커밋
   PostgreSQL Dockerfile을 EC2 root 전용 검증 디렉터리에 보관했다. 비밀값은 출력하지 않았다.
2. 새 release를 root:runninggu, 디렉터리 `0750`, 파일 `0440`으로 먼저 설치했다.
   이때 실행 중인 `current`는 바꾸지 않았다.
3. DB 백업 `09:13:22.030` → `09:14:14.719`, `Result=success`, `ExecMainStatus=0`.
4. 올바른 `runninggu-postgres-wal-archive-check.service`의 success/0을 확인했다.
   전체 준비 완료 `09:14:15.015`.
5. 활성화 시작 `09:16:36.976`. GraphHopper verify oneshot **656ms**.
6. Spring 중지 → 저장소 exact commit checkout → `current` 원자 전환 → Importer/Flyway 실행
   성공, `status=NO_OP` → Spring 시작 순서를 지켰다.
7. 내부 대회 API readiness `09:16:58.038`, 활성화 시작부터 **21,062ms**.
8. repository HEAD·current·실제 Spring 프로세스 working directory가 새 SHA와 일치했다.
9. PostgreSQL·GraphHopper image와 시작 시각, graph symlink, 호스트 boot ID는 그대로였다.
   DB·GraphHopper·EC2를 재시작하지 않았다.
10. application.env·compose.env·메모리 drop-in·미커밋 Dockerfile은 배포 전과 byte 단위 동일했다.
    실제 Spring 프로세스에서도 `MAIL_ENABLED=true`와 SMTP 인증 항목 존재를 확인했다.
11. Spring MemoryHigh 1GiB/MemoryMax 1536MiB, GH reservation 3GiB/memory·memory+swap 5GiB,
    Docker restart `no` 유지. Spring·GH `Result=success`, `NRestarts=0`.
12. 활성화 이후 Spring journal에서 실제 SMTP 인증정보 2개와 지정 수신 주소 2개의 원문 일치
    **0건**. journal은 메모리에서 검사했고 원문을 출력하지 않았다. 모든 시크릿·모든 로그 저장소를
    전수 검증했다는 의미는 아니다.

| SSM 실행 | Command ID | 결과 |
|---|---|---|
| 사전 점검·전달 검증·비활성 설치·백업·WAL | `44d37bbb-01b9-4fa1-8b38-88ec3de3be78` | Success, 0 |
| graph 검증·백엔드 전환·readiness·보존 확인 | `3c67a58f-7f9f-4723-ab73-6b637c002f59` | Success, 0 |

실패 시 직전 소스·release로 복구하는 경로를 준비했지만 이번 배포에서 실행하지 않았다.
따라서 이번 SHA의 롤백 시험까지 수행했다고 주장하지 않는다.

## 외부 HTTPS 검증

`09:18:26` 전후, 기존 공개 조회 도구를 **2회** 실행해 같은 7항목이 통과했다.

- 대회 목록·상세·마감 임박·월간 건수, 코스 지역·목록: 200 및 내용 검사 통과.
- 코스 전체 261개, 지역 11개 확인. 상세 좌표는 유효성만 검사하고 출력하지 않았다.
- 미인증 `/api/me`: 예상한 401. 이 401은 정상 가입·인증된 조회 성공이 아니다.
- HTTP → HTTPS: 301과 같은 staging HTTPS 목적지 확인.
- 공개 `/v3/api-docs`: 404. TLS 검증과 redirect 차단을 유지했다.

계정이 만들어질 수 없도록 필수 동의도 모두 false인 별도 입력으로
`POST /api/auth/signup`의 **연령 거부 경로만** 확인했다. 실제 수신 주소·실제 비밀번호를 쓰지 않았고,
인증 메일 발송이나 코드 검증을 호출하지 않았다.

| ageOver14 입력 | 실제 HTTP / code |
|---|---|
| 누락 | 400 / `VALIDATION_FAILED` |
| null | 400 / `VALIDATION_FAILED` |
| 문자열 `"true"` | 400 / `VALIDATION_FAILED` |
| 숫자 `1` | 400 / `VALIDATION_FAILED` |
| false | 400 / `AGE_REQUIREMENT_NOT_MET` |

연령 검증이 필수 동의 검사보다 앞서 실행됨을 포함해 API 명세 §1-5와 일치했다.
카카오 가입·정상 이메일 가입·로그인·토큰 갱신의 운영 검증을 대신하지 않는다.

## 남은 작업과 사용자 입력 경계

- 사용자가 Android Studio에서 설치한 앱이 있다고 확인했다. 연결된 에뮬레이터 1개를 읽기 전용으로
  확인했고, 작업 루트의 debug BASE_URL도 로컬 주소였다. 설치 APK 내부의 실제 주소는 추출하지 않았다.
  해당 commit의 `debug`는 `http://10.0.2.2:8080/api/` 고정이고 `release`만
  `local.properties`의 `API_BASE_URL`을 사용한다. 앱이 있다는 이유만으로 staging 연결로 간주하지 않는다.
  처음에는 별도 staging 빌드를 제안했으나 필수 변경이 아님을 정정했다. 이후 사용자 승인으로
  공유 코드를 바꾸지 않고 기존 release 설정을 사용한 [내부 검증 APK](android-staging-release-20260904.md)를
  빌드·서명·설치했다. 원래 프로젝트 설정은 보존했다. 후속 사용자 가입 보고와
  [서버 집계](signup-success-20260904.md)에서 인증 200·가입 201·첫 계정 생성을 확인했다.
- 연결 대상을 확인한 뒤 사용자가 약관·연령을 확인하고 인증 코드·비밀번호를 직접 입력해
  정상 verify → signup → login → 인증된 내 정보 조회를 검증한다. 동의값을 대신 만들거나
  DB 직접 삽입으로 대체하지 않는다. 인증 코드·비밀번호를 채팅으로 요청하지 않는다.
- SES 수신 확인은 [앞선 SMTP 기록](ses-smtp-staging-20260904.md)의 결과다. 이번 새 SHA에서
  인증 메일을 다시 보냈다고 기록하지 않는다. 이전 인증 코드를 재사용하도록 안내하지 않는다.
- 30분 앱 혼합 부하·외부 API 허용량·고정 입력·4GiB 시험은 [별도 계획](../api-load-test-plan.md)을 따른다.
- 이번에는 배포 CI의 backend test/bootJar 성공을 사용했다. 로컬 backend·Android 빌드, 기기 UI,
  30분 부하·재부팅·4GiB 시험을 다시 실행하지 않았다. EC2는 8GiB 실행 상태다.
- 기존 공개 준비 도구의 단위 테스트 14개를 재실행해 통과했고 `git diff --check`도 통과했다.
  CRLF 변환 안내는 있었지만 whitespace 오류는 없었다. 이번 턴에는 도구 코드를 변경하지 않았다.
- 문서 기록만 추가·동기화했다. API 계약·코드·IAM·DNS·Free Plan·SES 샌드박스는 바꾸지 않았고,
  commit·push·PR 생성도 하지 않았다. 다른 작업의 미커밋 변경은 보존했다.
