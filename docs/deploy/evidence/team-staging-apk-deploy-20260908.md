# 앱팀 최신 APK 제작·EC2 배포 — 2026-09-08

**후속 기능 시험:** [실제 서명 APK 검증](team-apk-fullcheck-20260908.md)에서 두 계정 로그인·동선/코스/찜 저장·복원·#311 편집·양방향 계정 분리를 확인했다. 아래 미시험 표기는 최초 배포 직후 기록이다. 외부 인증 시험은 후속 문서의 상태를 따른다.

**후속 정정:** [Windows AVD ARM 설치 재검증](windows-avd-arm-install-20260908.md)에서 같은 APK를 `--abi arm64-v8a`로 설치해 카카오 지도·경로선을 확인했다. 아래 x86_64 지도 실패는 기본 설치 당시 기록이다. 현재 전달본은 Windows 설치 도우미를 포함한 후속 ZIP이며 실기기가 필수라는 안내를 정정한다.

사용자가 #311 머지 후 최신 APK 제작과 기존 EC2 기동·배포를 요청했다. 파일은 사용자가 민지·건모에게 직접 비공개 전달한다. 다른 팀원의 코드를 수정하지 않았고 원래 작업 폴더의 미커밋 변경을 보존했다. 제품 명세·API·DB 스키마 변경은 없다.

## 고정 소스와 산출물

- 앱: `ccab0e0a1a0635daba7eedc3fc9985e624d8e366`. 전달 직전 원격 develop과 다시 대조했다.
- #311 최종 head `17265f4ddd013290048c34d88691d1fd2661baa2`, squash `f766bdf2ac359dd54345804dd54ed343a33435d8`. 저장 후 편집을 포함한다.
- 별도 detached worktree: `.codex-worktrees/release-team-20260908-ccab0e0a`.
- 백엔드: `395c1e39e7c28a8445d350603d1d53c5758684fa`, [Backend CI 34114369868](https://github.com/uuusun/RunningGu/actions/runs/34114369868) 성공 artifact.
- `git diff 395c1e39 ccab0e0 -- backend scripts/osm .github/actions/backend-verify .github/workflows/backend.yml` 차이 없음. 앱 SHA와 서버 artifact SHA는 다르게 기록한다.
- APK: `.cache/release-artifacts/20260908-team-ccab0e0a/runninggu-staging-ccab0e0a.apk`, 44,721,194 bytes.
- APK SHA256: `0db9fb888fb733f949791224b612e8b0636151c98a9c7867299558521a8a72df`.
- API: `https://staging-api.runninggu.store/api/`, package `com.runninggu.app`, version `1.0 (1)`.
- 기존 출시 인증서 SHA256: `41073757d098062b234c83695c04bd4753640fe8da86bb1f2ba3a2b2fb734fc7`.
- Kakao key hash: `oRJtsESChTHqkP9fnp4lRq+wNxM=`. 기존 네이티브 키를 사용했고 키 원문·서명 암호는 출력하지 않았다.

## 빌드·기기 검증

JDK 21에서 `:app:testDebugUnitTest :app:assembleRelease --offline --no-daemon --console=plain --max-workers=2` 성공 (3분 56초). 111개 테스트 클래스, @Test 922개, 실패·오류·건너뜀 0. R8·리소스 축소 release APK를 기존 JKS로 서명하고 zipalign·apksigner 검증을 통과했다. `debuggable=false`, staging 주소 포함, 로컬 서버 주소·placeholder 미포함, 위치 권한 없음.

기존 Pixel_10_Pro AVD는 다른 인증서여서 `adb install -r`가 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`로 거부됐다. 기존 앱·데이터는 보존했다. 작업 폴더의 별도 AVD `RunningGu_Team_20260908`를 생성해 새 APK 설치와 실행을 확인했다 (Android 17, API 37, x86_64, 16KiB 이미지).

- 로그인 화면에 이메일·비밀번호 입력, 카카오 로그인, 가입·비밀번호 찾기, 게스트 버튼이 표시된다.
- 게스트 홈에 스테이징 서버의 대회명·일정·접수 상태와 축제 카드가 표시된다.
- 러닝코스 화면에 출발지 선택·거리 조절·지역별 탭이 표시된다.
- 서울시청 프리셋 선택 후 실제 서버의 러닝 경로 카드(거리·시간·난이도·상승고도)와 서울광장 등 장소 목록이 표시된다.
- **이 x86_64 AVD의 기본 설치에서는 카카오 지도가 실패했다.** SDK 오류는 `libK3fAndroid.so is for EM_AARCH64 (183) instead of EM_X86_64 (62)`다. 실제 APK의 지도 라이브러리도 `arm64-v8a`·`armeabi-v7a`만 있다. 이후 동일 AVD의 ARM 변환 기능을 확인하고 ARM 모드로 설치하니 지도가 표시됐다 (상단 후속 기록).
- 두 계정의 존재 여부 API는 모두 true. 비밀번호 로그인·저장·복원·편집·계정 분리·카카오 로그인은 이번 기기 시험에서 미실행이다. 전체 기능 QA 완료로 보고하지 않는다.
- [후속 UI #307](https://github.com/uuusun/RunningGu/issues/307)은 전달 전 조회에서도 OPEN이다.

## EC2·백업·배포

대상은 기존 `i-07aa483968f4daddc` / `runninggu-staging-4g-2b` / c7i-flex.large / 서울 2b / 4GiB다. 11:21 KST 시작했고 최종 Running, system/instance status 모두 ok다. 구 8GiB `i-05457509f8383f45c`는 Stopped를 유지했다. EBS·Elastic IP·보안 그룹·IAM 권한은 변경하지 않았다.

1. SSM Online과 기존 release `673a2f796052f4553113d5cd25608fb3821222ac`를 확인했다.
2. 배포 직전 전체 백업 label `20260908-022738F` 완료 (UTC 02:27:38~02:28:23), WAL 검사 성공. 백업 명령 ID `21fe93f1-b554-449c-b377-270d88434993`.
3. CI ZIP SHA256 `8ba6638e06066d88449e41fa9441f351731b2bfd9ede261490a3e3c91ca295d6`과 내부 SHA256SUMS 4개를 로컬·EC2에서 대조했다.
4. 최종 artifact 경로는 `s3://runninggu-staging-artifacts-987622176638/backend/staging/395c1e39e7c28a8445d350603d1d53c5758684fa/team-20260908.zip`이다 (SSE AES256).
5. backend 중지 → 새 release로 current 링크 전환 → Importer → backend 시작 → 내부 readiness·외부 HTTPS 확인. 11:35:40 KST 성공. 배포 명령 ID `6a596f41-62d2-40c4-8ed1-7ee1d3ee70f9`.
6. Flyway 8개 migration 검증, 추가 migration 없음, Importer `NO_OP` 확인.
7. 기존 graph `gh11-korea-20260901-2ff6731b181a-2b8515dd29fc`, env 2개, Nginx site·로그 14일 정책의 해시가 배포 전후 동일하다. 이전 release와 백업을 유지했다.

서버 repository에는 기존 `backend/postgres/Dockerfile` 수정이 있어 checkout을 바꾸지 않았다. 실제 실행 JAR은 새 release이며 기존 런타임 시작·PostgreSQL 대기 스크립트는 최신 소스와 SHA256이 같다. 4GiB 실효 설정도 최신 기준과 일치한다.

최종 명령 ID `293c8e68-a2f5-4c10-80f7-f532590d66b8`:

- PostgreSQL healthy, GraphHopper·backend·Nginx active/running.
- GraphHopper reservation 2GiB, memory/memorySwap 2560MiB, OOM false.
- backend MemoryHigh 640MiB, MemoryMax 768MiB, NRestarts 0.
- 5432·8080·8989는 loopback만 listen. TLS 인증서 만료 `2026-12-02T08:55:18Z`.

초기 시도에서 CloudShell 입력 중복, root의 git 소유권 검사, 잘못 선택한 S3 `backend/<SHA>` 경로 때문에 각각 실패했다. 시작은 EC2 콘솔, git 읽기는 해당 저장소만 지정한 safe.directory, artifact는 기존 허용된 `backend/staging/<SHA>` 경로로 정정했다. 실패는 backend 교체 전에 발생했으며 새 권한을 추가하거나 기존 설정을 덮어쓰지 않았다.

## 외부 HTTPS 기능 스모크

공개 API만 호출했다. 개인정보·토큰·비밀번호를 출력하지 않고 상태·건수만 `.cache/team-deploy-20260908/public-smoke.json`에 기록했다.

| 확인 | 결과 |
|---|---|
| 대회 목록·축제 | 각 200, 데이터 표시 |
| 코스 지역·목록 | 200, 지역 11개 |
| 공개 코스 전체 상세 | 200, polyline 있음, 고도 51점 |
| 공개 프리셋 출발지 주변 | 200, 항목 12개 중 ROUTE 1개, degradedSources 빈 배열 |
| 동선 생성 | 200, 3일·10개 블록 |
| 민지·건모 계정 존재 | 각 200, true |

첫 스모크 스크립트가 계약의 `courseId`·`kind` 대신 `id`·`type`을 읽어 상세 404·경로 수 0으로 잘못 보고했다. 스크립트 필드를 계약대로 수정한 재시험에서 위 결과를 확인했다. 앱·서버 코드는 수정하지 않았다.

## 전달·운영 범위

전달 묶음에는 APK·SHA256SUMS·설치 안내·기능 체크리스트·버전 정보만 포함한다. JKS, 암호, API 키 원문, R8 mapping, 서버 환경 파일은 넣지 않는다. 실제 두 사람의 수신·로그인·전체 기능 시험은 사용자가 전달한 뒤 진행한다.

전달 ZIP은 `.cache/release-artifacts/20260908-team-ccab0e0a/runninggu-team-test-20260908-ccab0e0a.zip` (22,316,524 bytes), SHA256 `c274cd3163e6d824158df9fdbc4621f8b89d27817fdc04ea134870549095803e`다. ZIP 내부 5개 파일 구성과 SHA256SUMS의 4개 파일 해시를 실제 스트림으로 검증했다. 검증용 임시 AVD만 종료했고 기존 emulator-5554는 보존했다. 잘못된 S3 경로에 이번 작업이 올린 중복 ZIP은 삭제했으며 올바른 `backend/staging` artifact는 보존했다.

EC2는 테스트용으로 Running을 유지한다. 종료 시각은 사용자에게 확인 요청했으며, 답변 없이 자동 중지 시각을 만들지 않는다. EBS·고정 IP 등 보유 비용은 EC2 중지와 별개다.
