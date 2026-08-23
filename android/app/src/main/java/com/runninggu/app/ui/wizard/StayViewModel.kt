package com.runninggu.app.ui.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.domain.PoiCategory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.runninggu.app.data.model.PoiItem
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.repository.PoiRepository

/**
 * S6 숙소 선택의 UI 계약. (SPEC §4.9 · §3-5)
 *
 * 숙소는 **선택 사항**이라 미선택도 정상 흐름이다 — CTA 문구가 갈릴 뿐이다.
 */
data class StayUiState(
    val phase: Phase = Phase.LOADING,
    val query: String = "",
    val items: List<PoiItem> = emptyList(),
    /** `LIVE` · `SAMPLE` · `SYNTH` 소스 배지. (NFR-2) */
    val source: String = "",
    val errorMessage: String? = null,
) {
    enum class Phase { LOADING, CONTENT, EMPTY, ERROR }
}

/**
 * S6 숙소 선택 ViewModel. (SPEC §4.9 · AP-11)
 *
 * 최초에는 대회장 주변 추천 8건을 받고, 검색어가 2자 이상이면 **500ms debounce** 후
 * 같은 API 를 `query` 와 함께 부른다.
 *
 * 고른 숙소는 [WizardViewModel] 에 넣는다 — S7 이 `POST /itineraries/generate` 요청에
 * 실어 보내야 하기 때문이다.
 *
 * 저장소는 서버 `GET /api/pois` 를 부른다(AP-14, 2026-08-22 연결).
 */
class StayViewModel(
    /**
     * 기본은 **서버 저장소**다. (AP-14 · `GET /api/pois`)
     *
     * 테스트·미리보기에서는 생성자로 가짜 저장소를 바꿔 끼운다 — 화면은 안 건드린다.
     */
    private val repository: PoiRepository = ServiceLocator.poiRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StayUiState())
    val uiState: StateFlow<StayUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var center: Pair<Double, Double>? = null

    /** 화면 진입. 대회장 기준 추천을 받는다. */
    fun start(lat: Double, lng: Double) {
        if (center != null) return
        center = lat to lng
        load(query = null)
    }

    /**
     * 검색어 입력. (SPEC §4.9)
     *
     * 2자 미만이면 서버가 `400 VALIDATION_FAILED` 를 주므로 호출하지 않고, 대신 추천 목록으로
     * 돌아간다. 타이핑마다 부르지 않도록 앞선 요청은 취소한다.
     */
    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val trimmed = query.filterNot { it.isWhitespace() }
            if (trimmed.length < PoiRepository.MIN_QUERY_LENGTH) {
                load(query = null)
                return@launch
            }
            delay(DEBOUNCE_MS)
            load(query = query.trim())
        }
    }

    fun retry() {
        val current = _uiState.value.query.trim()
        load(query = current.takeIf { it.length >= PoiRepository.MIN_QUERY_LENGTH })
    }

    private fun load(query: String?) {
        val (lat, lng) = center ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(phase = StayUiState.Phase.LOADING, errorMessage = null) }
            val outcome = runCatching {
                repository.search(PoiCategory.LODGING, lat, lng, query)
            }
            _uiState.update { state ->
                outcome.fold(
                    onSuccess = { result ->
                        state.copy(
                            phase = if (result.items.isEmpty()) {
                                StayUiState.Phase.EMPTY
                            } else {
                                StayUiState.Phase.CONTENT
                            },
                            items = result.items,
                            source = result.source,
                        )
                    },
                    onFailure = {
                        state.copy(
                            phase = StayUiState.Phase.ERROR,
                            errorMessage = "숙소를 불러오지 못했어요.",
                        )
                    },
                )
            }
        }
    }

    private companion object {
        /** SPEC §4.9 — 입력 후 500ms debounce. */
        const val DEBOUNCE_MS = 500L
    }
}
