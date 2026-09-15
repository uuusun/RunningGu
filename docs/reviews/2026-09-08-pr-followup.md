# 건모 요청 PR 처리 — 2026-09-08

최신 head·본문·댓글·정식/인라인 리뷰·diff·명세·CI를 대조했다. 사용자 요청에 따라 재리뷰를 게시하고, 선경의 #313 수정 요청은 전용 작업 공간에서 수정·커밋·push했다.

| PR | head | 처리 결과 |
|---|---|---|
| #311 | `840453ad` | [선경 승인](https://github.com/uuusun/RunningGu/pull/311#pullrequestreview-5136184017). 완료 후 진행·실패 안내와 후보 선택 수락 가드 해결. 작업 중 추가된 민지의 화면 검증 요청 때문에 PR 전체는 CHANGES_REQUESTED 유지 |
| #302 | `ee83c60c` | [승인](https://github.com/uuusun/RunningGu/pull/302#pullrequestreview-5136184101). WBS 심사 소요일·4GiB 상태 정정 |
| #305 | `205d352f` | [승인](https://github.com/uuusun/RunningGu/pull/305#pullrequestreview-5136184226). 저장 ID·중복·부족 대조와 후속 페이지 조회 보완 |
| #312 | `74b47807` | [수정 요청](https://github.com/uuusun/RunningGu/pull/312#pullrequestreview-5136184336). 09-12 권장일 근거 제거 및 #302와 일정 충돌 해소 필요 |
| #314 | `9117c6eb` | [수정 요청](https://github.com/uuusun/RunningGu/pull/314#pullrequestreview-5136184450). 여백 수정 확인. #307에 포함된 오프라인 찜 쓰기 제어 미구현 |
| #278 | `973a75cf` | [검토 댓글](https://github.com/uuusun/RunningGu/pull/278#pullrequestreview-5136184585). 최종 문안·앱/서버 활성 버전 전환 전이므로 Draft 유지 |
| #313 | `94fa9f68` | [수정·push 완료](https://github.com/uuusun/RunningGu/pull/313), 충돌 해소 확인, 민지에게 재리뷰 요청 |

게시한 리뷰 6건은 GitHub에서 되읽어 종류·대상 commit·본문·작성자 `uuusun` 일치를 확인했다. #311의 선경 인라인 리뷰 1개, #302의 4개도 수정 내용을 확인한 뒤 resolved로 바꿨다.

## 검증

- **#311 원본 head:** JDK 21, `:app:testDebugUnitTest :app:assembleDebug --offline --no-daemon --console=plain --max-workers=2` 성공. 클래스 111개 / @Test 922개 / 실패·오류·건너뜀 0.
- **#311 별도 재현:** 이전 `ReviewSavedItineraryEditTest`를 최신 head에 적용. 클래스 1개 / @Test 14개 전부 통과. 날짜 전환 4연산·추가 제목 정규화·무시된 후보 선택 시트 유지·완료 후 지연 실패 상태 확인. 재현 코드는 `.cache/pr-followup-20260908/pr311/`에만 추가했다.
- **#305:** 분리 사본의 unittest 28개를 두 번 실행해 모두 통과. 같은 개수의 다른 ID·저장 ID 반복·후속 페이지 검사를 확인했다. 10페이지 제한에 도달했을 때 조회 한도를 구분하는 것은 비차단 보완으로 남겼다.
- **#302:** 이전 지적과 변경 문서·현재 develop 대조, diff 공백 검사 통과. 원스토어 공식 안내 재확인.
- **#312:** 공식 소요일·배포 옵션 보완은 확인. `git merge-tree --write-tree ee83c60c 74b47807`은 가이드 일정 부분의 충돌을 보고했다. 새 블록의 09-12 근거가 준비 즉시 제출·09-11 공개 목표와 맞지 않아 수정 요청을 유지했다.
- **#314:** diff·화면→ViewModel→FavoriteStore 호출 경로·공통 오프라인 계약 대조. CI 성공과 작성자의 여백 수정 기기 관찰은 확인했으나, 캐시 출처에서 찜 쓰기를 막는 코드가 없다.

## #313 수정

작업 공간은 `.codex-worktrees/nginx-log-retention-14d`, 브랜치는 기존 `chore/nginx-log-retention-14d`다. 깨끗한 상태에서 최신 develop을 병합했고, 충돌한 PRIVACY 1.2에 #304의 저장 동선·숙소 좌표 설명과 #313의 목적·14일 보관을 함께 보존했다. 강제 push는 하지 않았다.

공개 처리방침·약관 초안·웹 README·실행서·증적을 함께 수정했다. HTTP·HTTPS 앱 host의 접속 로그는 질의 문자열 제외가 적용되지만, 오류·전역 접속 로그는 같은 보장을 할 수 없음을 명시했다. 이것을 개인정보 로그 금지 원칙 준수 완료로 간주하지 않으며, 공개 전 별도 조치·검증이 남는다.

HTML 검사에서는 lxml의 기존 HTML5 태그 진단과 비교해 추가 진단이 없음을 확인했다. section 14개, 표 열 수, 내부 앵커, 상대 파일 링크 27개가 유효하다. 활성 약관 설정·기존 nginx/logrotate 런타임 설정·draft/noindex를 보존했다. 변경분 공백 검사와 미해결 충돌 검사도 통과했다.

새 커밋 CI의 Spec sync, 단위·PostgreSQL 통합 테스트·bootJar, PR 검증용 백엔드 묶음은 모두 통과했다. PR 본문을 최종 변경과 검증으로 다시 작성하고 민지에게 재리뷰를 요청했으며 [수정 요청 대응 댓글](https://github.com/uuusun/RunningGu/pull/313#issuecomment-5577359299)도 게시했다.

## 한계와 남은 작업

기기·실서버는 이번 작업에서 실행하지 않았다. #311 최신 완료 후 지연 실패 표시의 기기 관찰은 아직 완료되지 않았다는 작성자 기록을 유지하고 #296 복구·오류 검증에 남겼다. #305 승인도 실제 심사 계정 표본 생성이나 실서버 멱등성 승인이 아니다.

**#311 최종 상태 보완:** 09-08 09:40 KST에 [민지의 추가 수정 요청](https://github.com/uuusun/RunningGu/pull/311#pullrequestreview-5136173542)이 올라왔다. VM 테스트로는 Compose 표시 조건을 지키지 못하므로 기기 확인·계측 또는 #296에 구체 경로를 명시하고 PR 본문에 한계를 쓰는 방법 중 하나를 요구했다. 고아 주석·안내 배치 설명도 요청했다. 선경의 코드를 기준으로 한 승인은 유지했지만 다른 리뷰어의 요청을 해제하지 않았으며, APK 배포 차단이 모두 풀렸다고 보고하지 않는다.

#278은 활성 1.0 문안의 내부 표시 때문에 실패하는 가드이며, 문안·필요 결정·활성 버전 동시 전환이 선행해야 한다. #313의 초안 수정만으로 Draft를 해제하지 않았다. 원본 작업 공간의 사용자 미커밋 변경은 보존했다. PR 머지·APK 배포·EC2 기동·메일 발송은 수행하지 않았다.
