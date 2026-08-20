package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.model.Festival
import com.runninggu.app.data.remote.dto.FestivalDto
import com.runninggu.app.data.remote.dto.FestivalListDto
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 축제 응답 → 앱 모델. (API 명세 §4-1)
 *
 * 서버가 정한 순서(진행 중 우선 · 시작일 오름차순)를 **다시 정렬하지 않는다**.
 * 날짜가 깨진 항목도 버리지 않는다 — 이름과 지역만으로도 카드는 그릴 수 있다.
 */
fun FestivalListDto.toDomain(): List<Festival> = items.map { it.toDomain() }

fun FestivalDto.toDomain(): Festival = Festival(
    contentId = contentId,
    name = name,
    startDate = startDate.toLocalDateOrNull(),
    endDate = endDate.toLocalDateOrNull(),
    region = region,
    imageUrl = imageUrl?.takeIf { it.isNotBlank() },
    // 진행 중 판정은 서버 몫이다 (§4-1)
    inProgress = inProgress,
)

private fun String.toLocalDateOrNull(): LocalDate? =
    if (isBlank()) null else try { LocalDate.parse(this) } catch (e: DateTimeParseException) { null }
