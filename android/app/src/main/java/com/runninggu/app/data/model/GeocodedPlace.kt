package com.runninggu.app.data.model

/**
 * 검색으로 찾은 출발지 한 곳. (SPEC §4.11-1 ② · API 명세 §4-4)
 *
 * 화면은 이걸 [com.runninggu.app.ui.course.OriginState.Fixed] 로 바꿔 쓴다.
 */
data class GeocodedPlace(
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
)
