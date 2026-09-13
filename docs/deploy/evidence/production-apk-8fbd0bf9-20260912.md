# 운영 서명 APK 재빌드 — `8fbd0bf9` · 2026-09-12

09-11 최종본(`42bfec46`) 이후 앱 코드를 바꾸는 PR 세 건이 머지되어 다시 만들었다.
**기기 검증은 하지 않았다** — 아래 「완료하지 않은 것」을 먼저 본다.

## 빌드 식별

| 항목 | 값 |
|---|---|
| 용도 | 운영 주소 최종 후보본. 09-11 `42bfec46` 을 대체한다 |
| 앱 소스 | `8fbd0bf9a6a6d9c89c5cf4200e449e89c192c4e7` (`develop` HEAD) |
| 작업 디렉터리 | 세션 스크래치패드의 detached 워크트리 (소스 수정 없음) |
| APK | `.cache/release-artifacts/20260912-final-8fbd0bf9/runninggu-production-8fbd0bf9.apk` |
| 크기 | `44,725,433` bytes |
| 파일 SHA-256 | `5f04e114fb6b04139c9b3b52a1f7ce6af87f363b9c11449e12aa9aaa17c2f2c1` |
| applicationId | `com.runninggu.app` |
| versionCode / versionName | `1` / `1.0` — 09-11 회차와 같다. **올리지 않았다** |
| targetSdk / minSdk | `36` / `26` |
| API 주소 | 운영(`api.runninggu.store`). DEX 에서 주입 확인, 값은 기록하지 않는다 |
| 인증서 SHA-256 | `41073757d098062b234c83695c04bd4753640fe8da86bb1f2ba3a2b2fb734fc7` |
| 카카오 키 해시 | `oRJtsESChTHqkP9fnp4lRq+wNxM=` — 09-05 출시 키와 동일 |
| 서명 검증 기록 | 같은 폴더의 `apksigner-verification.txt` |
| R8 매핑 | 같은 폴더의 `mapping.txt` (54,440,787 B) · SHA-256 `637223262c743b686dd8d534e97b75b72d4f6c6dcb07892d62dc979965205316`. `seeds.txt` 도 함께 둔다 |

## 빌드가 재현된다

같은 커밋·같은 도구로 **한 번 더 빌드해 정렬한 결과가 최초 빌드와 바이트 단위로 같았다.**

```
최초 빌드 정렬본  eb4f99ed5ff99249d211a5ff2790ebf557881df5c136dc2376b2be726b61bad6
재빌드   정렬본  eb4f99ed5ff99249d211a5ff2790ebf557881df5c136dc2376b2be726b61bad6
```

재빌드를 한 이유는 첫 빌드의 워크트리를 지우면서 `mapping.txt` 를 먼저 빼내지 않은 실수 때문이다.
R8 난독화가 켜져 있어(`isMinifyEnabled = true`) 매핑이 없으면 이 APK 의 크래시 스택을 되돌릴 수 없다.

**해시가 같으므로 위 `mapping.txt` 는 이미 서명해 둔 APK 의 매핑이다.** 재서명하지 않았다.
반대로 해시가 달랐다면 매핑을 못 믿으므로 다시 서명해야 했다.

## 09-11 `42bfec46` 대비 무엇이 들어갔나

| PR | 머지 | 내용 |
|---|---|---|
| [#345](https://github.com/uuusun/RunningGu/pull/345) | 09-12 13:40 | 원스토어 지식재산권 증빙 캡처 보강 — 문서만, APK 무관 |
| [#344](https://github.com/uuusun/RunningGu/pull/344) | 09-12 15:47 | 마이 > 계정 관리에 오픈소스 라이선스 화면. OFL 1.1 전문과 폰트 고지를 asset 으로 번들 |
| [#342](https://github.com/uuusun/RunningGu/pull/342) | 09-12 16:54 | S7 저장 바가 `BottomActionBar` 를 다시 쓰게 해 3버튼 내비에 가리던 것을 고침 (#266 재발) |

`86599438`(09-11 20:23, 원스토어 증빙 문서)도 09-11 APK 이후 머지됐지만 `docs/store/` 뿐이라 APK 에 영향이 없다.

## 실제 검증

- JDK 21(Android Studio JBR 21.0.10)에서 `:app:testDebugUnitTest :app:assembleRelease --no-daemon --console=plain` 성공(2분 53초).
  **113개 테스트 클래스, @Test 932개, 실패 0 · 오류 0 · 건너뜀 0.**
- Gradle 은 서명 없이 산출했다(`local.properties` 에 `RELEASE_*` 를 넣지 않음). SDK build-tools 36.0.0 으로
  `zipalign -p -f 4` → `zipalign -c 4` 통과 → `apksigner sign` → `apksigner verify --verbose --print-certs` 성공.
  서명 뒤에도 `zipalign -c 4` 를 다시 통과했다. R8 · 리소스 축소가 적용된 산출물이다.
- 서명 비밀번호는 기존 CurrentUser DPAPI 보관본을 **운영자가 직접** 프로세스 환경변수로만 복원해 쓰고 제거했다.
  키 파일을 복사·업로드하지 않았고 `local.properties` 에 서명 비밀번호를 기록하지 않았다.
- 인증서·공개키 SHA-256, 키 알고리즘, 키 길이를 09-11 회차 `apksigner-verification.txt` 와 대조해 **차이 0** 을 확인했다.
  카카오 키 해시도 인증서 SHA-1 에서 다시 계산해 09-05 출시 키와 같음을 확인했다.
- APK 권한: `INTERNET`, `ACCESS_NETWORK_STATE`, `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
  **위치 권한 없음**(SPEC 결정-56 · #215).
- `debuggable` 없음. 패키지·버전 확인.
- DEX 에서 `api.runninggu.example` · `10.0.2.2` · `localhost:8080` · `staging-api` **각 0건**, 운영 주소 주입 1건.
- #344 자산이 실제로 실렸다 — `assets/licenses/OFL-1.1.txt`(5,017 B). 폰트 상표 고지 2줄(Source · Archivo) 포함.
- 팀 내부 문서가 새어 나가지 않았다 — `assets/README.md` 0건(`ignoreAssetsPatterns` 동작 확인).

## 완료하지 않은 것

- **기기 검증을 하지 않았다.** 설치·실행·화면 확인 전부 미실행이다.
  특히 이번 빌드의 핵심인 **#342 S7 저장 바 위치는 정적 점검으로 확인되지 않는다** — 3버튼 내비 기기에서 직접 봐야 한다.
- **#342 는 API 30 에서만 기기 확인이 되어 있다**(해당 PR 본문). `targetSdk = 36` 이고 API 35+ 는 edge-to-edge 가
  강제되어 inset 해석이 다르므로, **API 36 3버튼 기기 확인이 남아 있다**([#342 리뷰](https://github.com/uuusun/RunningGu/pull/342#issuecomment) 1번 항목).
- **#344 오픈소스 라이선스 다이얼로그도 이 빌드에서는 열어보지 않았다.** 작성자가 API 30 에서 확인한 기록이 PR 본문에 있다.
- `versionCode` 를 올리지 않았다. 스토어 업로드 전에 올릴지 팀에서 정해야 한다.
- 스토어 업로드, 팀원 배포, 서버 배포는 하지 않았다.

## 팀 시험 전달본

`.cache/release-artifacts/20260912-team-8fbd0bf9/runninggu-team-test-20260912-8fbd0bf9.zip` (22,205,866 B).
09-08 회차와 같은 구성이다 — APK · `.idsig` · `INSTALL-WINDOWS.cmd` · `README.md` · `TEST-CHECKLIST.md` ·
`SHA256SUMS` · 서명 검증 기록. `mapping.txt` 는 크기가 커서 ZIP 에 넣지 않고 산출물 폴더에만 둔다.

- 카카오 지도 `.so`(`libK3fAndroid.so`)가 `arm64-v8a` · `armeabi-v7a` 에만 있어
  **x86_64 AVD 기본 설치로는 지도가 조용히 실패한다.** 설치 도우미가 `--abi arm64-v8a` 로 설치해 이를 피한다.
  09-08 [Windows AVD ARM 설치 재검증](windows-avd-arm-install-20260908.md)과 같은 조건이다.
- **이번 전달본은 운영 서버를 본다.** 09-08 것은 스테이징이었다. 팀원이 가입하면 운영 DB 에 실제 계정이 생기므로
  README 첫 절에 경고를 두었다. 심사 계정(#293)·표본 데이터를 건드리지 않도록 안내한다.
- 파일 전달은 운영 담당자가 직접 비공개로 한다. 이 작업에서 외부로 파일을 보내지 않았다.

## 원스토어 제출 시 올릴 파일

`runninggu-production-8fbd0bf9.apk` **하나**다. `-aligned-unsigned.apk`(서명 전 중간물) · `.idsig`(v4 부산물) ·
`mapping.txt` 는 업로드 대상이 아니다.

- 형식은 **APK** 다. AAB 가 아니다 🔒확정(#253 · 2026-09-03 · 가이드 534행). 원스토어 앱 서명은 쓰지 않는다.
- 원스토어가 재서명하지 않으므로 사용자 기기의 인증서 = 우리 릴리스 키이고, 카카오 키 해시도 그 하나가 기준이다.
- target API 최소 33(2026-09-01~) 대비 `36` 으로 충족한다.
- **`versionCode` 가 `1` 이다.** 업로드마다 증가시켜야 하므로(가이드 554행) 기존 업로드 이력이 있으면 올려야 한다.

## 다음 할 일

1. 3버튼 내비 · **API 36** 기기에서 S7 [이 동선 저장하기] 가 내비바 위로 올라오는지 확인
2. 마이 > 계정 관리 > 오픈소스 라이선스 진입 · 스크롤 · [닫기] 확인
3. 결과를 [릴리스 APK 검증 기록](../../release/release-apk-verification.md)에 회차로 추가
4. 원스토어 제출 전 `versionCode` 를 올릴지 팀에서 결정
