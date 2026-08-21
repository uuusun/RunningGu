package com.runninggu.app.data.model

/** 화면이 쓰는 장소 항목. */
data class PoiItem(
    val name: String,
    val address: String,
    val description: String,
    val lat: Double,
    val lng: Double,
)

/**
 * 목록 key.
 *
 * **서버가 유일성을 보장하는 조합과 같아야 한다.** 서버는 정규화한 이름과 **좌표**가 같은
 * 항목을 지운다(API 명세 §4-2). 이름+주소로 만들면 서버 중복 제거를 통과한 항목 둘이
 * 앱에서 같은 key 가 되어 `LazyColumn` 이 터진다 — 주소는 원천에 값이 없으면 빈 문자열이라
 * 이름만 같아도 겹친다.
 *
 * POI 는 안정적인 `placeId` 가 없다. 동선에 snapshot 으로 저장하므로 서버가 두지 않는다.
 */
val PoiItem.listKey: String get() = "$name|$lat|$lng"

/** 조회 결과 + 소스 배지. */
data class PoiSearchResult(val source: String, val items: List<PoiItem>)
