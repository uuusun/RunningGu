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

/** 크롤 레코드의 종목 플래그. 세 단계 중 ① 이다 — 나머지를 가리지 않는다. */
data class EventFlags(
    val full: Boolean = false,
    val half: Boolean = false,
    val tenK: Boolean = false,
    val fiveK: Boolean = false,
) {
    val any: Boolean get() = full || half || tenK || fiveK
}

/**
 * 토큰 하나를 종목으로. **모르는 토큰은 `null`** 이다. (SPEC §5.4 ③)
 *
 * [stdEvent] 와 나뉘는 이유 — 저쪽은 "그 외 `5K`" 라 뭘 넣어도 종목 하나가 나온다.
 * 그건 **종목 하나를 표준화**할 때의 규칙이고, 여기서는 **목록을 만드는 중**이라
 * 모르는 토큰을 `5K` 로 넣으면 없는 종목이 생긴다.
 *
 * 숫자 앞을 보는 이유는 `110km` 의 `10k`, `15km`·`4.5km` 의 `5k` 오매칭을 막기 위해서다
 * (`scripts/build_races_json.py` 의 같은 규칙과 짝이다 — 결정-39).
 */
fun stdEventToken(raw: String?): EventType? {
    val s = raw.orEmpty().lowercase()
    return when {
        Regex("""풀|full|(^|\D)42""").containsMatchIn(s) -> EventType.FULL
        Regex("""하프|half|(^|\D)21""").containsMatchIn(s) -> EventType.HALF
        Regex("""(?<![\d.])10\s*k""").containsMatchIn(s) -> EventType.TEN_K
        Regex("""(?<![\d.])5\s*k""").containsMatchIn(s) -> EventType.FIVE_K
        else -> null
    }
}

/**
 * 크롤 레코드 → 표준 종목 목록. (SPEC §5.4 `eventsFromContest`)
 *
 * **세 단계의 합집합**이다 — ① `has_*` 플래그 ∪ ② `distances` 거리 버킷 ∪ ③ `event_types` 토큰.
 * 번호는 표기 순서일 뿐 우선순위가 아니다. 앞 단계가 뒤를 가리면 `has_full` 만 켜진
 * 무주 풀코스(`distances=[42.195, 24, 12, 8, 4]`)가 **풀 하나로 줄어든다** — 실제로
 * 그렇게 동작하고 있었다(이슈 #61).
 *
 * 순서는 언제나 `[풀, 하프, 10K, 5K]` 다.
 */
fun eventsFromRace(
    flags: EventFlags? = null,
    distancesKm: List<Double> = emptyList(),
    eventTypes: List<String> = emptyList(),
): List<EventType> {
    val picked = mutableSetOf<EventType>()

    // ① 플래그
    if (flags != null) {
        if (flags.full) picked += EventType.FULL
        if (flags.half) picked += EventType.HALF
        if (flags.tenK) picked += EventType.TEN_K
        if (flags.fiveK) picked += EventType.FIVE_K
    }

    // ② 거리 버킷 — 0·음수는 거리가 아니라 결측이다
    distancesKm.filter { it > 0 }.mapNotNullTo(picked, ::stdEventKm)

    // ③ 토큰 — 모르는 것은 넣지 않는다
    eventTypes.mapNotNullTo(picked, ::stdEventToken)

    return EventType.entries.filter { it in picked }
}
