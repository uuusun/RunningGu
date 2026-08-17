package com.runninggu.app.ui.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * S7 동선 결과 ViewModel. (SPEC §4.10 · AP-11)
 *
 * **동선을 만들지 않는다.** 위저드 선택을 `POST /itineraries/generate` 요청으로 옮겨
 * 서버에 맡기고(결정-41), 받은 응답을 화면 상태로 들고 있는다. 저장 전 USER 블록 편집만
 * 앱 몫이다(§5.7).
 *
 * TODO(AP-14): [FakeItineraryRepository] 를 Retrofit 구현으로 교체한다.
 */
class ResultViewModel(
    private val repository: ItineraryRepository = FakeItineraryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResultUiState())
    val uiState: StateFlow<ResultUiState> = _uiState.asStateFlow()

    private var lastRequest: GenerateItineraryRequest? = null
    private var lastRegion: String = ""

    /** 위저드 상태로 생성을 요청한다. 같은 조건이면 다시 부르지 않는다. */
    fun generate(wizard: WizardUiState) {
        val request = wizard.toRequestOrNull() ?: return
        if (request == lastRequest && _uiState.value.phase == ResultUiState.Phase.CONTENT) return
        lastRequest = request
        lastRegion = wizard.race?.region.orEmpty()
        send(request)
    }

    /** 오류 상태의 [다시 시도]. 같은 입력으로 재요청한다. (SPEC §4.10) */
    fun retry() {
        lastRequest?.let(::send)
    }

    /** 일자 탭 선택. 지도와 타임라인이 함께 따라간다. (SPEC §4.10) */
    fun onDaySelect(index: Int) {
        _uiState.update { it.copy(activeDayIndex = index) }
    }

    private fun send(request: GenerateItineraryRequest) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(phase = ResultUiState.Phase.LOADING, errorMessage = null)
            }
            val outcome = runCatching { repository.generate(request) }
            _uiState.value = outcome.fold(
                onSuccess = { result ->
                    ResultUiState(
                        // 200 인데 days=[] 면 오류가 아니라 빈 상태다 (API 명세 §5-1 · SPEC §4.10).
                        phase = if (result.days.isEmpty()) {
                            ResultUiState.Phase.EMPTY
                        } else {
                            ResultUiState.Phase.CONTENT
                        },
                        result = result,
                        event = request.event,
                        region = lastRegion,
                    )
                },
                onFailure = {
                    // 네트워크·timeout·4xx/5xx 는 Error 이며 Empty 로 강등하지 않는다 (API 명세 §5-1).
                    ResultUiState(
                        phase = ResultUiState.Phase.ERROR,
                        event = request.event,
                        region = lastRegion,
                        errorMessage = "동선을 만들지 못했어요.",
                    )
                },
            )
        }
    }
}

/** 위저드 상태 → 생성 요청. 일정이 덜 정해졌으면 null. */
private fun WizardUiState.toRequestOrNull(): GenerateItineraryRequest? {
    val race = race ?: return null
    val start = start ?: return null
    val end = end ?: return null
    return GenerateItineraryRequest(
        contestId = race.id,
        startDate = start,
        endDate = end,
        event = event,
        themes = themes,
    )
    // TODO(AP-11): S6 숙소 선택이 붙으면 hotel 을 함께 보낸다. null 이면 대회장 중심이다(§4.9).
}
