package com.runninggu.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.ContestFilter
import com.runninggu.app.data.repository.ContestRepository
import com.runninggu.app.domain.EventType
import com.runninggu.app.ui.favorite.FavoriteStore
import com.runninggu.app.ui.favorite.FavoriteToggleResult
import com.runninggu.app.ui.model.toRaceSummary
import com.runninggu.app.ui.userMessageOrDefault
import com.runninggu.app.domain.today
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * S2 캘린더 ViewModel. (SPEC §2.4 · AP-10 · AP-14)
 *
 * **거르는 일은 서버가 한다.** 검색어·종목·지역·접수가능을 [ContestFilter] 로 넘기고 결과를
 * 그대로 받는다(API 명세 §3-1). 목록이 커서로 나뉘어 오기 때문에, 받아온 페이지만 놓고
 * 앱에서 다시 거르면 아직 안 받은 대회가 조건에 맞아도 안 보인다.
 *
 * [CalendarUiState.filteredRaces] 의 조건은 그래서 **없애지 않고 그물로 남겨 둔다** — 서버가
 * 이미 같은 조건으로 걸러 주므로 평소에는 아무것도 걸리지 않는다.
 */
class CalendarViewModel(
    private val repository: ContestRepository = ServiceLocator.contestRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    /** 찜 토글 결과를 스낵바로 알린다. 소비하면 [onMessageShown]으로 비운다. (SPEC §3-4) */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 게스트가 하트를 눌렀다. 화면이 로그인으로 유도한다. (SPEC §4.5 · 결정-4) */
    private val _loginRequired = MutableStateFlow(false)
    val loginRequired: StateFlow<Boolean> = _loginRequired.asStateFlow()

    private var initialQueryApplied = false

    /** 조회는 한 번에 하나만. 조건을 빠르게 바꿀 때 늦게 온 응답이 최신을 덮는 걸 막는다. */
    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private var countsJob: Job? = null
    private var searchJob: Job? = null

    /**
     * 달을 한 번이라도 정했는가.
     *
     * 첫 조회는 결과가 있는 달을 열어 주는 게 맞지만, 그 뒤로는 **사용자가 보고 있는 달**이
     * 기준이다. 조회할 때마다 옮기면 날짜 선택을 해제하는 것만으로 달이 바뀐다(#85 리뷰).
     */
    private var monthDecided = false

    init {
        load()
        // 찜은 S3 상세·S10 마이와 같은 값을 봐야 하므로 공용 보관소를 구독한다. (SPEC §4.5)
        viewModelScope.launch {
            FavoriteStore.favoriteIds.collect { ids ->
                _uiState.update { it.copy(favoriteIds = ids) }
            }
        }
    }

    /**
     * 홈에서 넘어온 검색어를 최초 1회만 적용한다. (SPEC §4.5 · §4.4-1)
     *
     * **검색어를 넣은 뒤 다시 조회한다.** 거르는 일을 서버가 하는데(§3-1 `q`), `init` 의
     * 첫 조회는 검색어 없이 나가 있다. 그 결과만 놓고 앱에서 다시 걸러 봐야 **검색 대상이
     * 첫 장 밖에 있으면 안 보인다** — 조건에 맞는 대회가 있는데도 빈 화면이 된다(#85 리뷰).
     */
    fun applyInitialQuery(query: String) {
        if (initialQueryApplied) return
        initialQueryApplied = true
        if (query.isBlank()) return

        _uiState.update { it.copy(query = query) }
        load()
    }

    /** 첫 장부터 다시. 진입·조건 변경·오류 재시도가 모두 여기로 온다. */
    fun load() {
        loadJob?.cancel()
        moreJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = CalendarUiState.Phase.LOADING,
                    errorMessage = null,
                    loadingMore = false,
                )
            }
            try {
                val page = repository.list(filter = _uiState.value.toContestFilter())
                // 서버가 `contest_date >= 오늘(KST)` 을 보장하지만(§3-1), 번들로 대체해도
                // 같게 보이도록 여기서도 한 번 거른다. (SPEC §4.5)
                val today = today()
                val upcoming = page.contests
                    .map { it.toRaceSummary() }
                    .filter { !it.date.isBefore(today) }

                _uiState.update { state ->
                    state.copy(
                        phase = CalendarUiState.Phase.LOADED,
                        allRaces = upcoming,
                        nextCursor = page.nextCursor,
                        hasNext = page.hasNext,
                        // **보고 있던 달을 유지한다.** 매번 첫 결과의 달로 옮기면, 10월 날짜를
                        // 눌렀다 재탭으로 해제했을 때 8월로 튄다 — 해제는 달을 바꾸는 동작이
                        // 아니다(SPEC §4.5 · #85 리뷰). 첫 조회에서만 옮긴다
                        currentMonth = if (monthDecided) {
                            state.currentMonth
                        } else {
                            upcoming.minByOrNull { race -> race.date }
                                ?.let { race -> YearMonth.from(race.date) }
                                ?: state.currentMonth
                        },
                    )
                }
                monthDecided = true
                // 리스트 뷰에서는 달력이 안 보인다 — 안 쓰는 값을 받으려고 왕복하지 않는다
                if (_uiState.value.viewMode == CalendarViewMode.CALENDAR) loadDailyCounts()
            } catch (e: ApiException) {
                _uiState.update {
                    it.copy(
                        phase = CalendarUiState.Phase.ERROR,
                        errorMessage = e.userMessageOrDefault(),
                    )
                }
            }
        }
    }

    /**
     * 다음 장을 이어 붙인다. (API 명세 §3-1)
     *
     * **실패해도 목록을 지우지 않는다.** 이미 보고 있던 대회가 사라지는 것보다 스낵바로
     * 알리고 그대로 두는 편이 낫다 — 다시 스크롤하면 재시도된다.
     */
    fun loadMore() {
        val state = _uiState.value
        val cursor = state.nextCursor ?: return
        if (!state.hasNext || state.loadingMore) return
        if (state.phase != CalendarUiState.Phase.LOADED) return

        moreJob = viewModelScope.launch {
            _uiState.update { it.copy(loadingMore = true) }
            try {
                val page = repository.list(filter = _uiState.value.toContestFilter(), cursor = cursor)
                val today = today()
                val more = page.contests
                    .map { it.toRaceSummary() }
                    .filter { !it.date.isBefore(today) }

                _uiState.update { current ->
                    // 같은 대회가 두 번 오면 한 번만 남긴다 — 조회 중 원천이 갱신되면 생긴다
                    val seen = current.allRaces.mapTo(mutableSetOf()) { it.id }
                    current.copy(
                        allRaces = current.allRaces + more.filter { it.id !in seen },
                        nextCursor = page.nextCursor,
                        hasNext = page.hasNext,
                        loadingMore = false,
                    )
                }
            } catch (e: ApiException) {
                _uiState.update { it.copy(loadingMore = false) }
                _message.value = e.userMessageOrDefault()
            }
        }
    }

    /**
     * 이 달의 날짜별 대회 수. (API 명세 §3-2)
     *
     * 목록과 **같은 조건**을 넘겨야 한다 — 조건을 안 넘기면 걸러진 대회까지 점이 찍혀서
     * 눌렀는데 아무것도 없는 날이 생긴다.
     *
     * 실패해도 화면 전체를 오류로 덮지 않는다. 점이 안 찍혀도 목록은 볼 수 있기 때문이다
     * (AGENTS 2장-5 영역별 부분 실패). 다만 **빈 맵으로 뭉뚱그리지 않는다** — 그러면
     * 대회 없는 달과 구분이 안 돼서 "이 달엔 대회가 없구나" 로 읽힌다(#85 리뷰).
     */
    private fun loadDailyCounts() {
        val state = _uiState.value
        val month = state.currentMonth
        countsJob?.cancel()
        countsJob = viewModelScope.launch {
            _uiState.update {
                if (it.currentMonth == month) it.copy(dailyCounts = DailyCountsState.Loading) else it
            }
            val result = try {
                DailyCountsState.Content(
                    repository.dailyCounts(
                        year = month.year,
                        month = month.monthValue,
                        filter = state.toContestFilter(includeDate = false),
                    ),
                )
            } catch (e: ApiException) {
                DailyCountsState.Error
            }
            _uiState.update {
                // 늦게 온 응답이 그 사이 바뀐 달을 덮지 않게 한다
                if (it.currentMonth == month) it.copy(dailyCounts = result) else it
            }
        }
    }

    /**
     * 검색어. 한 글자마다 부르지 않고 [SEARCH_DEBOUNCE_MS] 만큼 기다렸다 조회한다 —
     * 서버가 거르기 때문에(§3-1) 타이핑 중 매번 왕복하면 낭비다.
     */
    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            load()
        }
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
        if (mode == CalendarViewMode.CALENDAR) loadDailyCounts()
    }

    fun onFilterApply(filter: RaceFilter) {
        if (_uiState.value.filter == filter) return
        _uiState.update { it.copy(filter = filter) }
        load()
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
        load()
    }

    /** 빈 상태의 [필터 초기화] — 검색어까지 함께 지운다. */
    fun onResetAll() {
        searchJob?.cancel()
        _uiState.update { it.copy(filter = RaceFilter(), query = "") }
        load()
    }

    /** 월 이동. 선택일은 초기화한다. (SPEC §4.5) */
    fun onMonthChange(delta: Long) {
        _uiState.update { it.copy(currentMonth = it.currentMonth.plusMonths(delta), selectedDate = null) }
        loadDailyCounts()
    }

    /**
     * 날짜 탭. 같은 날을 다시 누르면 해제한다. (SPEC §4.5)
     *
     * 고른 날짜도 서버 조건이다(§3-1 `date`) — 그 날 대회가 첫 장 밖에 있으면 앱에서
     * 걸러 봐야 안 나오기 때문에 다시 조회한다.
     */
    fun onDateSelect(date: LocalDate) {
        _uiState.update { it.copy(selectedDate = if (it.selectedDate == date) null else date) }
        load()
    }

    /** 하트 토글. 카드 이동과 독립이며 스낵바를 띄운다. (SPEC §4.5 · 결정-16 · AP-21) */
    fun onFavoriteToggle(raceId: String) {
        viewModelScope.launch {
            // 상태 갱신은 보관소 구독(init)이 받아서 반영한다 — 낙관적 갱신이라 즉시 온다.
            when (val result = FavoriteStore.toggle(raceId)) {
                FavoriteToggleResult.LoginRequired -> _loginRequired.value = true
                is FavoriteToggleResult.Done ->
                    _message.value = if (result.nowFavorite) "찜했어요" else "찜을 해제했어요"
                FavoriteToggleResult.Failed ->
                    _message.value = "찜을 저장하지 못했어요. 잠시 후 다시 시도해 주세요."
            }
        }
    }

    fun onMessageShown() {
        _message.value = null
    }

    fun onLoginRequiredShown() {
        _loginRequired.value = false
    }

    companion object {
        /** 타이핑이 멎었다고 볼 시간. 너무 짧으면 왕복이 늘고 길면 검색이 굼떠 보인다. */
        private const val SEARCH_DEBOUNCE_MS = 300L

        /**
         * 기본은 서버 저장소다. 테스트·미리보기에서 가짜 저장소로 바꿔 끼운다.
         * 화면은 안 건드린다 — [ContestRepository] 인터페이스만 보기 때문이다(AGENTS 4장).
         */
        fun factory(repository: ContestRepository = ServiceLocator.contestRepository) =
            viewModelFactory {
                initializer { CalendarViewModel(repository) }
            }
    }
}

/**
 * 화면 조건을 서버 파라미터로. (API 명세 §3-1)
 *
 * 종목은 화면이 한국어 라벨(`풀`·`하프`)로 들고 있고 서버는 계약 값(`FULL`·`HALF`)을 받는다.
 * 모르는 라벨은 **보내지 않는다** — 서버에 낯선 값을 넘겨 400 을 받느니 그 조건만 빠지는 게 낫다.
 *
 * @param includeDate 월간 건수 조회에는 [CalendarUiState.selectedDate] 를 넘기지 않는다.
 *   하루만 세면 달력의 나머지 날에 점이 사라진다.
 */
private fun CalendarUiState.toContestFilter(includeDate: Boolean = true): ContestFilter =
    ContestFilter(
        query = query.takeIf { it.isNotBlank() },
        events = filter.events.mapNotNull { EventType.fromLabel(it) },
        openOnly = filter.openOnly,
        regions = filter.regions.toList(),
        date = selectedDate.takeIf { includeDate && viewMode == CalendarViewMode.CALENDAR },
    )
