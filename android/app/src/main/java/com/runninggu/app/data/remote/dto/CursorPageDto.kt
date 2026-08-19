package com.runninggu.app.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * 대회 목록 커서 페이징. (API 명세 §0-4)
 *
 * **대회 목록만** 불투명 커서를 쓴다. [nextCursor] 는 서버 내부 `(contestDate, id)` 를
 * Base64 로 감싼 값이라 **앱이 해석하지 않는다** — 다음 페이지 요청에 그대로 돌려준다.
 * `null` 이면 마지막 페이지다.
 */
@Serializable
data class CursorPageDto<T>(
    val items: List<T> = emptyList(),
    val nextCursor: String? = null,
)
