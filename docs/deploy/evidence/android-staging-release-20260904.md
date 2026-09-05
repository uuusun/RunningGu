# EC2 연결용 Android 내부 검증 APK — 2026-09-04

> 앱 공유 코드 변경 없이 기존 release 빌드의 서버 주소 설정을 사용했다.
> **빌드·설치·가입·별도 재로그인을 확인했다. 다만 재로그인 후 마케팅 OFF 표시 결함이 발견됐다.**

## 범위와 식별 정보

| 항목 | 값 |
|---|---|
| 승인 | 운영책임자가 기존 release 설정을 사용한 APK 빌드·설치·가입 검증 진행 승인 |
| source | `b9e632d768c99f05f4028545b7e265817038d9c8`, 현재 staging 백엔드와 동일 commit |
| source archive SHA-256 | `2ec39500f12d24cd3b68349d5b3d8ac6047765fcad36bc9db53de2d5e8737d17` |
| 빌드 종류 | release, R8 적용, debuggable 아님 |
| 서명 | 이 PC에 이미 있던 개발용 키. 정식 배포용 키가 아니며 출시 산출물로 사용하지 않음 |
| 대상 | 연결된 기존 Android 에뮬레이터 1개 |
| 앱 ID / 버전 | `com.runninggu.app`, versionCode 1 / versionName 1.0 |
| API 주소 | `https://staging-api.runninggu.store/api/` |
| APK 이름 | `runninggu-staging-b9e632d-internal-test.apk` |
| APK 크기 | 44,688,426 bytes |
| APK SHA-256 | `985685fe2e5585c1eccc75993a76c68e142b53153d2f5893b2492dd617ff0377` |

`release`는 Android의 빌드 종류다. `main` 머지·스토어 업로드·프로덕션 배포를 하지 않았다.
`staging` 빌드 종류를 새로 추가하거나 기존 debug 서버 주소를 고치지도 않았다.

## 기존 설정 보존과 빌드

1. 기존 `android/local.properties`에서 SDK·카카오 네이티브 키는 존재하지만 API 주소와 정식
   release 서명 항목 4개는 없음을 값 출력 없이 확인했다.
2. 검증된 source archive를 gitignore 대상 `.cache/stg263-build`에 별도로 해제했다.
   사용자가 작업 중인 브랜치·worktree를 checkout하거나 수정하지 않았다.
3. 원래 local.properties를 빌드 사본에 복사하고 그 사본에만 아래 한 줄을 추가했다.

   ```properties
   API_BASE_URL=https://staging-api.runninggu.store/api/
   ```

4. 원래 프로젝트의 API 주소 미설정 상태와 나머지 local.properties 내용 보존을 대조했다.
   카카오 키·비밀번호 등 값은 출력하지 않았다.
5. JDK 21.0.11로 다음을 실행했다. 첫 제한 환경 실행은 기존 Gradle cache lock 파일 접근 거부로
   실패했으며, 허용된 실행 환경에서 같은 명령을 다시 실행했다.

   ```text
   gradlew.bat :app:testDebugUnitTest :app:assembleRelease --offline --console=plain --no-configuration-cache
   ```

6. **BUILD SUCCESSFUL in 3m 2s**, 78 tasks executed.
   단위 테스트 **클래스 100개, 테스트 805개, 실패 0·오류 0·skip 0**를 XML 결과로 집계했다.
   deprecated API·opt-in·일부 native symbol strip 경고는 있었으며 이를 별도 실패로 바꾸거나 숨기지 않았다.
7. 빌드에 사용한 tracked `android/`·`docs/agreements/` 파일 **353개**를 source archive와
   SHA-256으로 대조해 변경 0개를 확인했다. 외부 API·UI·DTO·약관 문안·서명 관련 공유 코드는 수정하지 않았다.

Windows 기본 tar는 일부 한글 파일명에서 압축 해제에 실패했다. 그 부분 해제 폴더를 빌드에
재사용하지 않고, 새 폴더에 Python 표준 tarfile의 UTF-8 처리와 `filter='data'`로 해제한 뒤
위 파일 대조를 통과시켰다. 이 로컬 준비 오류를 EC2/API 오류로 집계하지 않는다.

## 서명·APK 검증·설치 결과

- 서명 없는 release APK를 먼저 만들고, SDK `apksigner`로 기존 개발용 키를 사용해 별도 내부
  검증 APK에 서명했다. 키를 새로 생성하거나 정식 서명 설정을 변경하지 않았다.
- 기존 설치 APK를 `.cache/stg263-build/installed-before.apk`에 보관하고 새 APK와 인증서
  SHA-256을 비교했다. **둘 다 서명 검증 성공, 동일 인증서**였다.
- 새 APK의 package ID·버전·debuggable 부재를 확인했다.
- 생성된 release BuildConfig뿐 아니라 APK의 DEX에서도 staging API 주소 존재를 확인했다.
  로컬 `http://10.0.2.2:8080/api/`와 자리표시자 API 주소는 DEX에서 검출되지 않았다.
- `adb -e install -r` → **Success**. 앱 삭제·데이터 초기화·강제 downgrade는 하지 않았다.
- `adb -e shell am start -W -n com.runninggu.app/.MainActivity` → **Status: ok**, cold launch,
  TotalTime 3,390ms. 프로세스 존재와 top resumed Activity가 MainActivity임을 확인했다.
- 설치·실행 확인 시각: **2026-09-04 18:36:48 KST**.

Activity 시작 성공과 서버에서의 정상 가입 성공은 다른 검증이다. 이번 기록에서 화면을 눈으로
직접 확인했다고 주장하지 않는다. 기존 APK는 보관했으나 실제 복구 설치는 수행하지 않았다.

## 사용자에게 넘긴 입력과 남은 검증

앱의 회원가입에서 사용자가 약관·연령을 직접 검토하고, 앞서 지정한 테스트 주소 중 하나로
비밀번호·새 인증 코드를 **앱에만 입력**하도록 요청했다. 채팅으로 비밀값을 요청하지 않았고,
동의·연령값을 대신 체크하거나 DB 직접 삽입·인증 우회를 하지 않았다.

- 정상 이메일 인증 → 가입 → 첫 세션 생성: 사용자 `가입완료` 보고와
  [서버 읽기 전용 증거](signup-success-20260904.md)로 확인했다. 인증 200·가입 201,
  회원 1명·약관 기록 3건·활성 세션 1건이다. 18:44:29 KST 설치 APK hash도 위 산출물과 일치했다.
- 별도 로그아웃 204·로그인 200은 후속 확인했다. 사용자는 내 정보 화면 표시를 확인했지만
  GET `/me` 호출은 없었고, 서버 ON과 달리 화면 OFF가 되는 [복원 결함](marketing-relogin-diagnosis-20260904.md)을 발견했다.
- 그 뒤 마케팅은 건드리지 않고 앱을 재시작해 사용자 내 정보 표시와
  [GET `/me` 200](api-readonly-boundaries-20260904.md)을 확인했다. 재로그인 표시 결함은 #287로 분리했다.
- 두 번째 계정의 정상 가입도 [후속 기록](api-readonly-boundaries-20260904.md)으로 확인했다.
- 전체 API 스모크·30분 혼합 부하·4GiB: 미완료.
- 카카오 로그인·지도 키 해시 등록·릴리스 화면 전체·기기 계측 테스트: 이 작업에서 미검증.
- 기존 [서버 배포 기록](staging-develop-b9e632d-20260904.md)의 EC2·SMTP·DB·GraphHopper 설정은
  변경하지 않았다. 서버 전체 시험이 완료됐다는 의미가 아니다.
- 원래 프로젝트·민지 담당 공유 코드 변경 없음. 이 기록과 관련 상태 문서만 갱신했으며
  commit·push·PR 생성은 하지 않았다.
