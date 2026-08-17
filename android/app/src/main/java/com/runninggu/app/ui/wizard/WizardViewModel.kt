package com.runninggu.app.ui.wizard

import androidx.lifecycle.ViewModel
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.PoiCategory
import com.runninggu.app.domain.TripPattern
import com.runninggu.app.domain.stdEvents
import com.runninggu.app.ui.model.RaceSummary
import com.runninggu.app.ui.sample.SampleData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate

/**
 * 위저드(S4~S7) 공유 ViewModel. (SPEC §2.4 · AP-11)
 *
 * wizard 그래프 스코프로 만들어 S4~S7이 같은 인스턴스를 본다 —
 * 생성 위치는 [com.runninggu.app.ui.navigation.RunningGuNavHost] 참고.
 *
 * TODO(AP-14): [start]의 대회 조회를 `GET /api/contests/{id}`로 교체한다.
 * TODO(AP-11): S5~S7 진행에 따라 event·themes·stay·days 액션을 이 클래스에 이어 붙인다.
 */
class WizardViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(WizardUiState())
    val uiState: StateFlow<WizardUiState> = _uiState.asStateFlow()

    /**
     * 위저드 진입. 대회를 싣고 기본 패턴(전후로)·기본 종목으로 채운다.
     *
     * `SELECT_RACE` 계약(SPEC §2.4)에 해당한다.
     */
    fun start(raceId: String) {
        if (_uiState.value.race?.id == raceId) return
        val race = SampleData.raceById(raceId) ?: return
        _uiState.value = WizardUiState(race = race, event = defaultEventOf(race))
            .withPattern(TripPattern.DEFAULT)
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
