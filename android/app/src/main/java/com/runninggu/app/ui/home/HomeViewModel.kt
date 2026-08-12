package com.runninggu.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.ui.model.RegistrationStatus
import com.runninggu.app.ui.model.registrationStatus
import com.runninggu.app.ui.sample.SampleData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * S1 홈 ViewModel. (SPEC §2.4 · AP-09)
 *
 * TODO(AP-14): 임시 데이터를 백엔드 API 클라이언트(RaceRepository·FestivalRepository)로 교체한다.
 * 화면은 [HomeUiState]만 보므로 이 클래스 내부만 바뀐다.
 */
class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    init {
        load()
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            delay(LOADING_DELAY_MS) // 임시 — 실제 조회로 교체하면 제거한다.

            // 마감 임박: 접수중 ∧ regEnd 임박순 상위 6건. (SPEC §4.4-3)
            val closingSoon = SampleData.races
                .filter { it.registrationStatus() == RegistrationStatus.OPEN && it.regEnd != null }
                .sortedBy { it.regEnd }
                .take(CLOSING_SOON_LIMIT)

            _uiState.value = HomeUiState.Content(
                featured = closingSoon.firstOrNull(),
                closingSoon = closingSoon,
                festivals = SampleData.festivals,
            )
        }
    }

    private companion object {
        const val LOADING_DELAY_MS = 400L
        const val CLOSING_SOON_LIMIT = 6
    }
}
