package com.runninggu.app.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * 대회 인근 축제 응답. (API 명세 §3-5)
 *
 * 빈 배열은 **정상**이다 — "대회 기간에 열리는 인근 축제가 없어요" 빈 상태로 그린다.
 * 실패(`502`)와 구분해야 해서 여기서 예외로 바꾸지 않는다.
 */
@Serializable
data class NearbyFestivalListDto(
    val items: List<NearbyFestivalDto> = emptyList(),
)

@Serializable
data class NearbyFestivalDto(
    val contentId: String,
    val name: String,
    val startDate: String,
    val endDate: String,
    /** 대회장 기준 직선거리. **서버가 Haversine 으로 계산한다**(§8.3) — 앱은 다시 재지 않는다. */
    val distanceKm: Double,
    val imageUrl: String? = null,
    val address: String = "",
)
