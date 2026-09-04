package com.runninggu.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.ContestRepository
import com.runninggu.app.data.repository.FestivalRepository
import com.runninggu.app.ui.common.DataOrigin
import com.runninggu.app.ui.common.SectionState
import com.runninggu.app.ui.model.toFestivalSummary
import com.runninggu.app.ui.model.toRaceSummary
import com.runninggu.app.ui.sectionMessage
import kotlinx.coroutines.Job
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
 * 두 영역 모두 **서버를 본다**(AP-14). 마감 임박은 우리 DB(canonical), 축제는 KTO 프록시다.
 */
class HomeViewModel(
    private val contestRepository: ContestRepository = ServiceLocator.contestRepository,
    private val festivalRepository: FestivalRepository = ServiceLocator.festivalRepository,
) : ViewModel() {

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

    /**
     * 마감 임박만 다시 조회한다. (SPEC §4.4-3 · API 명세 §3-3)
     *
     * **거르고 자르는 일은 서버가 한다.** 접수중 판정도 `limit` 도 계약이라(§3-3),
     * 앱이 한 번 더 거르면 서버가 4건을 줬는데 화면에 3건이 뜨는 일이 생긴다.
     */
    fun loadClosingSoon() {
        closingSoonJob?.cancel()
        closingSoonJob = viewModelScope.launch {
            _uiState.update { it.copy(closingSoon = SectionState.Loading) }
            val next = try {
                val result = contestRepository.closingSoon(CLOSING_SOON_LIMIT)
                result.items
                    .map { it.contest.toRaceSummary() }
                    // 되살린 목록이면 **언제 것인지를 함께 올린다.** 캐시가 0건으로 걸러졌을
                    // 때는 Empty 라 출처가 붙을 자리가 없는데, 그건 보여줄 목록 자체가
                    // 없는 것이라 "언제 것" 을 말할 대상도 없다(#276)
                    .toSectionState(result.cachedAt?.let { DataOrigin.LocalCache(it) } ?: DataOrigin.Server)
            } catch (e: ApiException) {
                SectionState.Error(e.sectionMessage())
            }
            _uiState.update { it.copy(closingSoon = next) }
        }
    }

    /**
     * 축제만 다시 조회한다. (SPEC §4.4-4 · API 명세 §4-1)
     *
     * **위치를 쓰지 않는다.** 홈은 전국 월간 목록이다(D-04 · 결정-28) — 홈에 들어왔다는
     * 이유로 위치 권한을 묻지 않기로 했다.
     *
     * 여기가 KTO 프록시라 `502`·`504` 가 실제로 난다. 실패해도 [loadClosingSoon] 결과는
     * 그대로 남는다(AGENTS 2장-5).
     */
    fun loadFestivals() {
        festivalsJob?.cancel()
        festivalsJob = viewModelScope.launch {
            _uiState.update { it.copy(festivals = SectionState.Loading) }
            val next = try {
                festivalRepository.list().map { it.toFestivalSummary() }.toSectionState()
            } catch (e: ApiException) {
                SectionState.Error(e.sectionMessage())
            }
            _uiState.update { it.copy(festivals = next) }
        }
    }

    private companion object {
        /**
         * 홈 마감 임박 노출 건수. (SPEC §4.4-3 🔒 · 결정-28)
         *
         * **서버 계약과 같은 값이어야 한다.** `GET /api/contests/closing-soon` 의 `limit` 은
         * 허용 범위가 `1~4` 라, 이 값이 넘으면 붙는 순간 `400 VALIDATION_FAILED` 다
         * (API 명세 §3-3 · #102 리뷰).
         */
        const val CLOSING_SOON_LIMIT = 4
    }
}

/**
 * 조회 결과를 영역 상태로. 빈 목록은 [SectionState.Empty] 다.
 *
 * **빈 목록에도 출처를 넘긴다.** 캐시로 되살렸는데 0건이면 화면이 "언제 것" 을 말할 수
 * 있어야 한다 — 여기서 떨어뜨리면 repository 가 준 `cachedAt` 이 사라진다(#283 리뷰).
 *
 * **정상 0건과 오류를 섞지 않기 위해** 여기서 한 번만 판단한다 — 화면마다 `isEmpty()` 를
 * 따로 보면 어디선가 빠진다(API 명세 §0-3).
 */
internal fun <T> List<T>.toSectionState(
    origin: DataOrigin = DataOrigin.Server,
): SectionState<List<T>> =
    if (isEmpty()) SectionState.Empty(origin) else SectionState.Content(this, origin)
