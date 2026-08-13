package com.runninggu.app.ui.racedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.ui.favorite.FavoriteStore
import com.runninggu.app.ui.sample.SampleData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * S3 대회 상세 ViewModel. (SPEC §4.6 · AP-11)
 *
 * TODO(AP-14): 임시 데이터를 백엔드로 교체한다.
 *  - [load]        → `GET /api/contests/{id}`            (API 명세 §3-4)
 *  - [loadFestivals] → `GET /api/contests/{id}/festivals` (API 명세 §3-5)
 * TODO(AP-21): 찜은 [FavoriteStore] 참고 — 서버 동기화·게스트 로그인 유도.
 */
class RaceDetailViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(RaceDetailUiState())
    val uiState: StateFlow<RaceDetailUiState> = _uiState.asStateFlow()

    /** 찜 토글 스낵바. 소비하면 [onMessageShown]으로 비운다. (SPEC §3-4) */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var raceId: String? = null

    init {
        viewModelScope.launch {
            FavoriteStore.favoriteIds.collect { ids ->
                _uiState.update { it.copy(isFavorite = raceId in ids) }
            }
        }
    }

    /** 화면 진입 시 1회 호출. 같은 대회면 재조회하지 않는다(회전·재구성 대비). */
    fun start(raceId: String) {
        if (this.raceId == raceId) return
        this.raceId = raceId
        load()
    }

    fun load() {
        val id = raceId ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(phase = RaceDetailUiState.Phase.LOADING, errorMessage = null)
            }
            delay(LOADING_DELAY_MS) // 임시 — 실제 조회로 교체하면 제거한다.

            val race = SampleData.raceById(id)
            if (race == null) {
                // 404 CONTEST_NOT_FOUND (API 명세 §3-4)
                _uiState.update { it.copy(phase = RaceDetailUiState.Phase.NOT_FOUND) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    phase = RaceDetailUiState.Phase.LOADED,
                    race = race,
                    isFavorite = FavoriteStore.isFavorite(id),
                )
            }
            loadFestivals()
        }
    }

    /** 인근 축제(M3). 대회 조회와 독립이라 실패해도 상세 본문은 유지된다. (SPEC §4.6) */
    fun loadFestivals() {
        val id = raceId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(festivalPhase = RaceDetailUiState.Phase.LOADING) }
            delay(FESTIVAL_DELAY_MS) // 임시 — 외부 API 경유라 본문보다 늦게 온다.

            _uiState.update {
                it.copy(
                    festivalPhase = RaceDetailUiState.Phase.LOADED,
                    festivals = SampleData.nearbyFestivals(id),
                )
            }
        }
    }

    /** 하트 토글. 앱바·카드 어디서 눌러도 같은 값을 본다. (SPEC §4.6 · 결정-16) */
    fun onFavoriteToggle() {
        val id = raceId ?: return
        _message.value = if (FavoriteStore.toggle(id)) "찜했어요" else "찜을 해제했어요"
    }

    fun onMessageShown() {
        _message.value = null
    }

    private companion object {
        const val LOADING_DELAY_MS = 300L
        const val FESTIVAL_DELAY_MS = 700L
    }
}
