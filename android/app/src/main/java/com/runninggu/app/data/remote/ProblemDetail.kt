package com.runninggu.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * RFC 9457 `application/problem+json` 응답. (API 명세 §0-3)
 *
 * 서버가 오류를 이 모양으로만 내려주기로 확정했다(NFR-17). 앱은 [code] 로 분기하고
 * [title] 을 사용자에게 보여준다 — [detail] 은 개발자용이라 화면에 그대로 쓰지 않는다.
 */
@Serializable
data class ProblemDetail(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
    /** 부록 D 의 안정적인 코드. 화면 분기는 이걸로 한다. */
    val code: String? = null,
    /** 서버 로그 추적용. 오류 보고에 같이 남긴다. */
    val traceId: String? = null,
    /** Bean Validation 실패 시 필드별 사유. (§0-3) */
    val errors: List<FieldError> = emptyList(),
) {
    @Serializable
    data class FieldError(
        @SerialName("field") val field: String,
        val reason: String,
    )
}
