package com.runninggu.app.ui.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.model.SavedItinerary
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.model.SavedCourse
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.ItineraryRepository
import com.runninggu.app.data.repository.SavedCourseRepository
import com.runninggu.app.data.repository.FavoriteRepository
import com.runninggu.app.ui.favorite.FavoriteStore
import com.runninggu.app.ui.favorite.FavoriteToggleResult
import com.runninggu.app.ui.model.RaceSummary
import com.runninggu.app.ui.model.toRaceSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** S10 세그먼트 3개. (SPEC §4.13) */
enum class MySegment(val label: String) {
    ITINERARY("동선"),
    COURSE("러닝코스"),
    FAVORITE("찜한 대회"),
}

/**
 * 저장한 러닝코스는 `data/model` 의 [SavedCourse] 를 그대로 쓴다. P0는 saved만이다
 * (SPEC §4.13 · 결정 D-25).
 *
 * 화면 전용 모델을 따로 두지 않는 이유는, 카드가 쓰는 값이 서버 응답의 부분집합이고
 * **상세로 넘길 canonical id(`Long`)** 가 그대로 필요하기 때문이다. 옮겨 담으면 id 를
 * 문자열로 바꿨다 되돌리는 일만 생긴다.
 */


/**
 * S10 마이의 UI 계약. (SPEC §4.13 · §3-5)
 *
 * 마이 진입 자체가 로그인 필요다(결정-4) — [profile]이 null(게스트)이면 화면은
 * 로그인 유도만 그린다.
 */
data class MyUiState(
    val profile: SessionProfile? = null,
    val segment: MySegment = MySegment.ITINERARY,
    val itineraries: SavedItinerariesState = SavedItinerariesState.Loading,
    val courses: SavedCoursesState = SavedCoursesState.Loading,
    val favorites: FavoriteRacesState = FavoriteRacesState.Loading,
    /**
     * 지금 찜 상태. **목록과 따로 든다.**
     *
     * 목록은 서버가 준 "찜한 대회" 이고 이 집합은 [FavoriteStore] 의 현재 값이다. S10 에서
     * 하트를 끄면 카드는 남되 하트만 꺼져야 한다 — 카드가 즉시 사라지면 잘못 눌렀을 때
     * 되돌릴 방법이 없다.
     */
    val favoriteIds: Set<String> = emptySet(),
)

/**
 * [찜한 대회] 세그먼트의 상태. (API 명세 §7-C · 화면-API 매핑표 S10)
 *
 * **[SavedCoursesState] 와 같은 모양이다.** 둘 다 Pageable 목록이고 [더 보기] 동작도 같아서,
 * 다른 규칙으로 갈라지면 다음 사람이 어느 쪽을 믿을지 모른다(#163).
 *
 * TODO(#49): 공용 `SectionState` 가 정해지면(PR #102) 셋을 함께 옮긴다. 지금 일반화하면
 *  쓰는 곳이 둘뿐이라 모양만 늘어난다.
 */
sealed interface FavoriteRacesState {
    data object Loading : FavoriteRacesState

    data class Content(
        /** 지금까지 받아온 것을 **이어 붙인** 목록. */
        val races: List<RaceSummary>,
        val hasNext: Boolean,
        /** 찜한 대회 전체 수. `races.size` 가 아니다 — 한 번에 20건씩 온다. */
        val totalElements: Long,
        val loadingMore: Boolean = false,
        /** 다음 장을 못 받았다. **이미 받은 목록은 지우지 않는다.** */
        val moreMessage: String? = null,
    ) : FavoriteRacesState {
        val canLoadMore: Boolean get() = hasNext && !loadingMore
    }

    /** 정상 조회했는데 0건. "찜한 대회가 없어요." */
    data object Empty : FavoriteRacesState

    /** 못 불러왔다. 재시도를 준다. */
    data class Error(val message: String) : FavoriteRacesState
}

/**
 * [러닝코스] 세그먼트의 상태. (SPEC §3-5 · 화면-API 매핑표 S10)
 *
 * **로딩·빈·오류를 구분한다.** 앞서 목록만 들고 있어서 조회 중이거나 서버가 실패해도
 * "저장한 코스가 없어요" 가 떴다 — 사용자는 다시 시도해야 할 상황인지 알 수 없다(#107 리뷰).
 *
 * 모양은 S8 의 `RegionCoursesState` 를 따른다. 같은 Pageable 목록이고 [더 보기] 동작도
 * 같아서, 두 화면이 다른 규칙으로 갈라지면 다음 사람이 어느 쪽을 믿을지 모른다.
 *
 * TODO(#49): 공용 `SectionState` 가 정해지면(PR #102) 그쪽으로 옮긴다.
 */
sealed interface SavedCoursesState {
    data object Loading : SavedCoursesState

    data class Content(
        /** 지금까지 받아온 것을 **이어 붙인** 목록. 다음 장을 받으면 뒤에 붙는다. */
        val courses: List<SavedCourse>,
        val hasNext: Boolean,
        /** 저장한 코스 전체 수. `courses.size` 가 아니다 — 한 번에 20건씩 온다. */
        val totalElements: Long,
        /** [더 보기] 로 다음 장을 받는 중. 목록은 그대로 두고 버튼만 바뀐다. */
        val loadingMore: Boolean = false,
        /** 다음 장을 못 받았다. **이미 받은 목록은 지우지 않는다.** */
        val moreMessage: String? = null,
    ) : SavedCoursesState {
        /** 더 받을 게 남았고 지금 받는 중이 아니다. */
        val canLoadMore: Boolean get() = hasNext && !loadingMore
    }

    /** 정상 조회했는데 0건. "저장한 코스가 없어요." */
    data object Empty : SavedCoursesState

    /** 못 불러왔다. 재시도를 준다. */
    data class Error(val message: String) : SavedCoursesState
}

/**
 * [동선] 세그먼트의 상태. (SPEC §3-5 · 매핑표 S10 · API 명세 §5-4)
 *
 * **[SavedCoursesState] 와 같은 모양이다.** 같은 Pageable 목록에 [더 보기] 도 같은데
 * 두 세그먼트가 다른 규칙으로 갈라지면 다음 사람이 어느 쪽을 믿을지 모른다.
 *
 * 목록만 들고 있던 때는 조회 중이거나 서버가 실패해도 **"저장한 동선이 없어요"** 가
 * 떴다 — 저장 코스에서 #107 이 고친 것과 같은 결함이 동선 쪽에 남아 있었다.
 */
sealed interface SavedItinerariesState {

    data object Loading : SavedItinerariesState

    data class Content(
        val itineraries: List<SavedItinerary>,
        val hasNext: Boolean,
        val totalElements: Long,
    ) : SavedItinerariesState

    /** 정상 조회했는데 0건. "저장한 동선이 없어요." */
    data object Empty : SavedItinerariesState

    /** 못 불러왔다. 재시도를 준다. */
    data class Error(val message: String) : SavedItinerariesState
}

/**
 * S10 마이. (SPEC §4.13 · AP-13)
 *

 * 저장소 SSOT 는 서버다 — **동선·코스·찜 목록을 모두 서버가 준다**(§5-4 · §7-A · §7-C).
 *
 * 찜은 두 갈래로 읽는다. **목록은 `GET /me/favorites`**(비활성도 유지 §7-C), **하트는
 * [FavoriteStore]** 다 — S2 카드·S3 상세와 같은 값이어야 하기 때문이다.
 *
 * TODO(#105): Room 읽기 캐시가 정해지면 조회 앞에 붙인다.
 */
class MyViewModel(
    private val savedCourseRepository: SavedCourseRepository = ServiceLocator.savedCourseRepository,
    private val itineraryRepository: ItineraryRepository = ServiceLocator.itineraryRepository,
    private val favoriteRepository: FavoriteRepository = ServiceLocator.favoriteRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MyUiState())
    val uiState: StateFlow<MyUiState> = _uiState.asStateFlow()

    /** 찜 해제 실패 등 스낵바. (SPEC §3-4) */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            SessionStore.session.collect { profile ->
                // 계정이 바뀌었다. 앞 계정으로 띄운 조회를 끊고 세대를 올린다 — 끊기 전에
                // 이미 응답이 온 코루틴이 있을 수 있어서 [sessionEpoch] 로 한 번 더 막는다.
                // 이게 없으면 로그아웃 뒤 늦게 도착한 A 계정 목록이 B 계정 화면에 남는다(#107 리뷰).
                sessionEpoch += 1
                coursesJob?.cancel()
                coursesJob = null
                itinerariesJob?.cancel()
                itinerariesJob = null
                favoritesJob?.cancel()
                favoritesJob = null
                resumedOnce = false
                _uiState.update {
                    it.copy(
                        profile = profile,
                        itineraries = if (profile == null) {
                            SavedItinerariesState.Empty
                        } else {
                            SavedItinerariesState.Loading
                        },
                        // 앞 계정의 목록을 그대로 두지 않는다. 게스트면 빈 목록, 로그인이면
                        // 곧 부를 조회의 로딩으로 시작한다.
                        courses = if (profile == null) SavedCoursesState.Empty else SavedCoursesState.Loading,
                        favorites = if (profile == null) {
                            FavoriteRacesState.Empty
                        } else {
                            FavoriteRacesState.Loading
                        },
                    )
                }
                // 서버 SSOT 를 다시 읽는다. 게스트면 캐시를 비운다 (SPEC §4.13 · AP-21).
                //
                // **기다리지 않는다.** 하트 캐시는 두 목록과 독립인데, 여기서 await 하면
                // 찜 id 를 마지막 장까지 받는 동안 동선·코스 목록이 시작조차 못 한다.
                viewModelScope.launch { FavoriteStore.refresh() }
                loadCourses() // 게스트면 스스로 빠진다
                loadItineraries()
                loadFavorites()
            }
        }
        viewModelScope.launch {
            // 하트만 따라간다. **목록을 여기서 만들지 않는다** — 찜 목록은 서버가 주고
            // (§7-C), 비활성·지난 대회도 유지하는 게 계약이라 앱이 걸러 낼 수 없다.
            FavoriteStore.favoriteIds.collect { ids ->
                _uiState.update { it.copy(favoriteIds = ids) }
            }
        }
    }

    /**
     * 화면이 다시 앞으로 나왔다. 상세에서 코스를 지우고 돌아온 경우가 이 자리다.
     *
     * 첫 번째는 건너뛴다 — [init] 의 세션 수집이 이미 목록을 불렀고, 진입할 때마다 같은 GET 을
     * 두 번 쏘게 된다. 이 플래그가 ViewModel 에 있어야 하는 이유는, 상세로 나가면 마이의
     * 컴포지션이 걷혀 화면 쪽 `remember` 는 지워지기 때문이다.
     */
    fun onResume() {
        if (resumedOnce) {
            loadCourses()
            loadItineraries()
            loadFavorites()
        }
        resumedOnce = true
    }

    private var resumedOnce = false

    /** 진행 중인 저장 코스 조회. 세션이 바뀌거나 다시 부를 때 끊는다. */
    private var coursesJob: Job? = null

    /**
     * 세션 세대. 계정이 바뀔 때마다 올린다.
     *
     * 취소만으로는 부족하다 — 응답이 이미 도착해 중단점이 남지 않은 코루틴은 계속 달려서
     * 앞 계정 목록을 써 넣는다. 쓰기 직전에 세대를 대조해 그 창을 막는다.
     */
    private var sessionEpoch = 0

    /** 다음에 받을 장. [loadCourses] 가 0 으로 되돌리고 [loadMoreCourses] 가 올린다. */
    private var coursesPage = 0

    /** 진행 중인 저장 동선 조회. */
    private var itinerariesJob: Job? = null
    private var itinerariesPage = 0

    /** 진행 중인 찜 목록 조회. 세션이 바뀌거나 다시 부를 때 끊는다. */
    private var favoritesJob: Job? = null
    private var favoritesPage = 0

    fun onSegmentSelect(segment: MySegment) {
        _uiState.update { it.copy(segment = segment) }
    }

    /**
     * 저장 코스 목록 — 첫 장부터 다시. (API 명세 §7-A · SPEC §4.13 · §3-5)
     *
     * 실패해도 화면 전체를 덮지 않는다 — 세 세그먼트 중 하나라서 동선·찜까지 가리면 안 된다
     * (§3-5 영역 단위 부분 실패). 대신 **이 세그먼트 안에** 오류와 재시도를 둔다.
     *
     * 상세에서 삭제하고 돌아왔을 때도 이걸 다시 부른다 — 목록에서 빠진 것을 보여야 한다.
     * 게스트 차단은 여기서 한다. 화면 복귀 등 부르는 자리가 여럿이라 각자 막으면 하나는 샌다.
     */
    fun loadCourses() {
        if (_uiState.value.profile == null) return
        val epoch = sessionEpoch
        coursesJob?.cancel()
        coursesPage = 0
        coursesJob = viewModelScope.launch {
            _uiState.update { it.copy(courses = SavedCoursesState.Loading) }
            val state = try {
                val page = savedCourseRepository.list(page = 0)
                if (page.courses.isEmpty()) {
                    SavedCoursesState.Empty
                } else {
                    SavedCoursesState.Content(
                        courses = page.courses,
                        hasNext = page.hasNext,
                        totalElements = page.totalElements,
                    )
                }
            } catch (e: ApiException) {
                // 서버 문구 대신 이 자리에서 뭘 못 불렀는지 말한다 — 마이는 세그먼트가
                // 셋이라 "정보를 불러오지 못했어요" 로는 어느 탭인지 알 수 없다.
                SavedCoursesState.Error("저장한 코스를 불러오지 못했어요.")
            }
            // 기다리는 사이 계정이 바뀌었으면 버린다 (#107 리뷰).
            if (epoch != sessionEpoch) return@launch
            _uiState.update { it.copy(courses = state) }
        }
    }

    /**
     * 저장 동선 목록 — 첫 장부터 다시. (API 명세 §5-4 · SPEC §4.13 · §3-5)
     *
     * [loadCourses] 와 같은 규칙이다 — 세그먼트 안에서만 실패를 그리고, 세대가 바뀌면
     * 결과를 버린다. 게스트 차단도 여기서 한다.
     *
     * **비활성 대회의 동선도 걸러 내지 않는다**(§5-4). 사용자가 저장한 것이 말없이
     * 사라지면 안 된다 — 카드가 흐려지고 안내가 붙을 뿐이다.
     */
    fun loadItineraries() {
        if (_uiState.value.profile == null) return
        val epoch = sessionEpoch
        itinerariesJob?.cancel()
        itinerariesPage = 0
        itinerariesJob = viewModelScope.launch {
            _uiState.update { it.copy(itineraries = SavedItinerariesState.Loading) }
            val state = try {
                val page = itineraryRepository.list(page = 0)
                if (page.itineraries.isEmpty()) {
                    SavedItinerariesState.Empty
                } else {
                    SavedItinerariesState.Content(
                        itineraries = page.itineraries,
                        hasNext = page.hasNext,
                        totalElements = page.totalElements,
                    )
                }
            } catch (e: ApiException) {
                SavedItinerariesState.Error("저장한 동선을 불러오지 못했어요.")
            }
            if (epoch != sessionEpoch) return@launch
            _uiState.update { it.copy(itineraries = state) }
        }
    }

    /** [더 보기] — 다음 장을 뒤에 이어 붙인다. (§0-4) */
    fun loadMoreItineraries() {
        val current = _uiState.value.itineraries as? SavedItinerariesState.Content ?: return
        if (!current.hasNext) return
        val epoch = sessionEpoch
        if (itinerariesJob?.isActive == true) return
        itinerariesJob = viewModelScope.launch {
            val next = itinerariesPage + 1
            val page = try {
                itineraryRepository.list(page = next)
            } catch (e: ApiException) {
                // 이미 받은 목록은 지우지 않는다 — 더 못 받은 것뿐이다
                _message.value = "동선을 더 불러오지 못했어요."
                return@launch
            }
            if (epoch != sessionEpoch) return@launch
            itinerariesPage = next
            _uiState.update {
                val shown = it.itineraries as? SavedItinerariesState.Content ?: return@update it
                it.copy(
                    itineraries = shown.copy(
                        itineraries = shown.itineraries + page.itineraries,
                        hasNext = page.hasNext,
                        totalElements = page.totalElements,
                    ),
                )
            }
        }
    }

    /**
     * [더 보기] — 다음 장을 받아 **뒤에 이어 붙인다**. (API 명세 §0-4 Pageable)
     *
     * 한 번에 20건씩 오므로 저장한 코스가 많으면 이걸 눌러야 21번째부터 볼 수 있다(#107 리뷰).
     * 실패해도 **이미 받은 목록은 두고** 문구만 붙인다 — 보이던 게 사라지면 안 된다.
     *
     * S8 지역별의 `loadMoreRegionCourses` 와 같은 규칙이다.
     */
    fun loadMoreCourses() {
        val current = _uiState.value.courses as? SavedCoursesState.Content ?: return
        if (!current.canLoadMore) return
        val epoch = sessionEpoch
        coursesJob?.cancel()
        coursesJob = viewModelScope.launch {
            _uiState.update {
                it.copy(courses = current.copy(loadingMore = true, moreMessage = null))
            }
            val state = try {
                val next = savedCourseRepository.list(page = coursesPage + 1)
                coursesPage += 1
                current.copy(
                    courses = current.courses + next.courses,
                    hasNext = next.hasNext,
                    // 총 건수는 매 응답에 온다 — 사이에 늘거나 줄었을 수 있어 최신값을 쓴다
                    totalElements = next.totalElements,
                    loadingMore = false,
                    moreMessage = null,
                )
            } catch (e: ApiException) {
                current.copy(loadingMore = false, moreMessage = "더 불러오지 못했어요.")
            }
            if (epoch != sessionEpoch) return@launch
            _uiState.update { it.copy(courses = state) }
        }
    }

    /**
     * 찜한 대회 목록 — 첫 장부터 다시. (API 명세 §7-C · SPEC §3-5)
     *
     * [loadCourses] 와 같은 규칙이다 — 세 세그먼트 중 하나라 실패해도 화면 전체를 덮지 않고
     * 이 안에 오류와 재시도를 둔다.
     *
     * **비활성·지난 대회를 걸러 내지 않는다.** 공개 목록과 달리 찜은 그대로 유지하는 것이
     * 계약이고(§7-C 🔒 · 결정-46), 흐림과 "정보 제공 종료" 표기는 `RaceCard` 가 한다.
     */
    fun loadFavorites() {
        if (_uiState.value.profile == null) return
        val epoch = sessionEpoch
        favoritesJob?.cancel()
        favoritesPage = 0
        favoritesJob = viewModelScope.launch {
            _uiState.update { it.copy(favorites = FavoriteRacesState.Loading) }
            var known: List<String> = emptyList()
            val state = try {
                val page = favoriteRepository.list(page = 0)
                known = page.contests.map { it.id }
                if (page.contests.isEmpty()) {
                    FavoriteRacesState.Empty
                } else {
                    FavoriteRacesState.Content(
                        races = page.contests.map { it.toRaceSummary() },
                        hasNext = page.hasNext,
                        totalElements = page.totalElements,
                    )
                }
            } catch (e: ApiException) {
                // 마이는 세그먼트가 셋이라 "정보를 불러오지 못했어요" 로는 어느 탭인지 모른다.
                FavoriteRacesState.Error("찜한 대회를 불러오지 못했어요.")
            }
            if (epoch != sessionEpoch) return@launch
            // 이 목록에 있다는 것 자체가 찜이라는 뜻이다(§7-C). 하트 조회가 늦거나 뒤쪽
            // 장에서 실패해도 목록의 카드가 빈 하트로 남지 않게 한다(#173 리뷰).
            FavoriteStore.mergeKnownFavorites(known)
            _uiState.update { it.copy(favorites = state) }
        }
    }

    /** [더 보기] — 다음 장을 뒤에 이어 붙인다. (§0-4 Pageable) */
    fun loadMoreFavorites() {
        val current = _uiState.value.favorites as? FavoriteRacesState.Content ?: return
        if (!current.canLoadMore) return
        val epoch = sessionEpoch
        favoritesJob?.cancel()
        favoritesJob = viewModelScope.launch {
            _uiState.update {
                it.copy(favorites = current.copy(loadingMore = true, moreMessage = null))
            }
            var known: List<String> = emptyList()
            val state = try {
                val next = favoriteRepository.list(page = favoritesPage + 1)
                favoritesPage += 1
                known = next.contests.map { it.id }
                current.copy(
                    races = current.races + next.contests.map { it.toRaceSummary() },
                    hasNext = next.hasNext,
                    totalElements = next.totalElements,
                    loadingMore = false,
                    moreMessage = null,
                )
            } catch (e: ApiException) {
                current.copy(loadingMore = false, moreMessage = "더 불러오지 못했어요.")
            }
            if (epoch != sessionEpoch) return@launch
            FavoriteStore.mergeKnownFavorites(known)
            _uiState.update { it.copy(favorites = state) }
        }
    }

    /**
     * [동선] 카드의 [삭제]. (`DELETE /api/itineraries/{id}` · §5-6 · SPEC §4.13 🔧정책)
     *
     * **서버가 지운 뒤에 목록에서 뺀다.** 먼저 빼고 실패하면 되돌려야 하는데, 그러면
     * 사라졌다 다시 나타나는 카드를 보게 된다. 삭제는 하트처럼 연타하는 조작이 아니라
     * 왕복을 기다려도 된다.
     */
    fun onDeleteItinerary(id: String) {
        val serverId = id.toLongOrNull() ?: return
        val epoch = sessionEpoch
        viewModelScope.launch {
            try {
                itineraryRepository.delete(serverId)
            } catch (e: ApiException) {
                _message.value = "동선을 삭제하지 못했어요."
                return@launch
            }
            if (epoch != sessionEpoch) return@launch
            _uiState.update { state ->
                val shown = state.itineraries as? SavedItinerariesState.Content
                    ?: return@update state
                val left = shown.itineraries.filterNot { it.id == id }
                state.copy(
                    itineraries = if (left.isEmpty()) {
                        SavedItinerariesState.Empty
                    } else {
                        shown.copy(itineraries = left, totalElements = shown.totalElements - 1)
                    },
                )
            }
        }
    }

    /**
     * 찜 하트 재탭. (SPEC §4.13 · AP-21)
     *
     * **카드는 목록에 남는다.** 목록은 서버가 주고 하트만 [FavoriteStore] 를 보므로,
     * 해제해도 카드가 사라지지 않는다 — 잘못 눌렀을 때 그 자리에서 다시 켤 수 있어야
     * 한다(#163). 그래서 이 자리에는 **해제와 재찜이 둘 다** 온다.
     *
     * 마이는 로그인 상태에서만 열리므로 `LoginRequired` 는 오지 않는다. 서버 실패만 알린다.
     */
    fun onFavoriteToggle(raceId: String) {
        // 어느 쪽을 하려던 것인지 누르기 **전에** 적어 둔다. 실패 문구가 방향을 말해야
        // 하는데, 재찜이 실패했는데 "해제하지 못했어요" 가 뜨면 반대로 읽힌다(#173 리뷰).
        val wasFavorite = raceId in _uiState.value.favoriteIds
        viewModelScope.launch {
            if (FavoriteStore.toggle(raceId) == FavoriteToggleResult.Failed) {
                _message.value = if (wasFavorite) {
                    "찜을 해제하지 못했어요. 잠시 후 다시 시도해 주세요."
                } else {
                    "찜하지 못했어요. 잠시 후 다시 시도해 주세요."
                }
            }
        }
    }

    fun onMessageShown() {
        _message.value = null
    }

}
