# 재로그인 후 마케팅 수신 OFF 표시 — 읽기 전용 진단

> **로그아웃·재로그인은 성공했지만 마케팅 설정 표시 유지 시험은 실패했다.**
> 서버의 최신 동의는 ON이며 재로그인 시 Android 세션 프로필에 기본값 false가 들어간다.
> 원인 조사와 기록만 했으며 제품 코드·계약·서버 설정은 수정하지 않았다.
> 후속 운영책임자 요청으로 [이슈 #287](https://github.com/uuusun/RunningGu/issues/287)에 민지를 태그해 게시했다.
> 아래는 최초 진단 구간이다. 앱 재시작 후 GET `/me` 성공은 [후속 증거](api-readonly-boundaries-20260904.md)로 별도 확인했다.

## 사용자 보고와 서버 증거

사용자가 계정 관리에서 마케팅 수신 ON → 로그아웃 → 같은 계정 로그인 → 계정 관리 OFF 표시를
보고했다. 재로그인 후 내 정보 표시도 확인했다고 보고했으나 에이전트가 앱 화면을 직접 본 것은 아니다.

- 앱·서버 기준 source: `b9e632d768c99f05f4028545b7e265817038d9c8`.
- SSM: `e4308c34-768b-4671-81ea-8a12eb8565ba`, **Success / response 0**.
- 점검: `2026-09-04T09:56:58.043782Z`. EC2 current source 일치, 백엔드·GraphHopper·Docker·Nginx active.
- SQL은 READ ONLY·statement timeout 5초. 설치 이후 생성 회원의 건수만 출력했다.
  이메일·닉네임·회원 ID·비밀번호·코드·토큰·해시 원문은 결과에 포함하지 않았다.

| 확인 | 결과 |
|---|---|
| 전체 회원 / 설치 이후 회원 | 1 / 1 |
| 마케팅 이력 | 3행. 최신값은 changed_at DESC, id DESC 기준 ON 1명·OFF 0명 |
| Refresh Token 행 | 3행 중 revoked 2, 활성 1 |
| `PATCH /api/me/agreements` | 200 × 3 |
| `POST /api/auth/logout` | 204 × 2 |
| `POST /api/auth/login` | 200 × 2, 401 × 1 |
| `GET /api/me`, `POST /api/auth/refresh` | 관측 구간에 0건 |

HTTP 관측 구간은 09:36 UTC 이후다. 마지막 흐름은 09:52:40 PATCH 200 → 09:52:46 logout 204 →
09:52:54 login 401 → 09:53:01 login 200이다. 401의 입력값·본문은 읽지 않았으므로 비밀번호
오타 등 원인을 단정하지 않고 실패 1건을 그대로 보존한다. 최종 로그인 성공이 이 실패를 지우지는 않는다.

`GET /me`가 없으므로 사용자의 **내 정보 화면 표시 확인**과 **인증된 GET /me 성공**을 구분한다.
후자는 아직 실측하지 않았다. 이 명령의 Success는 조회 명령 성공이지 마케팅 기능 합격이 아니다.

## 계약과 코드 대조

- API 명세 §2: `PATCH /me/agreements`가 값을 저장하고 GET/PATCH `/me` 응답의
  `agreements.marketing`은 최신 이력이다. 같은 값 PATCH는 이력을 추가하지 않는 멱등 200이다.
- 화면/API 매핑표 §10 D-36·SPEC 결정-59: P0는 선택 동의 상태만 유지하며 실제 마케팅 메일은 보내지 않는다.
  발송을 하지 않는다는 정책이 설정을 OFF로 초기화하라는 뜻은 아니다.
- `AccountViewModel.kt:215`의 토글은 `RemoteMemberRepository.updateMarketing`을 호출하고
  서버 응답을 세션에 반영한다. `MeMapper.kt:20`은 `agreements.marketing`을 올바르게 옮긴다.
- `RemoteAuthRepository.kt:188`의 로그인 응답 → SessionProfile 매핑에는 마케팅 값이 없다.
  인증 응답의 AuthUserDto는 계약대로 약관 필드를 포함하지 않는 요약 정보다.
- `SessionStore.kt:41`의 `marketingAgreed` 기본값은 false다. 따라서 로그인 성공 시 만든
  프로필은 기존 서버 동의값과 무관하게 OFF를 갖는다.
- `LoginViewModel.kt:94`는 그 프로필을 그대로 `SessionStore.signIn`에 넣는다.
- `AccountViewModel.kt:135`의 초기화는 SessionStore 구독뿐이며 최신 `/me` 조회가 없다.
  그래서 계정 관리의 OFF는 서버가 OFF로 바뀐 결과가 아니라 미조회 값을 false로 표시한 결과다.
- 시작 시 복원 검증의 `ApiSessionValidator`는 GET `/me` → MeMapper를 사용하지만, 이 경로가
  명시적 재로그인 직후에도 호출되는 것은 아니다. 앱 재시작으로 표시가 바뀌는지는 이번에 시험하지 않았다.

위 경로는 배포 source를 해제한 사본으로 확인했다. 카카오 기존 회원 로그인 매핑도 기본값을
사용하는 구조이나 카카오 실기 재현은 하지 않았으며 동일 현상 실측으로 세지 않는다.

## 수정 제안과 경계

민지 담당 앱 코어의 계정 프로필 조회·세션 반영 경로가 수정 대상이다. 기존 GET `/me` 계약으로
로그인 뒤 또는 계정 관리 진입 시 최신 동의를 가져오고, 조회 전/실패를 OFF 동의로 확정 표시하지
않아야 한다. 조회 실패 처리와 늦은 응답의 세션 세대 검증도 함께 검토한다. 새 로그인 응답 필드나
DB 스키마를 임의로 추가할 이유는 없다. 정확한 UiState·조회 시점 변경은 담당자와 확인 후 구현한다.

회귀 시험은 ON/OFF 각각에 대해 변경 성공 → 로그아웃 → 재로그인 → 최신 조회 → 표시 일치를
검증하고, 조회 실패·계정 전환 중 늦은 응답도 포함한다. 기존 단위 테스트 통과를 이 연결 경로의
검증으로 간주하지 않는다. 이번에는 코드 수정·새 테스트 실행·issue/PR 게시·commit/push를 하지 않았다.

계정 설정 기능 전체를 합격으로 표시하지 않는다. 이 표시 버그는 EC2 RAM 부족이나 SES 설정
실패의 증거가 아니며 8GiB/4GiB 판정과 구분한다. 전체 API 혼합 부하는 여전히 미실행이다.

후속 진행에서는 운영책임자 요청에 따라 이 결함을 미해결로 유지하고 관련 없는 검증을 재개했다.
마케팅 토글·동의 이력은 수정하거나 재시험하지 않았다.
