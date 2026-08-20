package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.model.PoiItem
import com.runninggu.app.data.model.PoiSearchResult
import com.runninggu.app.data.remote.dto.PoiItemDto
import com.runninggu.app.data.remote.dto.PoiSearchResponse

/** POI 응답 → 앱 모델. (API 명세 §4-2) */
fun PoiSearchResponse.toResult(): PoiSearchResult =
    PoiSearchResult(source = source, items = items.map { it.toModel() })

fun PoiItemDto.toModel(): PoiItem = PoiItem(
    name = name,
    address = address,
    description = description,
    lat = lat,
    lng = lng,
)
