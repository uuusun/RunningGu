# 스테이징 준비를 위한 승인 PR 통합 — 2026-09-07

상태: **승인된 PR 9개 머지 완료, 최종 통합 Android·백엔드 CI 성공**.

사용자의 “가능한 PR 통합은 이어서 진행해” 요청에 따라 실행했다. 다른 팀원의 PR 코드는
작성자만 수정한다는 지시를 지켰으며, #311은 작성자의 수정·재검증을 기다린다.
이번 실행은 PR 통합 범위다. EC2 기동·배포·APK 제작·계정 변경·팀 전달은 실행하지 않았다.

## 통합 결과

- 시작 develop: `31f3af777571c4707a02300c6518dfc4cbe82ef0`
- 통합 develop: `395c1e39e7c28a8445d350603d1d53c5758684fa`
- 실행 시각: 2026-09-07 19:57:36~20:00:28 KST

| 순서 | PR | 승인받은 head | Squash 커밋 | 포함 내용 |
|---|---|---|---|---|
| 1 | [#295](https://github.com/uuusun/RunningGu/pull/295) | `01ab1488` | `a53e2be8` | 과거 스테이징 APK 검증 증거 |
| 2 | [#296](https://github.com/uuusun/RunningGu/pull/296) | `6d087565` | `aaa3f351` | 서명 APK 기기 검증 기록 틀 |
| 3 | [#303](https://github.com/uuusun/RunningGu/pull/303) | `dd95a91e` | `53eaa9da` | 개인정보처리방침 웹 초안 |
| 4 | [#304](https://github.com/uuusun/RunningGu/pull/304) | `e1efe830` | `457e644f` | 비활성 PRIVACY 1.2 사실관계 보완 |
| 5 | [#299](https://github.com/uuusun/RunningGu/pull/299) | `870ba205` | `dca3a0b5` | OSM 노면·환경·경사 측정 도구 |
| 6 | [#290](https://github.com/uuusun/RunningGu/pull/290) | `49b621ed` | `72feff88` | 재로그인 후 마케팅 동의 복원 |
| 7 | [#301](https://github.com/uuusun/RunningGu/pull/301) | `34ead708` | `2c4065e8` | 블록 추가·편집 요청 정규화와 빈 문자열 계약 |
| 8 | [#309](https://github.com/uuusun/RunningGu/pull/309) | `9ceb6c4e` | `b2c4cde8` | 캘린더·대회 상세 캐시 출처·시각 전달 |
| 9 | [#300](https://github.com/uuusun/RunningGu/pull/300) | `b722bca8` | `395c1e39` | 큐레이션 상세 지역 표시 수정 |

매 머지 직전에 develop 대상, 현재 head의 유효 승인, 수정 요청 없음, 필요한 CI 성공,
충돌 없음, Draft 아님을 확인했다. `--match-head-commit`으로 검토 이후 head가 달라진
PR의 머지를 방지했다. 선행 머지 직후 GitHub 상태가 UNKNOWN인 경우 재계산 결과를
확인한 뒤 진행했다. 관리자 우회 옵션은 사용하지 않았다.

머지 후 GitHub에서 9개 PR의 MERGED 상태, 승인받은 head 유지, 부모 1개인 Squash 커밋,
원격 head 브랜치 삭제를 되읽었다. 로컬에서는 `git fetch origin develop`만 실행해
통합 커밋을 받았으며, 기존 작업 폴더를 pull·reset·stash하거나 작업 브랜치를 바꾸지 않았다.

## 이전 리뷰 대화 정리

#290(2개), #296, #299, #300, #301, #303(2개)의 기존 선경 리뷰 대화 8개는 수정 후
정식 승인까지 완료됐으나 해결 표시만 남아 있었다. 동일 head의 코드·문서를 다시 대조해
해결 상태로 표시했다. #311 등 미해결 요청은 그대로 유지했다. 신규 수정 커밋이나
다른 리뷰어의 승인·수정 요청 변경은 하지 않았다.

## CI

| 실행 | 검사 커밋 | 결과 |
|---|---|---|
| [Android build and test](https://github.com/uuusun/RunningGu/actions/runs/34114369898) | `395c1e39` | 성공 — 단위 테스트·debug 빌드 |
| [Backend build and test](https://github.com/uuusun/RunningGu/actions/runs/34114369868) | `395c1e39` | 성공 — 단위·통합 검사·bootJar |
| [Data validation](https://github.com/uuusun/RunningGu/actions/runs/34114313959) | `dca3a0b5` | 성공 |

#300을 마지막에 머지해 같은 최종 커밋의 Android·백엔드 push CI가 실행됐다.
중간 커밋의 Android·백엔드 실행은 기존 `cancel-in-progress: true` 설정에 따라
뒤의 통합 실행으로 대체됐다. Data validation은 마지막 데이터 관련 변경 #299의 결과이며,
`dca3a0b5..395c1e39` 사이 scripts·워크플로 변경이 없음을 확인했다.
로컬 기기 검증이나 실서버 검증은 이번 통합에서 수행하지 않았다.

백엔드 CI는 20:03:52 KST에 성공으로 완료됐다. 같은 커밋의
`runninggu-backend-395c1e39e7c28a8445d350603d1d53c5758684fa` artifact가 생성됐고,
GitHub 조회 기준 ID는 `10015735128`, 크기는 119,563,428 bytes, 만료 상태는 false다.
이번에는 artifact를 내려받거나 서버에 배포하지 않았다. push 실행에서 적용 대상이 아닌
Spec sync와 PR 전용 묶음 job의 skipped는 실패가 아니다.

## 남은 PR과 다음 단계

| PR | 보류 이유 |
|---|---|
| #311 | 건모의 기존 수정 요청 반영·재검증 대기. head `e9c92809` 유지. 선행 #301은 통합 완료 |
| #313 | 선경 PR. 기존 head CI는 성공했으나 팀원 승인 없음. 이번 통합 후 `docs/agreements/v1.2/privacy.md`에서 충돌 발생 |
| #302 · #305 · #312 | 작성자의 기존 수정 요청 반영 대기 |
| #278 | Draft이며 의도한 활성 약관 검사에서 Android CI 실패 |

현재 통합본에는 #311의 저장 후 편집 화면이 없다. 따라서 전체 기능 시험 APK의
최종 소스로 확정하지 않는다. #311 수정·승인·머지 뒤 통합 CI를 확인하고 APK·배포 준비를
재개한다. #309는 캐시 출처 전달 범위이며 #307의 후속 Compose 안내 완료를 뜻하지 않는다.
웹 초안 머지는 공개 게시나 약관 활성화가 아니며, OSM 도구 머지로 graph를 재생성하지 않았다.
