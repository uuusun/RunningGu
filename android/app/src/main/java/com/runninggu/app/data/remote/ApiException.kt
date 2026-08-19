package com.runninggu.app.data.remote

import java.io.IOException

/**
 * 앱이 다루는 API 실패. Repository 가 잡아서 화면의 Error 상태로 옮긴다.
 *
 * 화면은 네 상태(Loading/Content/Empty/Error)를 구분해야 하므로(§0-3),
 * **정상 빈 결과는 예외가 아니다** — 빈 배열은 그냥 성공이고 화면이 Empty 로 판단한다.
 */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 서버가 problem+json 으로 알려준 실패. */
    class Http(
        val status: Int,
        val code: ApiErrorCode,
        val problem: ProblemDetail?,
    ) : ApiException("HTTP $status ${code.name}") {

        /** 게스트가 로그인이 필요한 일을 시도했다 — "로그인이 필요해요" 모달. (§0-2) */
        val needsLogin: Boolean
            get() = status == 401

        /** 외부 API 쪽 실패. 재시도가 의미 있는 자리다. (NFR-3~5) */
        val isExternal: Boolean
            get() = code == ApiErrorCode.EXTERNAL_API_ERROR ||
                code == ApiErrorCode.EXTERNAL_API_TIMEOUT

        /** 사용자에게 보여줄 문구. 서버 title 이 없으면 화면이 기본 문구를 쓴다. */
        val userMessage: String?
            get() = problem?.title
    }

    /** 네트워크가 아예 안 됐다. 오프라인 폴백을 켤 자리다. (NFR-1) */
    class Network(cause: IOException) : ApiException("network unavailable", cause)

    /** 응답을 해석하지 못했다 — 계약 불일치이므로 그냥 삼키면 안 된다. */
    class Malformed(cause: Throwable) : ApiException("malformed response", cause)
}
