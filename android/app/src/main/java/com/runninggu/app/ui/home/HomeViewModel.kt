package com.runninggu.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.domain.RegistrationStatus
import com.runninggu.app.ui.common.SectionState
import com.runninggu.app.ui.model.registrationStatus
import com.runninggu.app.ui.sample.SampleData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * S1 홈 ViewModel. (SPEC §2.4 · AP-09)
 *
 * **영역을 따로 조회한다.** 마감 임박과 축제는 다른 API 라 한쪽이 실패해도 다른 쪽은
 * 그대로 보여야 한다(AGENTS 2장-5). 재시도도 영역별이다 — KTO 가 죽었다고 우리 DB 에서
 * 오는 대회 목록까지 다시 부를 이유가 없다.
 *
 * TODO(AP-14): 임시 데이터를 `ServiceLocator.contestRepository.closingSoon()` 과
 *  축제 저장소로 교체한다. **영역 구조는 그대로 두고 안쪽만 바뀐다** — 서버가
 *  `GET /api/contests/closing-soon` 을 이미 내주고 있어서 마감 임박이 먼저 붙는다.
 */
class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** 영역마다 따로 끊는다. 한쪽 재시도가 다른 쪽 조회를 취소하면 안 된다. */
    private var closingSoonJob: Job? = null
    private var festivalsJob: Job? = null

    init {
        loadClosingSoon()
        loadFestivals()
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    /** 마감 임박만 다시 조회한다. (SPEC §4.4-3) */
    fun loadClosingSoon() {
        closingSoonJob?.cancel()
        closingSoonJob = viewModelScope.launch {
            _uiState.update { it.copy(closingSoon = SectionState.Loading) }
            delay(LOADING_DELAY_MS) // 임시 — 실제 조회로 교체하면 제거한다.

            // 접수중 ∧ regEnd 임박순 상위 6건. (SPEC §4.4-3)
            val races = SampleData.races
                .filter { it.registrationStatus() == RegistrationStatus.OPEN && it.regEnd != null }
                .sortedBy { it.regEnd }
                .take(CLOSING_SOON_LIMIT)

            _uiState.update { it.copy(closingSoon = races.toSectionState()) }
        }
    }

    /** 축제만 다시 조회한다. (SPEC §4.4-4) */
    fun loadFestivals() {
        festivalsJob?.cancel()
        festivalsJob = viewModelScope.launch {
            _uiState.update { it.copy(festivals = SectionState.Loading) }
            delay(LOADING_DELAY_MS) // 임시 — 실제 조회로 교체하면 제거한다.

            _uiState.update { it.copy(festivals = SampleData.festivals.toSectionState()) }
        }
    }

    private companion object {
        const val LOADING_DELAY_MS = 400L
        const val CLOSING_SOON_LIMIT = 6
    }
}

/**
 * 조회 결과를 영역 상태로. 빈 목록은 [SectionState.Empty] 다.
 *
 * **정상 0건과 오류를 섞지 않기 위해** 여기서 한 번만 판단한다 — 화면마다 `isEmpty()` 를
 * 따로 보면 어디선가 빠진다(API 명세 §0-3).
 */
internal fun <T> List<T>.toSectionState(): SectionState<List<T>> =
    if (isEmpty()) SectionState.Empty else SectionState.Content(this)
