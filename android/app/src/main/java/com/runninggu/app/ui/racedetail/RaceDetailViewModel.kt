package com.runninggu.app.ui.racedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.ContestRepository
import com.runninggu.app.data.remote.apiErrorCode
import com.runninggu.app.ui.favorite.FavoriteStore
import com.runninggu.app.ui.favorite.FavoriteToggleResult
import com.runninggu.app.ui.model.toNearbyFestival
import com.runninggu.app.ui.model.toRaceSummary
import com.runninggu.app.ui.userMessageOrDefault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * S3 대회 상세 ViewModel. (SPEC §4.6 · AP-11 · AP-14)
 *
 * 본문과 인근 축제는 **서버 호출이 다르다**(§3-4 / §3-5). 축제는 KTO 프록시라 `502` 가
 * 실제로 나는데, 그때 대회 본문까지 가려지면 안 되므로 상태를 따로 둔다.
 */
class RaceDetailViewModel(
    private val repository: ContestRepository = ServiceLocator.contestRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RaceDetailUiState())
    val uiState: StateFlow<RaceDetailUiState> = _uiState.asStateFlow()

    /** 찜 토글 스낵바. 소비하면 [onMessageShown]으로 비운다. (SPEC §3-4) */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 게스트가 하트를 눌렀다. 화면이 로그인으로 유도한다. (SPEC §4.6 · 결정-4) */
    private val _loginRequired = MutableStateFlow(false)
    val loginRequired: StateFlow<Boolean> = _loginRequired.asStateFlow()

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

    /**
     * 대회 본문. (`GET /api/contests/{id}` · API 명세 §3-4)
     *
     * **canonical id 가 없는 대회는 부르지 않는다.** 번들·오프라인 항목은 크롤 원천 문자열을
     * id 로 갖는데(`roadrun-41543`), 그걸 숫자로 바꿔 보내면 서버에 없는 대회를 묻는 꼴이다.
     * 서버에 없는 것은 사실이므로 [NOT_FOUND][RaceDetailUiState.Phase.NOT_FOUND] 로 둔다 —
     * [다시 시도]를 줘도 생기지 않는다는 점에서 `404` 와 성격이 같다.
     */
    fun load() {
        val id = raceId ?: return
        val serverId = id.toLongOrNull()
        if (serverId == null) {
            _uiState.update { it.copy(phase = RaceDetailUiState.Phase.NOT_FOUND) }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(phase = RaceDetailUiState.Phase.LOADING, errorMessage = null)
            }
            val race = try {
                repository.detail(serverId).toRaceSummary()
            } catch (e: ApiException) {
                _uiState.update {
                    if (e.apiErrorCode() == ApiErrorCode.NOT_FOUND) {
                        // 404 CONTEST_NOT_FOUND — 다시 눌러도 소용없다 (§3-4)
                        it.copy(phase = RaceDetailUiState.Phase.NOT_FOUND)
                    } else {
                        it.copy(
                            phase = RaceDetailUiState.Phase.ERROR,
                            errorMessage = e.userMessageOrDefault(),
                        )
                    }
                }
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

    /**
     * 인근 축제(M3). 대회 조회와 독립이라 실패해도 상세 본문은 유지된다. (SPEC §4.6)
     *
     * **비활성 대회에서는 아예 부르지 않는다**(§3-4 · 결정-46). 화면에서 섹션을 숨기는
     * 것만으로는 부족하다 — `GET /contests/{id}/festivals` 는 KTO 를 거치는 외부 프록시라
     * 호출 자체가 비용이고, 그 호출을 막으라는 게 계약이다(#80 리뷰).
     *
     * 그리는 쪽([RaceDetailUiState.showFestivalSection])이 아니라 여기서 막는 이유는
     * [다시 시도] 버튼이 이 함수를 직접 부르기 때문이다.
     */
    fun loadFestivals() {
        val serverId = raceId?.toLongOrNull() ?: return
        if (_uiState.value.race?.active == false) return
        viewModelScope.launch {
            _uiState.update { it.copy(festivalPhase = RaceDetailUiState.FestivalPhase.LOADING) }
            try {
                val festivals = repository.festivals(serverId).map { it.toNearbyFestival() }
                _uiState.update {
                    it.copy(
                        festivalPhase = RaceDetailUiState.FestivalPhase.LOADED,
                        festivals = festivals,
                    )
                }
            } catch (e: ApiException) {
                // 409 는 좌표가 없다는 뜻이라 재시도가 헛돈다 — 별도 상태다 (§3-5)
                val phase = if (e.apiErrorCode() == ApiErrorCode.CONTEST_LOCATION_UNAVAILABLE) {
                    RaceDetailUiState.FestivalPhase.LOCATION_UNAVAILABLE
                } else {
                    RaceDetailUiState.FestivalPhase.ERROR
                }
                _uiState.update { it.copy(festivalPhase = phase, festivals = emptyList()) }
            }
        }
    }

    /** 하트 토글. 앱바·카드 어디서 눌러도 같은 값을 본다. (SPEC §4.6 · 결정-16 · AP-21) */
    fun onFavoriteToggle() {
        val id = raceId ?: return
        viewModelScope.launch {
            when (val result = FavoriteStore.toggle(id)) {
                FavoriteToggleResult.LoginRequired -> _loginRequired.value = true
                is FavoriteToggleResult.Done ->
                    _message.value = if (result.nowFavorite) "찜했어요" else "찜을 해제했어요"
                FavoriteToggleResult.Failed ->
                    _message.value = "찜을 저장하지 못했어요. 잠시 후 다시 시도해 주세요."
            }
        }
    }

    fun onMessageShown() {
        _message.value = null
    }

    fun onLoginRequiredShown() {
        _loginRequired.value = false
    }

}
