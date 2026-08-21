package com.runninggu.app.ui.calendar

import com.runninggu.app.ui.model.RaceSummary
import com.runninggu.app.domain.RegistrationStatus
import com.runninggu.app.ui.model.registrationStatus
import java.time.LocalDate
import java.time.YearMonth

/**
 * 월간 건수 조회 상태. (API 명세 §3-2)
 *
 * 달력의 점은 목록과 별도로 조회한다. 실패해도 목록은 볼 수 있으므로 화면 전체를 오류로
 * 덮지 않고 여기서만 구분한다(AGENTS 2장-5 영역 단위 부분 실패).
 */
sealed interface DailyCountsState {
    data object Loading : DailyCountsState
    data class Content(val counts: Map<LocalDate, Int>) : DailyCountsState
    data object Error : DailyCountsState
}

/** 화면이 점을 찍을 때 쓰는 값. 로딩·실패는 점이 없다. */
val DailyCountsState.countsOrEmpty: Map<LocalDate, Int>
    get() = (this as? DailyCountsState.Content)?.counts.orEmpty()

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
    /**
     * 다음 페이지 커서. **앱은 해석하지 않는다** — 서버가 준 문자열 그대로 되돌려준다(§0-4).
     * null 이면 마지막 장이다. (API 명세 §3-1)
     */
    val nextCursor: String? = null,
    val hasNext: Boolean = false,
    /** 다음 장을 받는 중. 목록은 그대로 두고 하단 표시만 바꾼다. */
    val loadingMore: Boolean = false,
    /**
     * 월간 뷰 날짜별 대회 수. (API 명세 §3-2)
     *
     * 목록([allRaces])과 달리 **받아온 페이지에 없는 대회도 센다.** 목록은 커서로 나눠
     * 오지만 달력의 점은 그 달 전체를 알아야 맞기 때문이다 — 첫 장에 없는 대회의 날짜에
     * 점이 안 찍히면 "이 달엔 대회가 없구나" 로 읽힌다(#85 리뷰).
     *
     * **못 불러온 것과 없는 것을 가른다.** 빈 맵으로 뭉뚱그리면 조회가 실패했을 때
     * 대회 없는 달과 구분이 안 된다.
     */
    val dailyCounts: DailyCountsState = DailyCountsState.Loading,
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
