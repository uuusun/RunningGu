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
 * 영역(section) 오류 문구. **없으면 null 이다.** (이슈 #260 · API 명세 §0-3)
 *
 * [userMessageOrDefault] 와 다른 점이 그것이다. 저쪽은 화면 하나가 통째로 실패했을 때
 * 쓰라고 만든 것이라 **반드시 문자열을 준다.** 영역은 다르다 — 홈에는 마감 임박과 축제가
 * 따로 있고, 화면이 영역마다 다른 기본 문구를 들고 있다.
 *
 *     section(state = uiState.closingSoon, errorMessage = "마감 임박 대회를 불러오지 못했어요")
 *     section(state = uiState.festivals,  errorMessage = "축제 정보를 불러오지 못했어요")
 *
 * ViewModel 이 [userMessageOrDefault] 로 "정보를 불러오지 못했어요." 를 채워 버리면
 * 화면의 `state.message ?: errorMessage` 에서 **오른쪽이 영원히 실행되지 않는다.**
 * 두 영역이 같은 문구를 내고, 어느 쪽이 죽었는지 화면에서 알 수 없게 된다.
 *
 * **네트워크는 따로 가른다.** 연결을 고쳐야 하는 것과 잠시 뒤 다시 눌러야 하는 것은
 * 사용자가 할 일이 다르다. 다른 화면들은 이미 그렇게 하고 있었고 홈만 빠져 있었다.
 */
internal fun ApiException.sectionMessage(): String? = when (this) {
    is ApiException.Network -> "네트워크에 연결할 수 없어요."
    // 서버가 준 문구가 있으면 그것, 없으면 **null 이라 영역 기본 문구가 산다**
    is ApiException.Http -> userMessage
    is ApiException.Malformed -> null
}
