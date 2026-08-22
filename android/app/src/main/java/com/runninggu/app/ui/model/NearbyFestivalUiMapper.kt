package com.runninggu.app.ui.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.runninggu.app.data.model.NearbyFestival as DataNearbyFestival

/**
 * `data/model` 의 인근 축제를 화면 모델로 옮긴다. (API 명세 §3-5 · AP-14)
 *
 * 필드는 같지만 **날짜를 다루는 층이 다르다** — 화면은 `LocalDate` 를 그대로 그리므로,
 * 없을 때 무엇을 보여줄지는 [nearbyFestivalPeriod] 한 곳에서 정한다.
 */
fun DataNearbyFestival.toNearbyFestival(): NearbyFestival = NearbyFestival(
    contentId = contentId,
    name = name,
    startDate = startDate,
    endDate = endDate,
    distanceKm = distanceKm,
    imageUrl = imageUrl,
    address = address,
)

/**
 * "08.20~08.24" 표기. 없는 쪽은 비우고, 둘 다 없으면 "기간 미정" 이다.
 *
 * **빈 문자열을 주지 않는다** — 화면이 `"{기간} · 대회장 1.2km"` 로 이어 붙이기 때문에
 * 빈 값이 오면 " · 대회장 1.2km" 처럼 보인다.
 */
fun nearbyFestivalPeriod(start: LocalDate?, end: LocalDate?): String = when {
    start != null && end != null -> "${start.format(PERIOD_FORMAT)}~${end.format(PERIOD_FORMAT)}"
    start != null -> "${start.format(PERIOD_FORMAT)}~"
    end != null -> "~${end.format(PERIOD_FORMAT)}"
    else -> "기간 미정"
}

private val PERIOD_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.dd")
