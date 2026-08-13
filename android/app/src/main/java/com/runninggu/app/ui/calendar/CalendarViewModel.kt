package com.runninggu.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.ui.sample.SampleData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * S2 캘린더 ViewModel. (SPEC §2.4 · AP-10)
 *
 * TODO(AP-14): 임시 데이터를 백엔드 대회 API로 교체한다.
 * TODO(AP-21): 찜은 지금 메모리에만 있다. `/me/favorites` 동기화와 게스트 로그인 유도를 붙인다.
 */
class CalendarViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    /** 찜 토글 결과를 스낵바로 알린다. 소비하면 [onMessageShown]으로 비운다. (SPEC §3-4) */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var initialQueryApplied = false

    init {
        load()
    }

    /** 홈에서 넘어온 검색어를 최초 1회만 적용한다. (SPEC §4.5) */
    fun applyInitialQuery(query: String) {
        if (initialQueryApplied) return
        initialQueryApplied = true
        if (query.isNotBlank()) {
            _uiState.update { it.copy(query = query) }
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(phase = CalendarUiState.Phase.LOADING, errorMessage = null) }
            delay(LOADING_DELAY_MS) // 임시 — 실제 조회로 교체하면 제거한다.

            // 노출 대상은 오늘 이후 대회만. (SPEC §4.5)
            val today = LocalDate.now()
            val upcoming = SampleData.races.filter { !it.date.isBefore(today) }

            _uiState.update {
                it.copy(
                    phase = CalendarUiState.Phase.LOADED,
                    allRaces = upcoming,
                    currentMonth = upcoming.minByOrNull { race -> race.date }
                        ?.let { race -> YearMonth.from(race.date) }
                        ?: YearMonth.now(),
                )
            }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    /**
     * 뷰 전환. 캘린더로 들어갈 때는 현재 조건에 맞는 첫 대회의 달을 연다 — 조건을 걸어둔
     * 채로 들어오면 빈 달이 열리는 걸 막는다. 이후 월 이동은 사용자가 한다. 🧩목업
     */
    fun onViewModeChange(mode: CalendarViewMode) {
        _uiState.update { state ->
            val month = if (mode == CalendarViewMode.CALENDAR) {
                state.filteredRaces.firstOrNull()?.let { YearMonth.from(it.date) }
                    ?: state.currentMonth
            } else {
                state.currentMonth
            }
            state.copy(viewMode = mode, selectedDate = null, currentMonth = month)
        }
    }

    fun onFilterApply(filter: RaceFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    /** 적용 중 칩의 ✕ — 조건 하나만 해제한다. */
    fun onFilterChipRemove(chip: ActiveFilterChip) {
        _uiState.update { state ->
            val filter = state.filter
            val next = when (chip.kind) {
                ActiveFilterChip.Kind.EVENT ->
                    filter.copy(events = filter.events - setOfNotNull(chip.value))

                ActiveFilterChip.Kind.REGION ->
                    filter.copy(regions = filter.regions - setOfNotNull(chip.value))
                ActiveFilterChip.Kind.OPEN_ONLY -> filter.copy(openOnly = false)
            }
            state.copy(filter = next)
        }
    }

    /** 빈 상태의 [필터 초기화] — 검색어까지 함께 지운다. */
    fun onResetAll() {
        _uiState.update { it.copy(filter = RaceFilter(), query = "") }
    }

    /** 월 이동. 선택일은 초기화한다. (SPEC §4.5) */
    fun onMonthChange(delta: Long) {
        _uiState.update { it.copy(currentMonth = it.currentMonth.plusMonths(delta), selectedDate = null) }
    }

    /** 날짜 탭. 같은 날을 다시 누르면 해제한다. */
    fun onDateSelect(date: LocalDate) {
        _uiState.update { it.copy(selectedDate = if (it.selectedDate == date) null else date) }
    }

    /** 하트 토글. 카드 이동과 독립이며 스낵바를 띄운다. (SPEC §4.5 · 결정-16) */
    fun onFavoriteToggle(raceId: String) {
        _uiState.update { state ->
            val next = if (state.isFavorite(raceId)) {
                state.favoriteIds - raceId
            } else {
                state.favoriteIds + raceId
            }
            _message.value = if (raceId in next) "찜했어요" else "찜을 해제했어요"
            state.copy(favoriteIds = next)
        }
    }

    fun onMessageShown() {
        _message.value = null
    }

    private companion object {
        const val LOADING_DELAY_MS = 400L
    }
}
