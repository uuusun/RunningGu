package com.runninggu.app.ui.model

import com.runninggu.app.data.model.Festival
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * `data/model` 의 축제를 홈 캐러셀 항목으로 옮긴다. (API 명세 §4-1 · AP-14)
 *
 * 화면은 [FestivalSummary] 만 알고 [Festival] 을 모른다 — 날짜를 문자열로 만드는 일도
 * 여기서 끝낸다([ContestUiMapper] 와 같은 방식이다).
 */
fun Festival.toFestivalSummary(): FestivalSummary = FestivalSummary(
    id = contentId,
    name = name,
    region = region,
    period = periodLabel(startDate, endDate),
    isOngoing = inProgress,
)

/**
 * "08.01~08.09" 표기. (목업 · `SampleData.festivals` 와 같은 형식)
 *
 * **날짜가 없을 수 있다.** 서버 DTO 는 문자열이지만 KTO 원본이 비어 있거나 형식이 깨지면
 * 매퍼가 null 로 떨군다(`FestivalMapper`) — 항목을 버리지 않기로 한 계약이라 여기까지 온다.
 *
 * 한쪽만 있으면 있는 쪽만 쓰고, 둘 다 없으면 "기간 미정" 이다. **빈 문자열을 주지 않는다** —
 * 화면이 `"{기간} · {지역}"` 으로 잇기 때문에 빈 값이 오면 " · 부산" 처럼 보인다.
 */
private fun periodLabel(start: LocalDate?, end: LocalDate?): String = when {
    start != null && end != null -> "${start.format(PERIOD_FORMAT)}~${end.format(PERIOD_FORMAT)}"
    start != null -> "${start.format(PERIOD_FORMAT)}~"
    end != null -> "~${end.format(PERIOD_FORMAT)}"
    else -> "기간 미정"
}

private val PERIOD_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.dd")
