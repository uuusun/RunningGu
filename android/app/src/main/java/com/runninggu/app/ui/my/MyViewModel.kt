package com.runninggu.app.ui.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.model.SavedCourse
import com.runninggu.app.data.remote.ApiException
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
 * 저장한 동선 카드 한 장. (SPEC §4.13 [동선] · API 명세 §5-3 목록)
 *
 * TODO(AP-14): `GET /api/itineraries` 응답 DTO 매핑으로 교체한다. 회복 배지 등 카드
 *  표시 필드는 TBD-DB-04(저장 snapshot 정책, 이슈 #55) 결정에 따라 달라질 수 있다.
 */
data class SavedItinerary(
    val id: String,
    /** "{지역} {당일치기|n박 n일}" */
    val title: String,
    val raceName: String,
    val event: String,
    /** 회복 배지 라벨. noHard 종목이 아니면 null. */
    val recoveryLabel: String?,
    /** "MM.DD~MM.DD" */
    val period: String,
    val placeCount: Int,
)

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
    val itineraries: List<SavedItinerary> = emptyList(),
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
 * S10 마이. (SPEC §4.13 · AP-13)
 *
 * 저장소 SSOT는 서버다. **코스·찜 목록은 서버를 본다**(#107 · #163). 동선만 아직
 * 데모 목록이다 — TODO(AP-14): `GET /api/itineraries` 로 교체한다.
 *
 * 찜은 두 갈래로 읽는다. **목록은 `GET /me/favorites`**(비활성도 유지 §7-C), **하트는
 * [FavoriteStore]** 다 — S2 카드·S3 상세와 같은 값이어야 하기 때문이다.
 */
class MyViewModel(
    private val savedCourseRepository: SavedCourseRepository = ServiceLocator.savedCourseRepository,
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
                favoritesJob?.cancel()
                favoritesJob = null
                resumedOnce = false
                _uiState.update {
                    it.copy(
                        profile = profile,
                        // 데모 목록은 로그인 상태에서만 보인다 — 게스트 화면 검증을 막지 않게.
                        itineraries = if (profile != null) SAMPLE_ITINERARIES else emptyList(),
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
            val state = try {
                val page = favoriteRepository.list(page = 0)
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
            val state = try {
                val next = favoriteRepository.list(page = favoritesPage + 1)
                favoritesPage += 1
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
            _uiState.update { it.copy(favorites = state) }
        }
    }

    /** [동선] 카드의 [삭제]. (SPEC §4.13 🔧정책) */
    fun onDeleteItinerary(id: String) {
        _uiState.update { state ->
            state.copy(itineraries = state.itineraries.filterNot { it.id == id })
        }
    }

    /**
     * 찜 해제 — 하트 재탭. 목록에서 바로 빠진다 (SPEC §4.13 · AP-21).
     *
     * 마이는 로그인 상태에서만 열리므로 `LoginRequired` 는 오지 않는다. 서버 실패만 알린다.
     */
    fun onFavoriteToggle(raceId: String) {
        viewModelScope.launch {
            if (FavoriteStore.toggle(raceId) == FavoriteToggleResult.Failed) {
                _message.value = "찜을 해제하지 못했어요. 잠시 후 다시 시도해 주세요."
            }
        }
    }

    fun onMessageShown() {
        _message.value = null
    }

    private companion object {
        /** TODO(AP-14): `GET /api/itineraries`·`GET /api/me/courses` 로 교체하는 데모 데이터. */
        val SAMPLE_ITINERARIES = listOf(
            SavedItinerary(
                id = "it_1",
                title = "서울 2박 3일",
                raceName = "서울 한강 러닝 페스티벌",
                event = "10K",
                recoveryLabel = null,
                period = "09.05~09.07",
                placeCount = 10,
            ),
            SavedItinerary(
                id = "it_2",
                title = "세종 1박 2일",
                raceName = "세종 호수공원 마라톤",
                event = "하프",
                recoveryLabel = "회복 모드",
                period = "09.08~09.09",
                placeCount = 6,
            ),
        )
        // 저장 코스는 SavedCourseRepository 가 준다 — 데모 목록을 여기 두지 않는다.
    }
}
