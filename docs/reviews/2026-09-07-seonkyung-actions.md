# 선경 담당 실행 결과 — 2026-09-07

추가 정책 결정 없이 진행할 수 있는 기술 리뷰, 시험 APK 제작, 출시 현황 정정과 검토 자료 준비를 수행했다. **4GiB 검증은 진행 중으로 유지했고 EC2 접속·부하 추가·재시작·배포는 하지 않았다.**

## 1. GitHub에 제출한 리뷰

제출 직전에 각 PR의 head를 다시 확인하고 정확한 커밋에 리뷰를 연결했다.

| PR | 커밋 | 결과·근거 |
|---|---|---|
| [#299](https://github.com/uuusun/RunningGu/pull/299#pullrequestreview-5127132512) | `870ba205` | **승인.** CLI·JSON에 노면/환경/경사 5개 지표가 남고 null/0 구분. 관련 스크립트 26테스트 2회 통과 |
| [#301](https://github.com/uuusun/RunningGu/pull/301#pullrequestreview-5127132408) | `23961aa2` | **수정 요청.** 빈 장소명 정규화가 PATCH에서 필드 생략으로 바뀌어 기존 이름은 남고 좌표만 변경되는 회귀 재현 |
| [#302](https://github.com/uuusun/RunningGu/pull/302#pullrequestreview-5127132735) | `452c82c2` | **수정 요청.** 이전 WBS·이미지 규격 보완은 확인. §8의 R8/서명/백엔드/배포 상태와 남은 Play 전제를 최신화하도록 요청 |
| [#303](https://github.com/uuusun/RunningGu/pull/303#pullrequestreview-5127132661) | `b7731bff` | **수정 요청.** 기존 IP 파일/동선 좌표 누락 해소. 미구현 이동시간 목적과 전체 로그 미기록 단정 정정 필요 |
| [#304](https://github.com/uuusun/RunningGu/pull/304#pullrequestreview-5127132580) | `41f5c1d2` | **첫 기술 리뷰·수정 요청.** #303과 동일한 사실 정정. 법률 적합성·공개/활성화 승인과 구분 |

#301 리뷰 재현은 격리 작업 디렉터리의 `BlockPatchWireReviewTest`로 수행했다. 기존 정규화 테스트 8개는 통과, 새 와이어 재현 1개만 실패했다. 실제 JSON은 `{"lat":37.5,"lng":127.0}`으로 placeName이 없다. 서버 §5-8은 생략을 기존 값 유지로 해석한다. 앱 소유 코드는 수정하지 않았고, 재현 테스트는 `.codex-worktrees/review-pr301-20260907`에만 남겼다.

#303·#304의 로그 지적은 HTTPS access_log와 error_log/HTTP/전역 설정의 차이다. nginx는 요청 오류 기록에 원래 요청행과 upstream URI를 넣을 수 있다([공식 소스](https://github.com/nginx/nginx/blob/master/src/http/ngx_http_request.c#L3891-L3955), [error_log 문서](https://nginx.org/en/docs/ngx_core_module.html#error_log)). 실제 서버에서 개인정보 유출을 확인했다고 보고한 것은 아니다. 운영 로그를 확인하거나 보유 기간을 임의 확정하지 않았다.

#300·#296은 기존 수정 요청 이후 head가 그대로라 같은 지적을 중복 제출하지 않았다. #295 과거 APK 증거 승인과 #278 Draft/활성화 조건도 유지했다.

### 마무리 중 올라온 #303·#304 후속 수정

#304 `fbaa8b13`, #303 `9e3a7dc7`를 추가 fetch해 대조했다. **이동시간 목적은 해결**됐고 오류/HTTP 로그 확인 조건도 추가됐다. 다만 뒤의 안전성 조치 절에 모든 로그의 이메일·좌표 미기록 단정이 남아 있어 기존 수정 요청을 유지했다. “평상시”를 확인된 HTTPS access_log로 한정하고, HTTP 블록은 전역 형식 상속 여부 미확인으로 쓰도록 후속 댓글을 남겼다([#304](https://github.com/uuusun/RunningGu/pull/304#issuecomment-5563384770), [#303](https://github.com/uuusun/RunningGu/pull/303#issuecomment-5563384904)). 실제 HTTP/오류 로그와 보존 설정은 별도 운영 확인 잔여다.

## 2. #290 시험 APK 완료

**소스 `49b621ed`, 새 출시 키, staging 주소를 고정한 서명 APK를 제작했다.**

- 파일: `.cache/release-artifacts/20260907-pr290-49b621ed/runninggu-staging-pr290-49b621ed-newkey.apk`
- SHA-256: `8730B371DC39F0C4D2C92DC153C898617633E1D90AF2B7F78FDA05F8AF5000B5`
- 단위 테스트 **108클래스·881개 통과**, release 빌드 성공. zipalign·서명·인증서·staging 주소·네이티브 키 주입·위치 권한 없음 확인.
- [상세 검증·기기 시험 안내](../deploy/evidence/staging-apk-pr290-49b621ed-20260907.md), [#290 전달 준비 기록](https://github.com/uuusun/RunningGu/pull/290#issuecomment-5563344461).

키 파일·비밀번호를 업로드하거나 출력하지 않았다. **팀원의 파일 수신·기기 설치는 미확인**이고, 부하 시험 종료·서버 개방 후 기기 검증이 남아 #290의 기존 보류를 유지했다. 같은 APK에서 사용한 앱 커밋과 실제 서버 커밋은 별개다.

## 3. 백엔드 재검증

| 실행 | 결과 |
|---|---|
| JDK 21 `test bootJar --offline --no-daemon --console=plain` | **실패. 429개 중 173개 실패.** Docker 엔진 파이프 부재로 PostgreSQL Testcontainers 초기화 실패 |
| `test --tests '*SpringMailVerificationMailSenderTest' bootJar --offline --no-daemon --console=plain` | **성공. 메일 단위 테스트 5개 통과, bootJar 최신 출력 유지** |

Docker Desktop 시작을 시도했으나 엔진 연결이 복구되지 않았다. 사용자 변경 코드를 되돌리거나 테스트를 삭제/무시하지 않았다. [SES 회신·검증 기록](../deploy/ses-production-access-reply-20260906.md)에 이번 결과를 추가했다. 전체 테스트 통과·SES 승인·변경 메일 실제 수신·서버 반영은 완료가 아니다.

## 4. 현황·문서 정리

- [#225](https://github.com/uuusun/RunningGu/issues/225): 제목·본문을 현재 출시 게이트로 재작성. 기존 “키 없음/R8 비활성/common·contest만 구현”을 실제 검증 잔여로 교체하고 오프라인 추적 누락·새 리뷰 결과를 연결.
- [#253](https://github.com/uuusun/RunningGu/issues/253): “결정 필요·전환 제안” 제목을 원스토어 실행 추적으로 변경. 상단에 현재 확정값을 추가하고 최초 제안 본문은 작성 당시 기록으로 보존.
- [#250](https://github.com/uuusun/RunningGu/issues/250#issuecomment-5563344607): 4GiB 진행 상태·기존 완료 범위·남은 판정 조건·로컬 테스트 환경 실패를 댓글로 구분.
- [#108](https://github.com/uuusun/RunningGu/issues/108#issuecomment-5563360039): 새 #290 APK·해시·기기 검증 경로를 연결. 이슈 종료 없음.
- [출시 지침 §8](../development-release-contest-guide.md): 같은 현황을 로컬 파일에도 반영. #302 담당자가 흡수할 수 있는 사실 정정이며 미커밋 상태다.
- [#195 공식 검토 자료 초안](2026-09-07-location-review-packet.md): 현재 위치/저장 좌표/IP 흐름, 기존 회신과 달라진 전제, 질문·제출 보완자료를 작성. 외부 발송 없음.
- [기능설명서 내용·KTO 증빙 초안](../contest/2026-09-07-functional-description-content-draft.md): 핵심 흐름 5개, KTO endpoint→화면/처리 방식/기존 실측 근거, 최종 화면·양식·PDF 작업 잔여를 정리. 공식 양식 본문은 웹 도구로 열지 못했으므로 최종 양식 적용 완료로 보고하지 않음.

## 5. 다음 담당과 선행조건

| 담당 | 남은 실행 |
|---|---|
| 민지 | #301 PATCH 회귀 수정, #302·303·304 문안 정정, 계정 캐시·GPX/초기 번들 계약 잔여 추적 |
| 건모 | #213 저장 후 편집 UI, #300 API 예시 정합성, #296 앱/서버 식별 구분, 서버 개방 후 새 APK 검증 |
| 선경 | 진행 중인 4GiB 판정·운영 배포 조건, 정상 Docker 환경 전체 테스트, SES 승인 확인/실수신, nginx 전체 로그·보유/삭제 정책, 공식 검토·최종 문안 |
| 공동 | 최종 릴리스 통합·심사 계정 표본·원스토어 등록/공개·기능설명서 최종 PDF·제출 리허설 |

미정 로그 보유 기간·국외 처리·시행일·고지 기간을 새로 정하지 않았다. 기존 계약을 바꾸거나 다른 담당자의 구현을 대신하지 않았고, 커밋·push·PR 생성·머지·배포·약관 공개/활성화도 하지 않았다. 기존 미커밋 변경 4파일과 출시 자료를 보존했다.
