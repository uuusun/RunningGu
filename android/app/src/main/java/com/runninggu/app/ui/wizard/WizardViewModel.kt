package com.runninggu.app.ui.wizard

import androidx.lifecycle.ViewModel
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
     * 위저드 진입. 대회를 싣고 기본 패턴(전후로)으로 기간을 계산한다.
     *
     * `SELECT_RACE` 계약(SPEC §2.4)의 일정 부분에 해당한다. 종목 기본값 설정은
     * S5에서 붙인다.
     */
    fun start(raceId: String) {
        if (_uiState.value.race?.id == raceId) return
        val race = SampleData.raceById(raceId) ?: return
        _uiState.value = WizardUiState(race = race).withPattern(TripPattern.AROUND)
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

/** 패턴에 맞춰 start·end를 다시 계산한다. 대회가 없으면 그대로 둔다. */
private fun WizardUiState.withPattern(pattern: TripPattern): WizardUiState {
    val raceDate = race?.date ?: return copy(pattern = pattern)
    val offsetStart = pattern.offsetStart
    val offsetEnd = pattern.offsetEnd
    return if (offsetStart == null || offsetEnd == null) {
        // 직접 선택 — 날짜를 비우고 첫 탭을 기다린다.
        copy(pattern = pattern, start = null, end = null, awaitingEndDate = false)
    } else {
        copy(
            pattern = pattern,
            start = raceDate.plusDays(offsetStart),
            end = raceDate.plusDays(offsetEnd),
            awaitingEndDate = false,
        )
    }
}
