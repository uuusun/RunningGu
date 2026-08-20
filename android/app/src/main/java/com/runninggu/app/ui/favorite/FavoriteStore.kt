package com.runninggu.app.ui.favorite

import com.runninggu.app.ui.auth.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 찜 토글 결과. 화면이 스낵바 문구와 로그인 유도를 가르는 데 쓴다.
 *
 * [LoginRequired] 는 오류가 아니라 정상 분기다 — 게스트는 탐색만 되고 저장은 로그인이
 * 필요하다(SPEC 결정-4 · §4.5 "게스트가 탭하면 로그인 유도").
 */
sealed interface FavoriteToggleResult {
    /** 게스트가 하트를 눌렀다. 화면은 로그인으로 유도하고 **찜을 예약하지 않는다**(D-27). */
    data object LoginRequired : FavoriteToggleResult

    /** 서버 반영 완료. [nowFavorite] 로 "찜했어요" / "찜을 해제했어요" 를 고른다(결정-16). */
    data class Done(val nowFavorite: Boolean) : FavoriteToggleResult

    /** 서버 실패 — UI 는 이미 원래대로 되돌아갔다. */
    data object Failed : FavoriteToggleResult
}

/**
 * 찜한 대회 id 보관소. (SPEC §4.5 · 4.6 · 4.13 · 결정-16 · AP-21)
 *
 * 찜 상태는 S2 카드·S3 상세·S10 마이에 **같은 값으로** 보여야 하는데 화면마다 ViewModel 이
 * 따로라 공유할 자리가 필요하다. SSOT 는 서버이고(§9.3) 이 객체는 그 **읽기 캐시**다 —
 * 양방향 병합의 기준이 아니다.
 *
 * 토글은 **낙관적 갱신**이다. 하트를 누르는 즉시 [favoriteIds] 를 바꿔 화면이 반응하고,
 * 서버가 실패하면 되돌린다. 하트는 반응이 즉각적이어야 하는 컨트롤이라 왕복을 기다리지 않는다.
 *
 * TODO(AP-14): [FakeFavoriteRepository] 를 Retrofit 구현으로 교체하고 Room 읽기 캐시를
 *  붙인다. 화면은 [favoriteIds] 와 [toggle] 만 보므로 이 파일 내부만 바뀐다.
 */
object FavoriteStore {

    private var repository: FavoriteRepository = FakeFavoriteRepository

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    /**
     * 세션을 직접 구독해 스스로 캐시를 맞춘다.
     *
     * 화면 ViewModel 의 `viewModelScope` 에서 부르면 **로그인 직후 화면이 전환되며 그
     * ViewModel 이 죽어 조회가 취소된다.** 그러면 찜해 둔 대회의 하트가 빈 채로 남는다.
     * 이 스코프는 앱 수명과 함께 살아 있어 그런 일이 없다.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 대회별로 진행 중인 토글 요청. 새 토글이 오면 이전 것을 끊는다. */
    private val inFlight = mutableMapOf<String, Job>()

    init {
        scope.launch {
            SessionStore.session
                // 같은 사용자의 프로필 변경(닉네임 등)으로 다시 조회하지 않는다.
                .map { it != null }
                .distinctUntilChanged()
                .collect { loggedIn -> if (loggedIn) refresh() else clear() }
        }
    }

    fun isFavorite(raceId: String): Boolean = raceId in _favoriteIds.value

    /**
     * 서버 목록으로 캐시를 채운다. 세션이 생기면 자동으로 불리고, 마이 진입에서도 부른다.
     *
     * 게스트면 캐시를 비우기만 한다 — 이전 사용자의 찜이 남아 보이면 안 된다.
     */
    suspend fun refresh() {
        if (!SessionStore.isLoggedIn) {
            _favoriteIds.value = emptySet()
            return
        }
        repository.loadFavoriteIds().onSuccess { ids -> _favoriteIds.value = ids }
        // 실패는 조용히 둔다 — 마지막 성공 목록을 계속 보여주는 편이 낫다(§4.13 오프라인 규칙).
    }

    /**
     * 찜 토글. 게스트면 [FavoriteToggleResult.LoginRequired] 를 돌려주고 아무것도 바꾸지 않는다.
     *
     * 서버 호출 전에 UI 를 먼저 바꾸고, 실패하면 되돌린다. 다만 **연타를 고려한다**:
     *
     * - `PUT` 과 `DELETE` 는 서로 다른 연산이라 멱등해도 **도착 순서가 뒤집히면** 서버 최종
     *   상태가 UI 와 갈린다. 그래서 대회별로 진행 중인 요청을 취소하고 마지막 것만 보낸다.
     * - 롤백도 조건부다. 찜 → (요청 중) 해제 → 첫 요청 실패 순서에서 무조건 되돌리면
     *   **사용자가 마지막으로 누른 "해제" 를 덮어쓴다.** 내 요청이 아직 최신일 때만 되돌린다.
     */
    suspend fun toggle(raceId: String): FavoriteToggleResult {
        if (!SessionStore.isLoggedIn) return FavoriteToggleResult.LoginRequired

        val nowFavorite = raceId !in _favoriteIds.value
        applyLocally(raceId, nowFavorite)

        // 같은 대회의 이전 요청을 끊는다. 마지막 의도만 서버에 남는다.
        inFlight.remove(raceId)?.cancel()

        val job = scope.launch {
            val outcome = if (nowFavorite) repository.add(raceId) else repository.remove(raceId)
            if (outcome.isFailure && _favoriteIds.value.contains(raceId) == nowFavorite) {
                applyLocally(raceId, !nowFavorite) // 내 결과가 아직 최신일 때만 롤백
            }
        }
        inFlight[raceId] = job
        job.join()

        return when {
            job.isCancelled -> FavoriteToggleResult.Done(nowFavorite) // 더 최신 토글이 이어받았다
            _favoriteIds.value.contains(raceId) == nowFavorite -> FavoriteToggleResult.Done(nowFavorite)
            else -> FavoriteToggleResult.Failed
        }
    }

    /** 로그아웃·탈퇴 시 캐시를 비운다. 진행 중인 요청도 끊는다. */
    fun clear() {
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        _favoriteIds.value = emptySet()
    }

    private fun applyLocally(raceId: String, favorite: Boolean) {
        _favoriteIds.update { current ->
            if (favorite) current + raceId else current - raceId
        }
    }
}
