package com.runninggu.app.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * 홈 축제 섹션 응답. (API 명세 §4-1)
 *
 * 서버가 KTO `searchFestival2` 를 호출·캐시한다 — **앱은 우리 서버만 부른다**(AGENTS 2장-3).
 * 홈은 위치 권한과 사용자 좌표를 쓰지 않고 조회 월만 보낸다.
 */
@Serializable
data class FestivalListDto(
    val items: List<FestivalDto> = emptyList(),
)

@Serializable
data class FestivalDto(
    /** KTO `contentId`. 목록 key 로 쓴다. */
    val contentId: String,
    val name: String,
    val startDate: String,
    val endDate: String,
    val region: String = "",
    val imageUrl: String? = null,
    /** `start <= 오늘 <= end`. **서버가 판정한다** — 앱이 다시 계산하지 않는다. */
    val inProgress: Boolean = false,
)
