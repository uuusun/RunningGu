package com.runninggu.app.ui.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.apiErrorCode
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.createSavedStateHandle
import com.runninggu.app.data.repository.ContestRepository
import com.runninggu.app.ui.navigation.Routes
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.PoiCategory
import com.runninggu.app.domain.TripPattern
import com.runninggu.app.domain.stdEvents
import com.runninggu.app.ui.model.RaceSummary
import com.runninggu.app.ui.model.toRaceSummary
import com.runninggu.app.ui.userMessageOrDefault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.runninggu.app.data.model.PoiItem

/**
 * 위저드(S4~S7) 공유 ViewModel. (SPEC §2.4 · AP-11)
 *
 * wizard 그래프 스코프로 만들어 S4~S7이 같은 인스턴스를 본다 —
 * 생성 위치는 [com.runninggu.app.ui.navigation.RunningGuNavHost] 참고.
 *
 * TODO(AP-11): S5~S7 진행에 따라 event·themes·stay·days 액션을 이 클래스에 이어 붙인다.
 */
class WizardViewModel(
    private val repository: ContestRepository = ServiceLocator.contestRepository,
    /**
     * wizard 그래프 항목의 상태. **`raceId` 가 여기 들어 있다** — 그래프 route 가
     * `wizard/{raceId}` 라 항목 인자가 그대로 담긴다.
     */
    savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(WizardUiState())
    val uiState: StateFlow<WizardUiState> = _uiState.asStateFlow()

    /** 지금 싣고 있는 대회. 회전·재구성으로 [start] 가 다시 불려도 재조회하지 않는다. */
    private var raceId: String? = null

    init {
        // **화면이 불러 주기를 기다리지 않는다.** (#192 리뷰)
        //
        // 예전에는 S4 의 `LaunchedEffect` 만 [start] 를 불렀다. 그래서 시스템이 프로세스를
        // 되살리며 **S5·S6·S7 로 바로 복원**하면 S4 가 합성되지 않아 조회가 시작조차 안 됐다 —
        // S5 는 영영 로딩, S6 는 숙소 조회 없이 CTA 만 살아 있고, S7 은 기본 상태로 만들어져
        // "조건이 덜 정해졌어요" 가 됐다.
        //
        // 그래프 인자가 이미 답을 들고 있으므로 여기서 읽는다. 진입점이 하나가 된다.
        savedState.get<String>(Routes.ARG_RACE_ID)?.let(::start)
    }

    /**
     * 위저드 진입. `SELECT_RACE` 계약(SPEC §2.4)에 해당한다.
     *
     * **예전에는 [com.runninggu.app.ui.sample.SampleData] 를 봤다.** 홈·캘린더가 서버로
     * 넘어간 뒤로는 넘어오는 id 가 숫자 canonical id 인데 샘플의 id 는 `chungbuk-past`
     * 같은 슬러그라 절대 안 맞았다 — `?: return` 이라 **조용히 나가서** S4 가 "불러오는
     * 중…" 에서 영영 멈췄다(이슈 #140).
     */
    fun start(raceId: String) {
        if (this.raceId == raceId) return
        this.raceId = raceId
        load()
    }

    /**
     * 대회 조회. (`GET /api/contests/{id}` · API 명세 §3-4)
     *
     * [WizardUiState.Phase.ERROR] 의 [다시 시도] 가 이 함수를 다시 부른다 — S3
     * [com.runninggu.app.ui.racedetail.RaceDetailViewModel.load] 와 같은 모양이다.
     *
     * **canonical id 가 없는 대회는 부르지 않는다.** 번들·오프라인 항목은 크롤 원천
     * 문자열을 id 로 갖는데(`roadrun-41543`), 숫자로 못 바꾸면 서버에 물을 수 없고
     * 그건 "서버에 없다" 와 같다. `404` 와 같은 자리에 둔다(#139 · #140 리뷰).
     */
    companion object {
        /**
         * 그래프 항목의 상태를 그대로 넘긴다 — `raceId` 가 거기 담겨 온다.
         *
         * 기본 팩토리로는 [SavedStateHandle] 을 넣을 수 없어서 따로 둔다. 이걸 안 쓰면
         * 프로세스 복원 시 대회가 안 실린다(#192 리뷰).
         */
        fun factory(
            repository: ContestRepository = ServiceLocator.contestRepository,
        ) = viewModelFactory {
            initializer { WizardViewModel(repository, createSavedStateHandle()) }
        }
    }

    fun load() {
        val id = raceId ?: return
        val serverId = id.toLongOrNull()
        if (serverId == null) {
            _uiState.value = WizardUiState(contestPhase = WizardUiState.Phase.NOT_FOUND)
            return
        }
        viewModelScope.launch {
            _uiState.value = WizardUiState(contestPhase = WizardUiState.Phase.LOADING)
            val race = try {
                repository.detail(serverId).toRaceSummary()
            } catch (e: ApiException) {
                _uiState.value = if (e.apiErrorCode() == ApiErrorCode.NOT_FOUND) {
                    // 404 CONTEST_NOT_FOUND — 다시 눌러도 생기지 않는다 (§3-4)
                    WizardUiState(contestPhase = WizardUiState.Phase.NOT_FOUND)
                } else {
                    WizardUiState(
                        contestPhase = WizardUiState.Phase.ERROR,
                        errorMessage = e.userMessageOrDefault(),
                    )
                }
                return@launch
            }
            _uiState.value = WizardUiState(
                contestPhase = WizardUiState.Phase.LOADED,
                race = race,
                event = defaultEventOf(race),
            ).withPattern(TripPattern.DEFAULT)
        }
    }

    /** 종목 세그먼트 선택. 대회에 없는 종목도 고를 수 있다. (SPEC §4.8) */
    fun onEventSelect(event: EventType) {
        _uiState.update { it.copy(event = event) }
    }

    /**
     * 취향 칩 토글. 0개가 되면 [WizardUiState.canProceedFromPrefs] 가 CTA 를 막는다. (SPEC §4.8)
     *
     * 순서는 §5.3 선언 순서를 따른다 — 고른 차례대로 두면 엔진의 `pickTheme` 우선순위가
     * 탭 순서에 따라 달라진다(§5.6-5).
     */
    fun onThemeToggle(theme: PoiCategory) {
        _uiState.update { state ->
            val picked = if (theme in state.themes) state.themes - theme else state.themes + theme
            state.copy(themes = PoiCategory.selectable.filter { it in picked })
        }
    }

    /** 숙소 선택·해제. 같은 숙소를 다시 누르면 해제된다(재선택 교체). (SPEC §4.9) */
    fun onStaySelect(stay: PoiItem?) {
        _uiState.update { it.copy(stay = if (it.stay == stay) null else stay) }
    }

    /** 패턴 칩 선택. 직접 선택은 날짜를 비우고 사용자 입력을 기다린다. (SPEC §4.7) */
    fun onPatternSelect(pattern: TripPattern) {
        _uiState.update { it.withPattern(pattern) }
    }

    /**
     * 미니 캘린더 날짜 탭 — 직접 선택에서만 동작한다. (SPEC §4.7)
     *
     * 첫 탭은 시작일, 둘째 탭은 종료일이다. 종료일을 시작일보다 앞에 찍으면
     * 되묻지 않고 자동으로 뒤집어 정렬한다.
     */
    fun onDateTap(date: LocalDate) {
        _uiState.update { state ->
            if (state.pattern != TripPattern.CUSTOM) return@update state
            if (state.awaitingEndDate && state.start != null) {
                val (start, end) = listOf(state.start, date).sorted().let { it[0] to it[1] }
                state.copy(start = start, end = end, awaitingEndDate = false)
            } else {
                state.copy(start = date, end = null, awaitingEndDate = true)
            }
        }
    }
}

/**
 * 종목 기본값. **이전 선택 → 하프 우선 → 첫 종목** 순이다. (SPEC §4.8)
 *
 * 위저드 진입 시점에는 "이전 선택" 이 없으므로 여기서는 뒤 두 단계만 본다 —
 * 진입 후 사용자가 고른 값은 [WizardViewModel.start] 의 같은 대회 재진입 가드가 지킨다.
 *
 * 대회가 종목을 하나도 안 열어도 세그먼트는 4종을 상시 노출하므로(§4.8) 하프로 떨어진다.
 */
private fun defaultEventOf(race: RaceSummary): EventType {
    val events = stdEvents(race.eventTypes)
    return when {
        EventType.HALF in events -> EventType.HALF
        else -> events.firstOrNull() ?: EventType.HALF
    }
}

/**
 * 패턴에 맞춰 start·end를 다시 계산한다. 대회가 없으면 그대로 둔다.
 *
 * 오프셋 적용은 [TripPattern.rangeOf]가 한다 — §5.2 규칙이라 `domain`이 갖고 있다.
 */
private fun WizardUiState.withPattern(pattern: TripPattern): WizardUiState {
    val raceDate = race?.date ?: return copy(pattern = pattern)
    // 직접 선택이면 null — 날짜를 비우고 첫 탭을 기다린다.
    val range = pattern.rangeOf(raceDate)
    return copy(
        pattern = pattern,
        start = range?.start,
        end = range?.endInclusive,
        awaitingEndDate = false,
    )
}
