package com.runninggu.app.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * 개인 목록 페이징. (API 명세 §0-4)
 *
 * 마이·찜·저장 코스처럼 **내 것만 보는 목록**은 Spring Pageable 을 쓴다
 * — `?page=0&size=20`, `createdAt DESC, id DESC`, 최대 50.
 */
@Serializable
data class PageDto<T>(
    val content: List<T> = emptyList(),
    val page: PageMeta = PageMeta(),
) {
    @Serializable
    data class PageMeta(
        val number: Int = 0,
        val size: Int = 0,
        val totalElements: Long = 0,
        val hasNext: Boolean = false,
    )
}
