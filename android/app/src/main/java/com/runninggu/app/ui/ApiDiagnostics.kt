package com.runninggu.app.ui

import com.runninggu.app.data.remote.ApiException

/**
 * 실패의 **개발자용 신원**. 화면 문구와 따로 남긴다. (이슈 #252 · API 명세 §0-3)
 *
 * `saveMessage()` 는 사용자에게 서버가 준 `title` 을 보여 준다. 그런데 **정상
 * `problem+json` 은 `title` 과 `code` 를 함께 준다** — `title` 만 쓰면 `code` 와
 * `traceId` 는 앱에서 사라진다(#254 리뷰). 그 둘이 서버 로그와 앱을 잇는 유일한 끈이다.
 *
 * 화면에 올리지 않는 이유는 `VALIDATION_FAILED` 같은 값이 사용자에게 뜻이 없어서다.
 * 대신 [apiFailureLogger] 로 흘려 logcat 에 남긴다.
 *
 * ## 무엇을 담고 무엇을 안 담나
 *
 * `status` · `code` · `traceId` · 실패한 **필드 이름**까지다.
 *
 * `problem.detail` 과 `errors[].reason` 은 **넣지 않는다.** 서버가 거절 사유에 사용자가
 * 넣은 값을 되비출 여지가 있고, 로그에 남기면 안 되는 것 목록(AGENTS 8장)과 부딪힐 수
 * 있다. 필드 이름만으로도 "어디가 문제냐" 는 답이 나온다 — #245 가 그랬다.
 */
internal fun ApiException.diagnostic(): String = when (this) {
    is ApiException.Http -> buildString {
        append("HTTP ").append(status).append(' ').append(code.name)
        problem?.traceId?.let { append(" trace=").append(it) }
        problem?.errors
            ?.takeIf { it.isNotEmpty() }
            ?.let { violations ->
                append(" fields=").append(violations.joinToString(",") { it.field })
            }
    }

    is ApiException.Network -> "network unavailable"
    is ApiException.Malformed -> "malformed response: ${cause?.javaClass?.simpleName ?: "unknown"}"
}

/**
 * 진단 문구를 흘려보내는 자리. **기본은 아무 일도 안 한다.**
 *
 * `android.util.Log` 를 곧바로 부르면 JVM 단위 테스트가 터진다 — 이 저장소에는
 * `unitTests.isReturnDefaultValues` 가 없어서 `Log.w` 가 "not mocked" 로 예외를 던진다.
 * 그렇다고 그 옵션을 켜면 **모든** `android.*` 호출이 조용히 기본값을 돌려주게 되어,
 * 진짜로 잡아야 할 실수까지 통과한다.
 *
 * 그래서 자리만 비워 두고 [com.runninggu.app.RunningGuApplication] 이 기기에서 실제
 * 로거를 꽂는다. 단위 테스트는 Application 을 안 띄우므로 기본값 그대로다 —
 * 필요하면 테스트가 직접 갈아 끼워 무엇이 남는지 볼 수도 있다.
 */
internal var apiFailureLogger: (String) -> Unit = {}
