package com.runninggu.app.ui.calendar

import com.runninggu.app.ui.model.RaceSummary
import com.runninggu.app.domain.RegistrationStatus
import com.runninggu.app.ui.model.registrationStatus
import java.time.LocalDate
import java.time.YearMonth

/** 리스트 / 캘린더 뷰 토글. 기본은 리스트. (SPEC §4.5) */
enum class CalendarViewMode { LIST, CALENDAR }

/**
 * 대회 목록 필터. 조건은 모두 AND로 결합한다. (SPEC §4.5)
 * 월 칩은 없다 — 월 탐색은 캘린더 뷰가 담당한다.
 */
data class RaceFilter(
    val events: Set<String> = emptySet(),
    val regions: Set<String> = emptySet(),
    val openOnly: Boolean = false,
) {
    val isEmpty: Boolean
        get() = events.isEmpty() && regions.isEmpty() && !openOnly

    /** [필터] 버튼 옆에 나열할 '적용 중' 칩. ✕로 개별 해제한다. */
    fun activeChips(): List<ActiveFilterChip> = buildList {
        events.forEach { add(ActiveFilterChip(it, ActiveFilterChip.Kind.EVENT, it)) }
        if (openOnly) add(ActiveFilterChip("접수 가능만", ActiveFilterChip.Kind.OPEN_ONLY, null))
        regions.forEach { add(ActiveFilterChip(it, ActiveFilterChip.Kind.REGION, it)) }
    }
}

data class ActiveFilterChip(val label: String, val kind: Kind, val value: String?) {
    enum class Kind { EVENT, REGION, OPEN_ONLY }
}

/**
 * S2 캘린더의 UI 계약. (SPEC §4.5 · §3-5)
 *
 * 조회 상태([phase])와 화면 조작 상태(검색어·필터·뷰 모드)를 한 객체에 담는다.
 * 목록은 원본 [allRaces]에서 파생하므로 별도 저장하지 않는다.
 */
data class CalendarUiState(
    val phase: Phase = Phase.LOADING,
    val errorMessage: String? = null,
    val allRaces: List<RaceSummary> = emptyList(),
    val query: String = "",
    val filter: RaceFilter = RaceFilter(),
    val viewMode: CalendarViewMode = CalendarViewMode.LIST,
    val currentMonth: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate? = null,
    val favoriteIds: Set<String> = emptySet(),
) {
    enum class Phase { LOADING, ERROR, LOADED }

    /** 검색어 ∧ 종목 ∧ 접수 가능 ∧ 지역. 개최일 오름차순. (SPEC §4.5) */
    val filteredRaces: List<RaceSummary>
        get() = allRaces
            .filter { race ->
                filter.events.isEmpty() || race.eventTypes.any { it in filter.events }
            }
            .filter { race ->
                filter.regions.isEmpty() || race.region in filter.regions
            }
            .filter { race ->
                !filter.openOnly || race.registrationStatus() == RegistrationStatus.OPEN
            }
            .filter { race ->
                query.isBlank() || listOf(race.name, race.venue, race.region)
                    .any { it.contains(query, ignoreCase = true) }
            }
            .sortedBy { it.date }

    /** 캘린더 뷰에서 날짜별 점을 찍기 위한 묶음. */
    val racesByDate: Map<LocalDate, List<RaceSummary>>
        get() = filteredRaces.groupBy { it.date }

    /**
     * 화면 하단에 실제로 나열할 목록.
     * 캘린더 뷰에서는 선택일이 있으면 그 날, 없으면 이달 전체를 보여준다.
     */
    val listedRaces: List<RaceSummary>
        get() = when (viewMode) {
            CalendarViewMode.LIST -> filteredRaces
            CalendarViewMode.CALENDAR -> when (val day = selectedDate) {
                null -> filteredRaces.filter { YearMonth.from(it.date) == currentMonth }
                else -> filteredRaces.filter { it.date == day }
            }
        }

    fun isFavorite(raceId: String): Boolean = raceId in favoriteIds
}
