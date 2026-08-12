package com.runninggu.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

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
            _uiState.value = HomeUiState.Content(
                featured = SampleHomeData.featured,
                closingSoon = SampleHomeData.closingSoon,
                festivals = SampleHomeData.festivals,
            )
        }
    }

    private companion object {
        const val LOADING_DELAY_MS = 400L
    }
}

/**
 * 화면 확인용 임시 데이터. 실제 데이터가 붙으면 삭제한다.
 * 값은 목업 v2(docs/mockup-design)의 예시를 따랐다.
 */
private object SampleHomeData {

    private val today: LocalDate = LocalDate.now()

    val featured = RaceSummary(
        id = "sejong-lake",
        name = "세종 호수공원 마라톤",
        region = "세종",
        venue = "세종 호수공원",
        date = today.plusDays(21),
        startTime = "08:00",
        regEnd = today.plusDays(9),
        eventTypes = listOf("하프", "10K", "5K"),
        source = "마라톤온라인",
        isRegistrationOpen = true,
    )

    val closingSoon = listOf(
        RaceSummary(
            id = "seoul-hangang",
            name = "서울 한강 러닝 페스티벌",
            region = "서울",
            venue = "여의도한강공원",
            date = today.plusDays(18),
            startTime = "09:00",
            regEnd = today.plusDays(4),
            eventTypes = listOf("10K", "5K"),
            source = "마라톤온라인",
            isRegistrationOpen = true,
        ),
        featured,
        RaceSummary(
            id = "busan-sea",
            name = "부산 바다마라톤",
            region = "부산",
            venue = "광안리해수욕장",
            date = today.plusDays(40),
            startTime = "08:30",
            regEnd = today.plusDays(27),
            eventTypes = listOf("풀", "하프", "10K"),
            source = "마라톤온라인",
            isRegistrationOpen = true,
        ),
        RaceSummary(
            id = "jeju-olle",
            name = "제주 올레 트레일런",
            region = "제주",
            venue = "성산일출봉 일원",
            date = today.plusDays(75),
            startTime = "07:30",
            regEnd = today.plusDays(62),
            eventTypes = listOf("하프", "10K"),
            source = "로드런",
            isRegistrationOpen = true,
        ),
    )

    val festivals = listOf(
        FestivalSummary("fest-busan", "부산 바다축제", "부산", "08.01~08.09", isOngoing = true),
        FestivalSummary("fest-bonghwa", "봉화 은어축제", "경북", "08.01~08.10", isOngoing = true),
        FestivalSummary("fest-sejong", "세종 호수공원 물빛축제", "세종", "08.20~08.24", isOngoing = false),
        FestivalSummary("fest-pyeongchang", "평창 백일홍축제", "강원", "08.28~09.13", isOngoing = false),
    )
}
