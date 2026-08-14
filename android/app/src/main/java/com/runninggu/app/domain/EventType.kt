package com.runninggu.app.domain

/**
 * 표준 종목 4종. (SPEC §5.4)
 *
 * 선언 순서가 곧 표시 순서다 — 풀 > 하프 > 10K > 5K.
 */
enum class EventType(val label: String) {
    FULL("풀"),
    HALF("하프"),
    TEN_K("10K"),
    FIVE_K("5K"),
    ;

    companion object {
        /** 라벨('풀'·'하프'·'10K'·'5K')로 찾는다. 못 찾으면 null. */
        fun fromLabel(label: String?): EventType? = entries.firstOrNull { it.label == label }
    }
}

/**
 * 원천의 다양한 표기를 표준 종목으로 정규화한다. (SPEC §5.4)
 *
 * `풀|full|42 → 풀` · `하프|half|21 → 하프` · `10k → 10K` · 그 외 `5K`.
 */
fun stdEvent(raw: String?): EventType {
    val s = raw.orEmpty().lowercase().replace(" ", "")
    return when {
        s.contains("풀") || s.contains("full") || s.contains("42") -> EventType.FULL
        s.contains("하프") || s.contains("half") || s.contains("21") -> EventType.HALF
        s.contains("10k") || Regex("""(^|[^0-9])10([^0-9]|$)""").containsMatchIn(s) -> EventType.TEN_K
        else -> EventType.FIVE_K
    }
}

/** 여러 표기를 표준 종목 목록으로. 중복을 없애고 풀 > 하프 > 10K > 5K 순으로 돌려준다. */
fun stdEvents(raw: List<String>?): List<EventType> {
    val set = raw.orEmpty().map(::stdEvent).toSet()
    return EventType.entries.filter { it in set }
}

/**
 * 거리(km)를 종목 버킷으로. 트레일·비표준 거리(11·15·28·40km 등)를 가까운 종목에 붙인다.
 *
 * `≥32 → 풀` · `≥18 → 하프` · `≥9 → 10K` · 그 외 `5K`. (SPEC §5.4)
 */
fun stdEventKm(km: Double?): EventType? = when {
    km == null || km.isNaN() -> null
    km >= 32 -> EventType.FULL
    km >= 18 -> EventType.HALF
    km >= 9 -> EventType.TEN_K
    else -> EventType.FIVE_K
}

/** 크롤 레코드의 종목 플래그. 하나라도 켜져 있으면 거리·토큰보다 우선한다. */
data class EventFlags(
    val full: Boolean = false,
    val half: Boolean = false,
    val tenK: Boolean = false,
    val fiveK: Boolean = false,
) {
    val any: Boolean get() = full || half || tenK || fiveK
}

/**
 * 크롤 레코드 → 표준 종목 목록. (SPEC §5.4 `eventsFromContest`)
 *
 * 우선순위는 ① `has_*` 플래그 ② `distances` 거리 버킷 ③ `event_types` 토큰 이다.
 * 앞 단계에서 하나라도 나오면 뒤는 보지 않는다.
 */
fun eventsFromRace(
    flags: EventFlags? = null,
    distancesKm: List<Double> = emptyList(),
    eventTypes: List<String> = emptyList(),
): List<EventType> {
    if (flags != null && flags.any) {
        val picked = buildList {
            if (flags.full) add(EventType.FULL)
            if (flags.half) add(EventType.HALF)
            if (flags.tenK) add(EventType.TEN_K)
            if (flags.fiveK) add(EventType.FIVE_K)
        }.toSet()
        return EventType.entries.filter { it in picked }
    }
    if (distancesKm.isNotEmpty()) {
        val picked = distancesKm.mapNotNull(::stdEventKm).toSet()
        if (picked.isNotEmpty()) return EventType.entries.filter { it in picked }
    }
    return stdEvents(eventTypes)
}
