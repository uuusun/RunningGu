# Windows AVD의 ARM 설치로 카카오 지도 확인 — 2026-09-08

후속 두 계정 기능 시험과 갱신한 전달 ZIP은 [실제 서명 APK 검증 기록](team-apk-fullcheck-20260908.md)을 따른다. 아래 기록은 ARM 설치 확인 당시 상태다.

사용자가 민지·건모 모두 Windows 에뮬레이터를 사용한다고 알려줘 동일 APK의 설치 ABI를 재검증했다. **Windows AVD에서도 ARM 변환 모드로 설치하면 실제 카카오 지도·핀·러닝 경로선을 표시한다.** 앞선 "지도 시험에 ARM64 실기기/AVD가 필수" 안내를 정정한다.

앱 코드·의존성·서명·서버는 변경하지 않았다. 다른 팀원 담당 코드를 수정하지 않았다. 제품 명세·API 계약·DB 변경은 없다.

## 원인과 검증 환경

- APK: `runninggu-staging-ccab0e0a.apk`, SHA256 `0db9fb888fb733f949791224b612e8b0636151c98a9c7867299558521a8a72df`.
- Windows, Android Emulator `36.6.11.0` (build 15507667), 별도 `RunningGu_Team_20260908` AVD.
- Pixel 10 Pro / Android 17 / API 37 / Google Play 16KiB x86_64 이미지. SDK 이미지 경로 `system-images/android-37.1/google_apis_playstore_ps16k/x86_64`.
- `ro.product.cpu.abilist=x86_64,arm64-v8a`, `ro.dalvik.vm.native.bridge=libndk_translation.so`.
- 기본 설치: `primaryCpuAbi=x86_64`. 카카오 지도 `.so`는 ARM용이므로 `EM_AARCH64 instead of EM_X86_64` 오류 후 지도 실패 안내로 전환했다.
- ARM 지정 설치: `primaryCpuAbi=arm64-v8a`, `secondaryCpuAbi=null`. 같은 APK의 ARM 라이브러리를 AVD의 변환 기능으로 실행한다.

Android 공식 [ARM 앱 실행 안내](https://android-developers.googleblog.com/2020/03/run-arm-apps-on-android-emulator.html)는 Google APIs·Google Play 이미지의 ARM 변환을 설명한다. [카카오 공식 SDK 요구사양](https://apis.map.kakao.com/android_v2/docs/getting-started/)은 `armeabi-v7a`·`arm64-v8a`를 지원한다. 일부 이미지·SDK 조합의 일반 가능성과 이 APK의 실제 검증 결과를 구분한다.

## 실행과 실제 화면

```powershell
adb -s emulator-5556 install --no-incremental -r --abi arm64-v8a runninggu-staging-ccab0e0a.apk
adb -s emulator-5556 shell dumpsys package com.runninggu.app
adb -s emulator-5556 shell am start -n com.runninggu.app/.MainActivity
```

설치 성공과 ARM ABI를 확인했다. 앱에서 게스트 진입 → 러닝코스 → 서울시청 프리셋을 선택했다.

```
기기  Windows / Pixel 10 Pro AVD / Android 17 API 37 / x86_64 + ARM64 변환
전    기본 설치는 지도 영역에 "지도를 불러오지 못했어요. 목록으로 확인해 주세요." 표시
후    서울시청·종각역·명동 일대 실제 지도 타일, 카카오 로고, 파란 순환 경로선과 핀 표시
      경로 카드에 6.17km·약 56분·완만·상승 116m 표시, 서울광장 등 장소 목록 표시
```

이 화면 확인은 카카오 로그인이나 두 팀 계정의 비밀번호 로그인을 대신하지 않는다. 저장·복원·저장 후 편집·계정 간 분리는 팀에서 추가 시험한다. 모든 Windows AVD가 ARM 변환을 지원한다고 보장하지 않으며 설치 도우미가 해당 AVD의 `arm64-v8a` 지원을 먼저 검사한다.

## 전달 도우미

`.cache/release-artifacts/20260908-team-ccab0e0a/INSTALL-WINDOWS.cmd`를 제공한다.

- AVD만 대상으로 하며, 여러 AVD가 있으면 임의 선택하지 않고 중단한다. 선택한 serial을 인자로 받을 수 있다.
- ARM 지원 확인 → 같은 APK를 `--abi arm64-v8a`로 설치 → 실제 `primaryCpuAbi` 확인 → 앱 실행.
- 기존 앱·사용자 데이터·기존 AVD를 삭제하지 않는다. 서명 불일치면 새 AVD 사용을 안내한다.
- 자동 선택에 AVD 2개가 보이는 경우 중단(exit 1)을 확인했다.
- 테스트 AVD를 명시해 2회 실행했고 두 번 모두 설치 성공·ARM ABI 확인·앱 실행(exit 0)을 확인했다.

기존 전달 ZIP에는 ARM 변환을 확인하기 전 안내가 들어 있으므로 후속 `runninggu-team-test-windows-20260908-ccab0e0a.zip`을 전달한다. APK 바이너리는 동일하다. 원래 개인 AVD `emulator-5554`의 앱·데이터는 보존했다.

최종 ZIP은 `.cache/release-artifacts/20260908-team-ccab0e0a/runninggu-team-test-windows-20260908-ccab0e0a.zip`, 22,319,528 bytes, SHA256 `50c1d8062f3e51fe018e05ba7f8d5a7f522c602d8175e1620b4457f723adf75a`다. ZIP 내부는 APK·README·체크리스트·Windows 설치 도우미·버전 정보·SHA256SUMS의 6개 파일이며 5개 파일의 해시를 ZIP 스트림에서 다시 확인했다. 마지막 HTTPS 대회 API는 200이었고 검증용 AVD만 종료했다. EC2 중지나 배포 변경은 수행하지 않았다.
