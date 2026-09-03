package com.runninggu.app.ui

import com.runninggu.app.data.remote.ApiException

/**
 * 서버가 준 문구가 있으면 그걸 쓰고, 없으면 기본 문구. (API 명세 §0-3)
 *
 * `detail` 은 개발자용이라 화면에 올리지 않는다.
 *
 * 화면 여럿이 같은 판단을 하므로 `ui/` 에 둔다 — 원래 `ui/course` 에 있었는데 캘린더도
 * 같은 게 필요해졌다. 화면끼리 서로를 import 하게 두면 의존 방향이 엉킨다.
 */
internal fun ApiException.userMessageOrDefault(): String =
    (this as? ApiException.Http)?.userMessage ?: "정보를 불러오지 못했어요."

/**
 * `ApiException` 이 아닌 실패의 저장 문구. (이슈 #252)
 *
 * **[com.runninggu.app.ui.course.saveMessage] 의 어느 갈래와도 달라야 한다.** 예전에는
 * 이 문구와 `saveMessage()` 의 기본 문구가 글자 하나까지 같아서, 화면만 보고는 "서버가
 * 거절했다" 와 "앱 안에서 깨졌다" 를 가릴 수 없었다 — #245 를 사흘 동안 못 찾은 이유다.
 *
 * **상수로 뽑은 이유가 그 불변 때문이다.** 두 ViewModel(S7·S8)에 문자열을 각각 적어 두면
 * 한쪽만 고쳐질 수 있고, 테스트가 자기 사본과 비교하게 되어 겹침을 못 잡는다.
 */
internal const val SAVE_FAILED_OUTSIDE_CONTRACT = "앱에서 저장을 마치지 못했어요. 다시 시도해 주세요."

/**
 * 오프라인 안내. (이슈 #274)
 *
 * **[SAVE_FAILED_OUTSIDE_CONTRACT] 와 정반대의 불변이다** — 그쪽은 "달라야 한다" 라서
 * 뽑았고, 이쪽은 **"어디서나 같아야 한다"** 라서 뽑는다. 연결이 끊겼을 때 사용자가 할
 * 일은 화면마다 다르지 않다. 이유는 똑같다 — 여러 곳에 적으면 한쪽만 고쳐진다.
 *
 * 실제로 갈렸다. `develop` 에 세 표현이 13곳에 흩어져 있었고,
 *
 * ```
 * "네트워크에 연결할 수 없어요."                       3곳  CourseViewModel
 * "네트워크에 연결할 수 없어요"                        4곳  Login · Account   ← 마침표가 없다
 * "네트워크에 연결되지 않았어요. 연결을 확인해 주세요."   6곳  Login · Reset · Signup
 * ```
 *
 * `LoginViewModel` 은 **한 파일 안에서** 둘을 쓰고 있었다 — [카카오로 시작하기] 와
 * [로그인] 이 같은 오프라인에 다른 말을 했다. 마침표 하나가 갈린 것도 아무도 못 봤다.
 *
 * ## 왜 짧은 쪽인가
 *
 * "연결을 확인해 주세요" 를 붙인 긴 쪽이 친절해 보이지만, **홈 섹션 오류처럼 좁은 자리**
 * (마감임박 · 축제)에서는 두 줄로 접힌다. 한 문구가 모든 자리에 서려면 짧은 쪽이어야 한다.
 * 그리고 연결을 확인하라는 말은 이미 알고 있는 것을 한 번 더 하는 것에 가깝다.
 */
internal const val OFFLINE = "네트워크에 연결할 수 없어요."
