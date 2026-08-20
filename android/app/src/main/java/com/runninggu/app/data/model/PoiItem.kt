package com.runninggu.app.data.model

/** 화면이 쓰는 장소 항목. */
data class PoiItem(
    val name: String,
    val address: String,
    val description: String,
    val lat: Double,
    val lng: Double,
)

/** 조회 결과 + 소스 배지. */
data class PoiSearchResult(val source: String, val items: List<PoiItem>)
