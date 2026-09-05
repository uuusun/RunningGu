# 스테이징 검증 APK — 커밋 고정과 내용 검증 (2026-09-05)

> 앱 코드 변경 없이 기존 release 설정에 스테이징 서버 주소만 넣어 빌드했다.
> **내용 검증은 전부 통과했으나 정식 키 서명은 하지 못했다** — 이 PC에 릴리스 keystore와
> 카카오 네이티브 키가 없다. 현재 산출물은 건모님께 전달할 수 있는 상태가 아니다.

## 범위와 식별 정보

| 항목 | 값 |
|---|---|
| 요청 | 운영책임자가 EC2 스테이징 HTTPS 준비 완료로 앱 검증 착수 지시 (2026-09-05) |
| source | `77da13dc37094473136c72a44f2a79fc1df9b316` (`77da13d`) |
| source 시각 | 2026-09-05 16:15:21 +0900 |
| source 제목 | `feat(course): 지역별 코스를 눌러 상세로 간다 (#280 · S8-D) (#286)` |
| 빌드 종류 | release, R8·리소스 축소 적용, debuggable 아님 |
| **서명** | **없음 (`app-release-unsigned.apk`)** — 아래 「막힌 것」 참고 |
| 앱 ID / 버전 | `com.runninggu.app`, versionCode 1 / versionName 1.0 |
| API 주소 | `https://staging-api.runninggu.store/api/` |
| 빌드 JDK | 21 (`JAVA_HOME`으로 고정) |
| 빌드 시각 | 2026-09-05 16:26:34 +0900 |
| APK 크기 | 44,644,507 bytes |
| APK SHA-256 | `bf739a25891fcbc8c779028b975bd94b1021913347de7c58342b49205eb30887` |
| mapping.txt | 54,068,943 bytes (보관) |

`release`는 Android 빌드 종류다. `main` 머지·스토어 업로드·공개 배포는 하지 않았다.
**서명이 붙으면 APK SHA-256은 달라진다.** 위 값은 서명 전 산출물의 것이며, 전달용 값이 아니다.

## 커밋을 고정한 방법

최신 `develop`을 그대로 썼고 **머지되지 않은 PR을 섞지 않았다.** 빌드 시점에 열려 있던
PR은 아래 여섯 개이며 이 APK에 **포함되지 않는다.**

| PR | 내용 |
|---|---|
| #278 | 약관 내부 표시 가드 (Draft) |
| #283 | 홈 마감임박 오프라인 캐시 |
| #284 | 캐시 영역 표시 |
| #290 | 재로그인 마케팅 동의 복원 (#287) |
| #291 | 축제 동선 제외 근거 (SPEC) |
| #292 | 저장 후 편집 블록 API |

**#287(재로그인 후 마케팅 동의 표시)은 이 APK에 고쳐져 있지 않다.** 건모님이 계정 화면을
확인하실 때 그 증상이 그대로 나온다.

## 설정

`android/local.properties`(gitignore 대상)에 서버 주소 한 줄만 추가했다. 저장소 파일은
건드리지 않았다.

```properties
API_BASE_URL=https://staging-api.runninggu.store/api/
```

## 실행한 명령과 결과

```
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
cd android
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

```
BUILD SUCCESSFUL
테스트 클래스 103개 / @Test 821개 / 실패 0 · 오류 0 · 건너뜀 0
```

## 내용 검증

APK를 풀어 직접 대조했다.

| 확인 항목 | 결과 |
|---|---|
| APK 내부 서버 주소 | `https://staging-api.runninggu.store/api/` 확인 |
| 자리표시자 `api.runninggu.example` | 없음 |
| 로컬 주소 `10.0.2.2` · `localhost:8080` | 없음 |
| 패키지명 | `com.runninggu.app` |
| 버전 | versionCode 1 / versionName 1.0 |
| `android:debuggable="true"` | 없음 |
| **병합 Manifest 권한** | `INTERNET` · `ACCESS_NETWORK_STATE` · `DYNAMIC_RECEIVER_NOT_EXPORTED` 셋뿐 — **위치 권한 없음** |
| 약관 문서 | `v1.0`(tos·privacy·marketing) · `v1.1`(tos·privacy) · `v1.2`(privacy) 포함 |
| 팀 내부 `README.md` | 포함되지 않음 (`ignoreAssetsPatterns`) |
| R8 · 리소스 축소 | 적용됨, `mapping.txt` 생성 |

위치 권한 부재는 결정-56·이슈 #215가 요구하는 것이고, **매니페스트 파일이 아니라 병합
결과에서** 읽었다.

## 막힌 것 — 두 가지가 이 PC에 없다

### 1. 릴리스 keystore

```
산출물: app-release-unsigned.apk
apksigner verify → DOES NOT VERIFY
```

`android/local.properties`에 서명 항목 4개(`RELEASE_STORE_FILE` · `RELEASE_STORE_PASSWORD` ·
`RELEASE_KEY_ALIAS` · `RELEASE_KEY_PASSWORD`)가 없고, 이 PC에서 릴리스 keystore 파일을
찾지 못했다. `#232` 기록과 운영책임자 안내가 Windows 명령을 쓰는 것으로 보아 해당 키는
다른 PC에 있는 것으로 보인다.

`#108`에 등록 요청한 릴리스 키 해시는 `FQYBObeiwECqGowYN/kOhgJc9LY=` 다. 서명 후 같은 값이
나오는지 대조하면 동일 키 여부를 확인할 수 있다.

### 2. 카카오 네이티브 키

`local.properties`에 `KAKAO_NATIVE_APP_KEY`가 없어 **빈 값으로 빌드됐다.**

```kotlin
val kakaoNativeAppKey = localProperties.getProperty("KAKAO_NATIVE_APP_KEY").orEmpty()
buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"$kakaoNativeAppKey\"")
manifestPlaceholders["kakaoNativeAppKey"] = kakaoNativeAppKey
```

**이 상태로 서명만 붙여 전달하면 건모님 검증 항목인 카카오 로그인·지도가 둘 다 동작하지
않는다.** 서명보다 이쪽이 먼저 채워져야 한다. 이 키는 카카오 개발자 콘솔에서 확인할 수
있고 콘솔 소유자는 건모님이다(#104).

## 다음 단계

1. `KAKAO_NATIVE_APP_KEY`를 `local.properties`에 넣는다 (콘솔 소유자에게 확인)
2. 릴리스 keystore와 비밀번호 3종을 같은 PC에 준비한다
3. 같은 커밋 `77da13d`에서 `:app:testDebugUnitTest :app:assembleRelease` 재실행
4. 서명 확인 — `apksigner verify --print-certs`로 인증서 SHA-256과 키 해시 대조
5. `runninggu-staging-77da13d-release.apk`로 이름 붙여 전달물 묶음 작성

전달물에는 keystore 파일·비밀번호·네이티브 키를 넣지 않는다. 공개 이슈에도 올리지 않는다.

## 확정이 필요한 것

운영책임자가 남긴 항목이다. **최종 공개 앱에서도 이 스테이징 주소를 쓸지는 미확정이다.**
운영 주소가 따로 정해지면 APK를 다시 만들어야 한다. `#253`의 운영 `BASE_URL` 결정과 같은
줄기다.

관련: #253 · #108 · #250 · #287 · SPEC 결정-56
