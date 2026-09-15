# 운영 배포 — `42bfec46` · 2026-09-11

## 무엇을 했나

운영 백엔드를 `a2c77006`에서 **`42bfec46`으로 올렸다.** 앱(최종 서명 APK)이 이미 `42bfec46`
소스인데 서버만 뒤처져 있어 생긴 두 가지를 함께 닫았다.

| 반영된 것 | 효과 |
|---|---|
| `14af1486` (#334 · 결정-65) 동선 추천 품질 | 동선 라벨·골격이 계약대로 바뀜 |
| `42bfec46` (#278) 활성 약관 `1.1 · 1.2 · 1.1` | 앱 번들 문안과 서버 기록 버전이 일치 |

약관 전환은 활성화 순서 3단계를 앞당긴 것이다. **앱이 아직 어디에도 공개되지 않아 현장에
구버전 앱이 없고, 심사에 올릴 APK 도 이미 `1.1/1.2/1.1`을 번들하고 있어** 지금 맞추는 편이
기록 정합에 유리하다고 판단했다(2026-09-11 운영책임자 결정).

## 배포 전 확인

- `develop` push CI run **`34563591250`** 이 success 이고 artifact
  `runninggu-backend-42bfec4653b00c3b05d5076d6c5d98abcfeecc4b` 가 만료되지 않았다.
- `a2c77006 → 42bfec46` 사이 **DB 마이그레이션 변경이 없다.** 배포 스크립트·compose 변경도 없다.
  실제 코드 변경은 `ItineraryGenerator.java`와 `application.yml` 두 개다.
- 롤백 경로가 남아 있다 — `/opt/runninggu/releases/a2c7700671f6c9ca60c67687b9e7863cfeb144a6`.

## 전달과 검증

EC2 role 의 S3 권한은 GraphHopper prefix 와 백업 bucket 뿐이라 백엔드 artifact 경로가 없다.
IAM 을 바꾸지 않고 **15분 만료 presigned URL** 로 전달했다. GitHub token 은 EC2 에 넣지 않았다.

```text
번들 크기   119,416,585 bytes
번들 SHA256 e60405f2d1a0fd1753bdcfe3e0d1fccb032d9c05d602ee7ab27643e3f1cb3d54
            (로컬·EC2 동일)
SHA256SUMS  runninggu-server.jar · runninggu-contest-import.jar ·
            data/contest_snapshot.json · release-manifest.txt 모두 OK
manifest    git_commit=42bfec4653b00c3b05d5076d6c5d98abcfeecc4b
            workflow_run_id=34563591250
```

런북 §9 대로 `install`로 읽기 전용 설치하고(`root:runninggu`, 디렉터리 0750 · 파일 0440)
설치본에서 checksum 을 다시 검증했다. 서버 저장소도 같은 commit 으로 detached checkout 했고
clean 을 확인했다.

## 전환과 결과

```text
current     releases/a2c77006…  →  releases/42bfec4653b0…
서비스      active · NRestarts=0 · 2026-09-11 09:54:06 UTC
내부 API    GET /api/contests?size=1 → 200 (재기동 약 16초 뒤)
공개 API    /api/contests · /api/geocode · /api/courses/near 모두 200
걷기 스팟   서울시청 ROUTE 1 + PLACE 11 = 12건 유지
```

**앱에서 #334 반영을 확인했다.** 같은 서명 APK(`42bfec46`)로 동선을 다시 생성했다.

| 블록 | 배포 전 | 배포 후 |
|---|---|---|
| D-1 둘째 | `카보로딩 저녁` · 탄수화물 보충 · 무리 없는 메뉴 | **`대회 전날 저녁`** · 속 편한 메뉴로 가볍게 |
| 마지막 날 체크아웃 | `체크아웃·귀가` (맨 끝) | **`숙소 체크아웃`** · 짐 정리하고 나서기 (점심 앞) |

S7 지도도 정상이다 — 번호 핀·연결선·지형 타일·`kakao` 워터마크.

## 이번에 드러난 동의 이력 문제

### ① 시험 계정의 동의 이력이 실제로 본 문안과 다르다

배포 전 운영 DB `user_agreement` 는 다음과 같았다.

```text
TOS 1.0 · PRIVACY 1.0 · MARKETING 1.0   각 1건
최근 changed_at  2026-09-11 09:21:55+00
```

그 시각은 3회차 검증에서 **카카오 로그인으로 신규 가입한 시점**이다. 그때 앱 A2 는
`이용약관 1.1` · `개인정보 수집·이용 1.2` · `마케팅 정보 수신 1.1` 전문을 보여줬는데 서버는
`1.0` 으로 기록했다. **이 계정 1건의 동의 이력은 사용자가 실제로 본 문안과 다르다.**

배포로 서버가 `1.1/1.2/1.1` 이 됐으므로 **이후 신규 가입은 일치한다.** 다만 이미 남은 1건은
그대로다. 해당 계정을 탈퇴시키면 삭제된다(#241 에서 DB 삭제 확인). 탈퇴는 3회차 10번 항목이기도
하니 함께 처리하면 된다.

### ② 서버가 앱이 보여준 버전을 검증하지 않는다

가입 요청에 약관 버전 필드가 없다. 서버가 자기 설정값을 그대로 찍는다.

```java
// EmailAuthService.java:154 · KakaoAuthTransaction.java:116
agreementProperties.tosVersion()
```

`application.yml` 의 `runninggu.auth.agreements.*-version` 은 **하드코딩**이고 환경변수가
아니다. 그래서 배포 범위가 곧 활성 버전이며, 앱과 서버 버전이 어긋나도 **오류 없이 조용히**
서버 값으로 기록된다. NFR-12 가 막으려는 상태가 배포 순서만으로 지켜진다.

활성화 순서를 지키면 실제 위험은 없지만 가드가 없다는 사실은 남는다. 앱이 본 버전을 보내고
서버가 대조하는 계약을 둘지는 별도 판단이며 이 기록에서 정하지 않는다.

## 남은 것

- 시험 계정 `user_agreement` 1건 정리(탈퇴)
- 활성화 순서 4단계(앱 공개)와 5단계(신규 가입 세 버전·같은 `changedAt` 확인)
- 임시 S3 객체(`backend-deploy/tmp-42bfec46-*.tgz`) 삭제
