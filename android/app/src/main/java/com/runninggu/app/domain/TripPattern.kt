package com.runninggu.app.domain

import java.time.LocalDate

/**
 * 일정 패턴. 대회일 기준 오프셋으로 여행 기간을 잡는다. (SPEC §5.2)
 *
 * [CUSTOM] 은 사용자가 달력에서 직접 고르므로 오프셋이 없다.
 */
enum class TripPattern(
    val key: String,
    val label: String,
    val sub: String,
    val startOffset: Int?,
    val endOffset: Int?,
) {
    PRE("pre", "전날부터", "1박 2일", -1, 0),
    POST("post", "대회+다음날", "1박 2일", 0, 1),
    AROUND("around", "전후로", "2박 3일", -1, 1),
    DAY("day", "당일치기", "당일", 0, 0),
    CUSTOM("custom", "직접 선택", "달력에서 직접 고르기", null, null),
    ;

    val isCustom: Boolean get() = startOffset == null

    /** 대회일에 오프셋을 적용한 기간. [CUSTOM] 이면 null — 사용자 선택을 써야 한다. */
    fun rangeOf(raceDate: LocalDate): ClosedRange<LocalDate>? {
        val s = startOffset ?: return null
        val e = endOffset ?: return null
        return raceDate.plusDays(s.toLong())..raceDate.plusDays(e.toLong())
    }

    companion object {
        /** 기본 선택값. (SPEC §5.2 — 전후로) */
        val DEFAULT = AROUND

        fun fromKey(key: String?): TripPattern? = entries.firstOrNull { it.key == key }
    }
}
