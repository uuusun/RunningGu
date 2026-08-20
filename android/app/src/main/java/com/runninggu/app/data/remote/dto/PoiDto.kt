package com.runninggu.app.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * `GET /api/pois` 응답. (API 명세 §4-2)
 */
@Serializable
data class PoiSearchResponse(
    /** `LIVE` · `SAMPLE` · `SYNTH` — 소스 배지로 노출한다. (SPEC §6.3 · NFR-2) */
    val source: String,
    val items: List<PoiItemDto> = emptyList(),
)

@Serializable
data class PoiItemDto(
    val name: String,
    val category: String,
    val lat: Double,
    val lng: Double,
    val distanceM: Int = 0,
    val description: String = "",
    val address: String = "",
    val url: String = "",
    val imageUrl: String? = null,
)
