package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.model.NearbyFestival
import com.runninggu.app.data.remote.dto.NearbyFestivalDto
import com.runninggu.app.data.remote.dto.NearbyFestivalListDto
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 대회 인근 축제 응답 → 앱 모델. (API 명세 §3-5)
 *
 * 서버가 기간·반경 40km 필터와 **거리순 정렬**까지 끝내서 준다(§8.3). 앱은 순서를 다시
 * 만들지 않고 거리도 다시 재지 않는다 — 두 벌 계산이 생기면 카드 순서와 라벨이 갈린다.
 */
fun NearbyFestivalListDto.toDomain(): List<NearbyFestival> = items.map { it.toDomain() }

fun NearbyFestivalDto.toDomain(): NearbyFestival = NearbyFestival(
    contentId = contentId,
    name = name,
    // 홈 축제(§4-1)와 같은 이유로, 날짜가 깨져도 항목을 버리지 않는다
    startDate = startDate.toLocalDateOrNull(),
    endDate = endDate.toLocalDateOrNull(),
    distanceKm = distanceKm,
    imageUrl = imageUrl?.takeIf { it.isNotBlank() },
    address = address,
)

private fun String.toLocalDateOrNull(): LocalDate? =
    if (isBlank()) null else try { LocalDate.parse(this) } catch (e: DateTimeParseException) { null }
