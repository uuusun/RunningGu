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
     * 다음 장 조회가 실패한 이유. null 이면 정상이다. (#85 리뷰)
     *
     * **실패하면 자동으로 다시 부르지 않는다.** 끝에 닿은 것만으로 다시 시도하면 네트워크가
     * 끊긴 동안 같은 요청을 계속 던진다. 사용자가 [다시 시도] 를 눌러야 한다.
     */
    val loadMoreError: String? = null,
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

    /**
     * "이 달엔 대회가 없어요" 를 확정해도 되는가. (#85 리뷰)
     *
     * **아직 받을 장이 남았으면 모르는 것이다.** 목록은 커서로 나뉘어 오는데, 9월 대회가
     * 다음 장에 있는 상태로 9월을 열면 지금까지 받은 것 중에는 하나도 없다. 그걸 Empty 로
     * 확정하면 달력에는 점이 찍혔는데 아래는 "없어요" 가 된다.
     */
    val showsEmpty: Boolean get() = listedRaces.isEmpty() && !hasNext

    /** 목록 끝에 "더 받기" 자리를 둘 것인가. 남은 장이 있으면 목록이 비어도 둔다. */
    val showsLoadMore: Boolean get() = hasNext

    /**
     * 목록 헤더 옆에 붙이는 건수. (#85 리뷰)
     *
     * 캘린더 뷰에서 날짜를 안 고른 상태의 헤더는 "9월 대회" 처럼 **그 달 전체**를 가리키므로
     * 달력의 점과 같은 값(`daily-counts`)을 쓴다. `listedRaces.size` 를 쓰면 아직 안 받은
     * 장이 빠져서, 점을 세면 27 인 달에 헤더만 12 로 나온다 — **한 화면 안에서 두 숫자가
     * 어긋나고, 사용자는 어느 쪽이 맞는지 알 방법이 없다.**
     *
     * 나머지는 받아온 목록을 그대로 센다. 선택일 헤더("9.6 (일) 대회")는 그날 것을 이미 다
     * 받은 상태이고, 리스트 뷰 헤더에는 월 개념이 없다.
     *
     * 건수를 못 불러왔으면(로딩·실패) 받아온 만큼이라도 센다. 그때는 점도 없으므로 어긋날
     * 것이 없다.
     */
    val headerRaceCount: Int
        get() = when {
            viewMode == CalendarViewMode.CALENDAR && selectedDate == null ->
                when (val counts = dailyCounts) {
                    is DailyCountsState.Content ->
                        counts.counts
                            .filterKeys { YearMonth.from(it) == currentMonth }
                            .values
                            .sum()
                    else -> listedRaces.size
                }
            else -> listedRaces.size
        }

    fun isFavorite(raceId: String): Boolean = raceId in favoriteIds
}
