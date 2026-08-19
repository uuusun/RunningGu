package com.runninggu.app.data.local

import com.runninggu.app.data.model.Contest
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.RegistrationStatus
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * `assets/races.json` 한 줄. (SPEC §6.1 데이터 번들)
 *
 * `scripts/build_races_json.py` 가 서버 배치와 **같은 원천**에서 만든다. 서버 API 와
 * 필드명·값 표기가 다르므로(한국어 값·`venue`/`checked`) DTO 를 따로 두고 매퍼로 모은다.
 *
 * 이 파일은 앱에 박혀 있어 낡는다 — **서버 데이터보다 우선하지 않는다**(§6.1).
 */
@Serializable
data class RaceBundleDto(
    val id: String,
    val name: String,
    val region: String = "",
    val venue: String = "",
    val date: String,
    val startTime: String = "",
    val eventTypes: List<String> = emptyList(),
    val regStatus: String = "",
    val regStart: String = "",
    val regEnd: String = "",
    val organizer: String = "",
    val source: String = "",
    val checked: String = "",
    val officialUrl: String = "",
    val detailUrl: String = "",
    val imageUrl: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val category: String = "",
)

/**
 * 번들 한 줄을 앱 모델로. 날짜가 깨졌으면 그 항목만 버린다.
 *
 * 번들 전체를 실패시키지 않는 이유 — 오프라인 폴백이라 **한 건 때문에 전부 못 쓰면 안 된다**.
 * 대신 버린 건수는 [BundleParseResult.skipped] 로 남겨 조용히 사라지지 않게 한다.
 */
internal fun RaceBundleDto.toContestOrNull(): Contest? {
    val day = date.toLocalDateOrNull() ?: return null
    return Contest(
        id = id,
        name = name,
        region = region,
        venue = venue,
        date = day,
        startTime = startTime.toLocalTimeOrNull(),
        eventTypes = eventTypes.mapNotNull(EventType::fromLabel),
        regStart = regStart.toLocalDateOrNull(),
        regEnd = regEnd.toLocalDateOrNull(),
        regStatusFallback = regStatusOfLabel(regStatus),
        organizer = organizer.ifBlank { null },
        officialUrl = officialUrl.ifBlank { null },
        detailUrl = detailUrl.ifBlank { null },
        imageUrl = imageUrl.ifBlank { null },
        lat = lat,
        lng = lng,
        category = category.ifBlank { null },
        checked = checked.toLocalDateOrNull(),
        sources = listOfNotNull(source.ifBlank { null }),
    )
}

/** 번들의 접수 상태도 한국어다. 표시에는 쓰지 않고 재계산의 근거로만 쓴다(§5.5). */
private fun regStatusOfLabel(raw: String): RegistrationStatus? = when (raw) {
    "접수중" -> RegistrationStatus.OPEN
    "접수전" -> RegistrationStatus.BEFORE
    "마감" -> RegistrationStatus.CLOSED
    "미정" -> RegistrationStatus.UNKNOWN
    else -> null
}

private fun String.toLocalDateOrNull(): LocalDate? =
    if (isBlank()) null else try { LocalDate.parse(this) } catch (e: DateTimeParseException) { null }

private fun String.toLocalTimeOrNull(): LocalTime? =
    if (isBlank()) null else try { LocalTime.parse(this) } catch (e: DateTimeParseException) { null }
