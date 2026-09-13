# 정식 재리뷰 — 2026-09-07

사용자 요청 “재확인해서 수정 요청 및 승인 리뷰 다 남겨”에 따라 `uuusun` 계정으로 정식 리뷰 8건을 게시했다. 게시 직전에 각 PR이 열려 있고 검토한 head와 같은지 확인했다. 게시 후에는 GitHub에서 리뷰를 되읽어 **종류·커밋·본문·작성자**가 준비한 내용과 일치하는지 검증했다.

## 새로 게시한 리뷰

아래 판정은 선경의 리뷰 상태다. 다른 리뷰어의 판정이나 머지 가능 상태를 대신하지 않는다.

| PR | 검토 head | 정식 리뷰 | 근거 |
|---|---|---|---|
| #290 | `49b621ed` | [승인](https://github.com/uuusun/RunningGu/pull/290#pullrequestreview-5130634872) | 해당 head의 Pixel 8 기기 4항목 기록으로 기존 검증 보류 해소. 최종 출시본 검증은 별도 |
| #295 | `01ab1488` | [승인](https://github.com/uuusun/RunningGu/pull/295#pullrequestreview-5130635260) | 과거 APK 증거와 새 키·주소 등 현재 전제를 구분. 과거 수치가 새 APK 검증을 대체하지 않음을 명시 |
| #296 | `6d087565` | [승인](https://github.com/uuusun/RunningGu/pull/296#pullrequestreview-5130634132) | 앱 #286과 서버 #297의 포함·배포 여부를 분리해 판정하도록 보완 |
| #300 | `b722bca8` | [승인](https://github.com/uuusun/RunningGu/pull/300#pullrequestreview-5130634560) | 지역 표시의 두 입력 형태 지원과 API 문서 예시·설명 보완 |
| #309 | `9ceb6c4e` | [승인](https://github.com/uuusun/RunningGu/pull/309#pullrequestreview-5130676388) | Room부터 repository·UiState까지 출처와 원래 저장 시각 보존. 전체 테스트·디버그 빌드 통과 |
| #305 | `ef8db21a` | [수정 요청](https://github.com/uuusun/RunningGu/pull/305#pullrequestreview-5130636628) | 목록/상세 계약 오류는 해결. 이번에 저장한 고유 ID의 실제 되읽기 대조·중복·부족 판정은 미구현 |
| #311 | `e9c92809` | [수정 요청](https://github.com/uuusun/RunningGu/pull/311#pullrequestreview-5130635690) | 날짜 전환 중 응답 오적용과 추가 행 정규화 누락 재현. 진행 상태·매핑표도 보완 필요 |
| #312 | `d64d8d2d` | [수정 요청](https://github.com/uuusun/RunningGu/pull/312#pullrequestreview-5130636024) | 원스토어 공식 영업일 최대 5일·예외 연장 안내 및 승인/배포 구분 반영. 문의는 건모가 발송하도록 요청 |

## 같은 head의 기존 리뷰 유지

이 PR들은 현재 head와 선경의 기존 정식 리뷰 커밋이 같아서 중복 게시하지 않았다.

| PR | 현재 head | 유지한 리뷰 |
|---|---|---|
| #299 | `870ba205` | [승인](https://github.com/uuusun/RunningGu/pull/299#pullrequestreview-5127132512) |
| #301 | `34ead708` | [승인](https://github.com/uuusun/RunningGu/pull/301#pullrequestreview-5127406032) |
| #303 | `dd95a91e` | [승인](https://github.com/uuusun/RunningGu/pull/303#pullrequestreview-5127406140) — 개인정보 페이지 기술 구현 범위 |
| #304 | `e1efe830` | [승인](https://github.com/uuusun/RunningGu/pull/304#pullrequestreview-5127406241) — 비활성 약관 초안의 사실관계 수정 범위 |
| #302 | `e196524c` | [수정 요청](https://github.com/uuusun/RunningGu/pull/302#pullrequestreview-5129580599) — WBS 심사 기간 미확인 표기·#310 이후 4GiB 상태 정정 |

#278은 `973a75cf`의 Draft이며 기존 [COMMENTED 리뷰](https://github.com/uuusun/RunningGu/pull/278#pullrequestreview-5124333592)를 유지했다. 활성 약관 내부 표시 때문에 실패하도록 만든 검증 PR이므로 초안 해제나 승인으로 바꾸지 않았다.

## #309 검토와 실행 결과

- 서버 성공은 `cachedAt=null`/`DataOrigin.Server`, 캐시 복원은 원래 저장 시각/`LocalCache`로 전달된다.
- 목록의 시각은 복원 가능한 행 중 가장 오래된 값이다. 깨진 행의 시각은 제외한다.
- 캘린더 월간 집계·인근 축제 실패는 목록·상세 본문 출처와 독립적이다.
- HTTP 계약·Room 스키마 변경은 없다. Compose 표시와 기기 확인은 후속 UI PR 범위다.
- 찜 하트 정책은 매핑표 공통 오프라인 행의 **쓰기 비활성**으로 이미 정해져 있음을 답변했다. 후속 UI 연결과 S2·S3 문서 보완, 기기 확인 후 #307을 닫도록 남겼다.

검토 복사본: `.cache/review-formal-20260907/pr309/`, JDK `liberica-21.0.11`.

```text
:app:testDebugUnitTest :app:assembleDebug --offline --no-daemon --console=plain --max-workers=2
최종 결과: BUILD SUCCESSFUL
테스트 클래스 107개 / @Test 878개 / 실패 0 · 오류 0 · 건너뜀 0
```

최초 실행은 Android 폴더만 추출하면서 `docs/agreements`를 빠뜨려 약관 테스트 5개가 실패했다. 최초 XML을 `pr309-test-results-initial/`에 보존했고, **같은 head의 약관 문서만 복원**한 뒤 전체 테스트와 빌드를 다시 실행했다. 생산 코드·테스트 수정이나 제외 없이 통과했다. 작성자의 과거 간헐 실패 원인까지 규명한 것은 아니다.

최종 로그는 `.cache/review-formal-20260907/pr309-build-complete-inputs.log`, 집계는 `pr309-tests-summary.json`에 있다.

## 나머지 검증과 범위

여섯 PR의 상세 재현·근거는 [앞선 검토 기록](2026-09-07-six-pr-rereview.md)에 있다. #311은 같은 head의 분리 사본에서 기존 7개가 통과하고 추가 재현 3개가 실패한 결과를 사용했다. #305는 오프라인 테스트 22개를 두 번 실행한 결과와 ID 검증 반례를 사용했다. #295는 이전 승인 `6ad8e1fa` 이후 문서 diff를 확인했으며 문서만 바뀌어 실행 테스트는 하지 않았다.

기기·실서버 호출은 이번 정식 재리뷰에서 실행하지 않았다. 기기 판정에는 기존 식별된 기록을 사용했다. 앱·서버 원본 코드와 사용자의 미커밋 변경을 보존했고, 메일 발송·커밋·push·머지는 하지 않았다.
