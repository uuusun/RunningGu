package com.runninggu.app.ui.course

import com.runninggu.app.ui.OFFLINE
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.model.CourseTargetKm
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.ui.SAVE_FAILED_OUTSIDE_CONTRACT
import com.runninggu.app.ui.apiFailureLogger
import com.runninggu.app.ui.diagnostic
import com.runninggu.app.ui.userMessageOrDefault
import com.runninggu.app.data.repository.CourseRepository
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.repository.GeocodeRepository
import com.runninggu.app.data.repository.SavedCourseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * S8 러닝코스 ViewModel. (SPEC §4.11 · AP-12)
 *
 * 화면은 [CourseUiState] 만 본다 — 서버든 폴백이든 출처를 모른다.
 */
class CourseViewModel(
    private val repository: CourseRepository,
    private val geocodeRepository: GeocodeRepository,
    private val savedCourseRepository: SavedCourseRepository = ServiceLocator.savedCourseRepository,
    /**
     * 이 S8 **백스택 항목**의 상태. S7 연계로 열렸으면 여기에 출발지·목표 거리가 들어 있다
     * (매핑표 D-15). 탭바로 그냥 열었으면 비어 있다.
     */
    launchState: SavedStateHandle = SavedStateHandle(),
    /**
     * 처음 펼 탭. 홈 퀵바의 [코스] 가 지역별로 열 때만 기본값과 다르다 (SPEC §4.4-2).
     *
     * [onTabChange] 를 대신 부르지 않는 이유는 **화면이 뜨기 전이라서**다. 그때 상태를
     * 갈면 사용자가 탭을 누른 적 없는데 눌린 것처럼 기록되고, 첫 조합(`init` 순서)이
     * 여기와 [applyLaunchContext] 로 갈려 읽기 어려워진다.
     */
    initialTab: CourseUiState.Tab = CourseUiState.Tab.NEARBY,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CourseUiState(tab = initialTab))
    val uiState: StateFlow<CourseUiState> = _uiState.asStateFlow()

    /** 슬라이더를 끌 때마다 조회하면 요청이 쏟아진다 — 이전 조회를 취소하고 마지막 것만 남긴다. */
    private var nearbyJob: Job? = null

    /** 칩을 빠르게 두 번 누르면 먼저 보낸 응답이 늦게 도착해 목록이 어긋난다 — 같은 이유로 끊는다. */
    private var regionJob: Job? = null

    /** 검색 버튼 연타도 마지막 것만 남긴다. */
    private var searchJob: Job? = null

    /** [저장] 연타. 버튼도 막지만, 화면이 다시 만들어지는 경우까지 여기서 끊는다. */
    private var saveJob: Job? = null

    /**
     * 출발지 주변 목록 세대. **조회할 때마다 올라간다.**
     *
     * 저장 결과가 어느 목록의 것인지 가리는 데 쓴다. `routeId` 만으로는 부족하다 —
     * §6-1 이 그걸 "near 응답 안에서만 유효한 불투명 식별자" 로 정의해서, 서로 다른
     * 조회 사이의 동일성을 보장하지 않는다(#166 리뷰).
     */
    private var nearbyGeneration = 0

    /** 지역별 목록에서 지금까지 받은 페이지 번호. 지역을 바꾸면 0 으로 돌아간다. */
    private var regionPage = 0

    init {
        loadRegions()
        // 지역별 목록은 그 탭에 들어갈 때 부른다 — 기본 탭은 출발지 주변이라 미리 부르면 헛 호출이다.
        //
        // **지역별로 열렸으면 여기서 한 번 부른다.** 그 진입은 [onTabChange] 를 거치지
        // 않아서, 안 부르면 사용자가 탭을 한 번 눌렀다 돌아와야 목록이 나온다 — 목록을
        // 보러 누른 버튼인데 빈 화면이 뜬다.
        if (initialTab == CourseUiState.Tab.BY_REGION) loadRegionCourses()
        applyLaunchContext(launchState)
    }

    /**
     * S7 에서 넘어온 출발지·목표 거리를 반영한다. (SPEC §4.11-1 · 매핑표 D-15)
     *
     * **목표 거리를 먼저 넣고 출발지를 정한다.** 순서가 뒤집히면 [onOriginChange] 가
     * 옛 목표 거리로 조회를 걸어, 화면에 보이는 슬라이더와 결과가 어긋난다.
     *
     * 숙소 없이 추천받은 동선이면(§4.9) 출발지는 프리필하지 않고 목표 거리만 남긴다 —
     * 없는 좌표를 지어내는 것보다 사용자가 직접 고르게 하는 편이 맞다.
     */
    private fun applyLaunchContext(launchState: SavedStateHandle) {
        val request = CourseLaunchContext.from(launchState) ?: return
        _uiState.update { it.copy(targetKm = snapTargetKm(request.targetKm)) }
        val stay = request.stay ?: return
        onOriginChange(
            OriginState.Fixed(
                name = stay.name,
                lat = stay.lat,
                lng = stay.lng,
                from = OriginState.Fixed.Source.ITINERARY,
            ),
        )
    }

    fun onTabChange(tab: CourseUiState.Tab) {
        _uiState.update { it.copy(tab = tab) }
        // 지역별을 처음 열 때 한 번만 부른다. 이후 갱신은 칩 선택·재시도가 맡는다
        if (tab == CourseUiState.Tab.BY_REGION && regionJob == null) loadRegionCourses()
    }

    /**
     * 출발지가 정해지면 바로 조회한다. (§4.11-1)
     *
     * 프리셋·검색·S7 연계가 모두 여기로 온다. 기기 위치는 쓰지 않는다(결정-56).
     */
    fun onOriginChange(origin: OriginState) {
        _uiState.update { it.copy(origin = origin) }
        if (origin is OriginState.Fixed) refreshNearby()
    }

    fun onOriginQueryChange(query: String) {
        // 입력을 고치면 앞선 실패 문구는 지운다 — 새 시도를 하는 중이다
        _uiState.update { it.copy(originSearch = it.originSearch.copy(query = query, message = null)) }
    }

    /**
     * 출발지 검색. (SPEC §4.11-1 ② · API 명세 §4-4)
     *
     * 서버가 카카오 키워드 **첫 결과 하나**를 주므로 찾으면 곧바로 출발지로 삼고 조회까지 간다.
     * 후보 목록을 보여주고 고르게 하지 않는다.
     */
    fun onOriginSearch() {
        val query = _uiState.value.originSearch.query.trim()
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(originSearch = it.originSearch.copy(searching = true, message = null))
            }
            try {
                val place = geocodeRepository.search(query)
                _uiState.update {
                    it.copy(originSearch = it.originSearch.copy(searching = false, message = null))
                }
                onOriginChange(
                    OriginState.Fixed(
                        name = place.name,
                        lat = place.lat,
                        lng = place.lng,
                        from = OriginState.Fixed.Source.SEARCH,
                    ),
                )
            } catch (e: ApiException) {
                _uiState.update {
                    it.copy(
                        originSearch = it.originSearch.copy(
                            searching = false,
                            message = e.searchMessage(),
                        ),
                    )
                }
            }
        }
    }

    /**
     * 목표 거리 변경. **드래그 중에는 조회하지 않는다** — 놓을 때 [onTargetKmCommit] 를 부른다.
     */
    fun onTargetKmChange(km: Double) {
        _uiState.update { it.copy(targetKm = snapTargetKm(km)) }
    }

    fun onTargetKmCommit() {
        if (_uiState.value.origin is OriginState.Fixed) refreshNearby()
    }

    /** 지역 칩은 **재탭하면 해제**된다(전국). (§4.11-b) */
    fun onRegionToggle(region: String) {
        val next = if (_uiState.value.selectedRegion == region) null else region
        _uiState.update { it.copy(selectedRegion = next) }
        loadRegionCourses()
    }

    /**
     * 목록에서 항목을 고른다.
     *
     * **고른 것이 바뀌면 이전 저장 결과를 지운다.** 안 지우면 A 를 저장한 뒤 B 를 골랐을 때
     * "저장했어요" 가 B 아래에 남아, 아직 안 누른 코스를 저장한 것처럼 읽힌다.
     */
    fun onItemSelect(item: NearbyItem?) {
        _uiState.update {
            if (it.selectedItem == item) it
            else it.copy(selectedItem = item, save = SaveCourseState.Idle)
        }
    }

    /**
     * [저장] — 고른 경로를 서버에 저장한다. (API 명세 §7-A · SPEC §4.11-6)
     *
     * ## 서버가 준 값을 그대로 되돌려보낸다
     *
     * 요청 본문은 `near` 응답에서 [toSaveRequest] 가 만든다. 화면이 값을 다시 조립하면
     * 서버가 준 것과 미세하게 달라져 중복 판정(fingerprint)이 흔들린다(이슈 #62).
     *
     * ## 멱등이라 "이미 저장함" 이 실패가 아니다
     *
     * 같은 경로를 다시 저장하면 서버가 새 행 대신 기존 id 를 `created=false` 로 준다(§7-A).
     * 사용자가 잘못한 게 없으므로 실패 색을 쓰지 않는다.
     *
     * ## 게스트는 로그인 유도 모달로 끝낸다
     *
     * `401` 이면 [SaveCourseState.NeedsLogin] 으로 모달을 띄운다(매핑표 S8 "게스트 modal").
     * 로그인하고 돌아와도 **저장을 예약하지 않는다**(D-27) — 누른 적 없는 저장이 저절로
     * 일어나면 사용자가 놀란다.
     */
    fun onSaveCourse() {
        val state = _uiState.value
        if (state.save is SaveCourseState.Saving) return
        val route = state.selectedRoute ?: return

        val epoch = SessionStore.sessionEpoch
        val generation = nearbyGeneration
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            _uiState.update { it.copy(save = SaveCourseState.Saving) }
            val done = try {
                val result = savedCourseRepository.save(route)
                when {
                    // 경로나 원천이 없어 fingerprint 를 만들 수 없다 — 저장 자체가 불가능하다
                    result == null -> SaveCourseState.Done(
                        message = "이 코스는 경로 정보가 없어 저장할 수 없어요.",
                        failed = true,
                    )

                    result.created -> SaveCourseState.Done("저장했어요. 마이에서 볼 수 있어요.")
                    else -> SaveCourseState.Done("이미 저장한 코스예요.")
                }
            } catch (e: ApiException) {
                // **화면에는 `title`, 로그에는 `status`·`code`·`traceId`** (이슈 #252 · #254 리뷰).
                // 정상 problem+json 은 둘을 함께 주는데 화면 문구만 쓰면 `code` 가 앱에서
                // 사라진다 — 서버 로그와 이어 볼 끈이 그것뿐이다
                apiFailureLogger("코스 저장 실패 — ${e.diagnostic()}")
                // 게스트는 문구가 아니라 모달이다 — 로그인은 화면을 옮겨야 끝나는 일이다
                if (e is ApiException.Http && e.needsLogin) SaveCourseState.NeedsLogin
                else SaveCourseState.Done(message = e.saveMessage(), failed = true)
            } catch (e: CancellationException) {
                // 취소는 실패가 아니다. 여기서 삼키면 코루틴 취소가 끊긴다
                throw e
            } catch (e: Throwable) {
                // **S7 에 있는 것이 여기엔 없었다** (이슈 #252 를 보다 찾음). `ApiException`
                // 이 아닌 것이 올라오면 코루틴이 죽어 `save` 가 `Saving` 인 채로 남는다 —
                // `canSave` 가 계속 false 라 [저장] 이 "저장 중…" 에 굳고, 같은 코스를 다시
                // 눌러도 안 풀린다. S7 은 #214 리뷰에서 같은 이유로 이 갈래를 넣었다.
                //
                // 문구는 `saveMessage()` 의 어느 갈래와도 겹치지 않는다 — 겹치면 화면만
                // 보고 서버 거절과 앱 안의 실패를 못 가린다
                apiFailureLogger("코스 저장 실패 — 계약 밖: ${e.javaClass.simpleName}")
                SaveCourseState.Done(message = SAVE_FAILED_OUTSIDE_CONTRACT, failed = true)
            }
            // 기다리는 사이 세션이 바뀌었으면 남의 결과다. 다만 두 가지를 지킨다.
            //
            // **`NeedsLogin` 은 통과시킨다.** 여기서 세대가 오르는 흔한 이유가 바로
            // "세션이 죽었다" 이다 — `401` 을 받은 `TokenAuthenticator` 가 재발급에
            // 실패하면 `onGiveUp` 으로 `signOut()` 을 부르고, 그게 응답이 화면에 닿기
            // **전에** 세대를 올린다. 그 결과를 버리면 정작 로그인하라는 말을 못 한다.
            // 모달은 계정별 데이터를 안 보여주고 로그인 뒤 자동 저장도 없어서(D-27)
            // 남의 결과가 새는 위험도 없다(#166 리뷰).
            //
            // **버리더라도 버튼은 풀어 준다.** `save` 를 `Saving` 인 채로 두면 `canSave`
            // 가 계속 false 라 "저장 중…" 이 굳는다. 같은 코스를 다시 눌러도 안 풀린다.
            //
            // `MyViewModel`(#107)의 세대와 같은 장치가 아니다. 저쪽은 ViewModel 로컬이고
            // 세대를 올리는 그 자리에서 화면 상태도 함께 초기화한다. 이건 전역이라
            // 되돌려 줄 관찰자가 없다.
            if (epoch != SessionStore.sessionEpoch && done !is SaveCourseState.NeedsLogin) {
                _uiState.update {
                    if (it.save is SaveCourseState.Saving) it.copy(save = SaveCourseState.Idle) else it
                }
                return@launch
            }

            // **보내는 사이 다른 코스를 골랐으면 이 결과는 지금 화면의 것이 아니다.**
            // A 를 저장하는 중에 B 를 고르면 `onItemSelect` 가 `save` 를 `Idle` 로
            // 되돌리는데, 그 뒤 A 응답이 도착해 "저장했어요" 를 다시 쓰면 **B 아래에
            // 붙는다.** 사용자는 누른 적 없는 코스를 저장한 것으로 읽는다(#166 리뷰).
            //
            // 재조회로 목록이 갈리는 경로도 같다 — 그때는 `selectedItem` 이 null 이 되고
            // [CourseUiState.selectedRoute] 가 새 목록의 첫 코스를 가리킨다.
            //
            // **작업을 취소하지 않고 결과만 버린다.** 요청은 이미 서버에 갔고 저장은
            // 멱등이라(§7-A), 끊어도 저장은 되고 확인만 못 하는 상태가 된다.
            //
            // [SaveCourseState.NeedsLogin] 은 통과시킨다 — 로그인 모달은 코스별 안내가
            // 아니라 계정 상태 안내다. 위 세대 가드와 같은 이유다.
            if (done !is SaveCourseState.NeedsLogin &&
                (generation != nearbyGeneration ||
                    _uiState.value.selectedRoute?.routeId != route.routeId)
            ) {
                return@launch
            }
            _uiState.update { it.copy(save = done) }
        }
    }

    /** 로그인 유도 모달을 닫는다. 고른 코스는 그대로 두어 로그인 뒤 다시 누를 수 있게 한다. */
    fun onLoginPromptDismiss() {
        _uiState.update {
            if (it.save is SaveCourseState.NeedsLogin) it.copy(save = SaveCourseState.Idle) else it
        }
    }

    fun refreshNearby() {
        val origin = _uiState.value.origin as? OriginState.Fixed ?: return
        // **조회할 때마다 세대를 올린다.** `routeId` 는 near 응답 **안에서만** 유효한
        // 불투명 식별자라(§6-1) 조회를 건너 비교할 수 없다 — 새 목록의 첫 경로가 같은
        // id 를 재사용하면 남의 결과가 통과한다(#166 리뷰).
        nearbyGeneration++
        nearbyJob?.cancel()
        nearbyJob = viewModelScope.launch {
            _uiState.update { it.copy(nearby = NearbyState.Loading) }
            val state = try {
                val result = repository.near(
                    lat = origin.lat,
                    lng = origin.lng,
                    targetKm = _uiState.value.targetKm,
                )
                if (result.items.isEmpty()) {
                    // 모든 원천이 정상인데 0건 — Empty 이지 Error 가 아니다 (§4.11-7)
                    NearbyState.Empty
                } else {
                    NearbyState.Content(
                        items = result.items,
                        attributions = result.attributions,
                        degradedSources = result.degradedSources,
                    )
                }
            } catch (e: ApiException) {
                NearbyState.Error(e.nearbyMessage())
            }
            // 목록이 갈렸으니 이전 저장 결과도 지운다 — 사라진 코스에 붙은 안내가 남으면 안 된다
            _uiState.update {
                it.copy(nearby = state, selectedItem = defaultSelection(state), save = SaveCourseState.Idle)
            }
        }
    }

    /**
     * 조회 직후 무엇을 고른 상태로 둘 것인가. (#190 리뷰)
     *
     * **첫 경로를 명시로 고른다.** 지도는 고르기 전에도 첫 경로를 그리는데(§4.11-4 ·
     * [CourseUiState.mappedRoute]), 선택을 비워 두면 **목록 카드는 강조되지 않는다.**
     * 서버는 경로와 장소를 거리순으로 섞어 주므로(§6-1) 그 경로가 목록 1번이라는 보장이
     * 없다 — 스팟이 앞에 오면 사용자는 **어느 코스인지 모르는 채 [저장]** 을 누른다.
     *
     * 경로가 하나도 없으면(수도권 기본 · §4.11 📌) null 이다. 그때는 번호 핀이 서고
     * [저장] 은 잠긴다 — 그릴 경로가 없으니 저장할 것도 없다.
     */
    private fun defaultSelection(state: NearbyState): NearbyItem.Route? =
        (state as? NearbyState.Content)?.items?.filterIsInstance<NearbyItem.Route>()?.firstOrNull()

    fun loadRegions() {
        viewModelScope.launch {
            _uiState.update { it.copy(regions = RegionsState.Loading) }
            val state = try {
                RegionsState.Content(repository.regions())
            } catch (e: ApiException) {
                // 칩을 못 불러와도 목록은 전국 기준으로 보여줄 수 있다
                RegionsState.Error(e.userMessageOrDefault())
            }
            _uiState.update { it.copy(regions = state) }
        }
    }

    companion object {
        /**
         * **세 갈래가 다 서버를 본다.** (AP-14 · AP-12)
         *
         * 출발지 검색과 지역별 목록은 #156, 출발지 주변은 `/courses/near` 가 #174
         * (AP-25 OSM 경로 생성)로 서면서 붙었다. 그전까지 `near` 만 스텁으로 보내던
         * 한시적인 조합은 예정대로 지웠다(#190).
         */
        fun factory(
            repository: CourseRepository = ServiceLocator.courseRepository,
            geocodeRepository: GeocodeRepository = ServiceLocator.geocodeRepository,
            savedCourseRepository: SavedCourseRepository = ServiceLocator.savedCourseRepository,
            /**
             * S8 백스택 항목의 상태를 그대로 넘긴다 — S7 연계 값이 여기 담겨 온다
             * (매핑표 D-15). `createSavedStateHandle()` 은 항목의 것과 다른 handle 이라
             * S7 이 쓴 값이 보이지 않는다.
             */
            launchState: SavedStateHandle = SavedStateHandle(),
            /** 홈 퀵바의 [코스] 로 열면 지역별이다. 그 밖의 진입은 출발지 주변. (SPEC §4.4-2) */
            initialTab: CourseUiState.Tab = CourseUiState.Tab.NEARBY,
        ) = viewModelFactory {
            initializer {
                CourseViewModel(
                    repository,
                    geocodeRepository,
                    savedCourseRepository,
                    launchState,
                    initialTab,
                )
            }
        }
    }

    /** 첫 장부터 다시. 지역을 바꿨거나 오류에서 재시도할 때다. */
    fun loadRegionCourses() {
        regionJob?.cancel()
        regionPage = 0
        regionJob = viewModelScope.launch {
            _uiState.update { it.copy(regionCourses = RegionCoursesState.Loading) }
            val state = try {
                val page = repository.byRegion(_uiState.value.selectedRegion, page = 0)
                if (page.courses.isEmpty()) {
                    RegionCoursesState.Empty
                } else {
                    RegionCoursesState.Content(
                        courses = page.courses,
                        hasNext = page.hasNext,
                        totalElements = page.totalElements,
                        attributions = page.attributions,
                    )
                }
            } catch (e: ApiException) {
                RegionCoursesState.Error(e.userMessageOrDefault())
            }
            _uiState.update { it.copy(regionCourses = state) }
        }
    }

    /**
     * [더 보기] — 다음 장을 받아 **뒤에 이어 붙인다**. (§4.11-b)
     *
     * 한 번에 20건씩 오므로 코스가 많은 지역은 이걸 눌러야 21번째부터 볼 수 있다.
     * 실패해도 **이미 받은 목록은 두고** 문구만 붙인다 — 보이던 게 사라지면 안 된다.
     */
    fun loadMoreRegionCourses() {
        val current = _uiState.value.regionCourses as? RegionCoursesState.Content ?: return
        if (!current.canLoadMore) return
        regionJob?.cancel()
        regionJob = viewModelScope.launch {
            _uiState.update {
                it.copy(regionCourses = current.copy(loadingMore = true, moreMessage = null))
            }
            val state = try {
                val next = repository.byRegion(_uiState.value.selectedRegion, page = regionPage + 1)
                regionPage += 1
                current.copy(
                    courses = current.courses + next.courses,
                    hasNext = next.hasNext,
                    // 총 건수는 서버가 매 응답에 준다 — 사이에 늘거나 줄었을 수 있어 최신값을 쓴다
                    totalElements = next.totalElements,
                    // 출처는 **화면에 보이는 코스 전체** 기준이다. 이어 붙인 장에 새 원천이
                    // 섞이면 그것도 표시해야 한다 (§6-2 · 결정-44)
                    attributions = (current.attributions + next.attributions).distinct(),
                    loadingMore = false,
                    moreMessage = null,
                )
            } catch (e: ApiException) {
                current.copy(loadingMore = false, moreMessage = e.userMessageOrDefault())
            }
            _uiState.update { it.copy(regionCourses = state) }
        }
    }
}

/** 슬라이더 값을 계약 범위·단위로 맞춘다. 1~21km, 0.5 단위. (SPEC §4.11-2) */
internal fun snapTargetKm(raw: Double): Double {
    val step = CourseTargetKm.STEP
    val snapped = (raw / step).roundToInt() * step
    return snapped.coerceIn(CourseTargetKm.MIN, CourseTargetKm.MAX)
}

/**
 * 출발지 주변 오류 문구. (§4.11-7)
 *
 * 원천이 다 죽어 표시할 게 없는 경우(`503`)와 그 밖의 실패를 구분한다 —
 * 전자는 "잠시 뒤 다시" 가 맞고, 후자는 네트워크 문제일 수 있다.
 */
internal fun ApiException.nearbyMessage(): String = when {
    this is ApiException.Network -> OFFLINE
    this is ApiException.Http && code == ApiErrorCode.COURSE_SOURCES_UNAVAILABLE ->
        "코스 정보를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요."
    else -> userMessageOrDefault()
}

/**
 * 저장 실패 문구. (API 명세 §7-A · §0-3)
 *
 * **게스트(`401`)는 여기로 오지 않는다** — 모달이라 문구가 따로 없다.
 *
 * ## 세 갈래를 가른다 (이슈 #252)
 *
 * 예전에는 `Network` 가 아닌 **모든 실패**가 "저장하지 못했어요. 잠시 뒤 다시 시도해
 * 주세요." 하나로 떨어졌다. 그런데 그 문구는 `ResultViewModel.onSave()` 의 마지막
 * `catch (e: Throwable)` 이 내는 것과 **글자 하나까지 같았다.** 그래서 화면만 보고는
 * "서버가 거절했다" 와 "앱 안에서 깨졌다" 를 가릴 수 없었다.
 *
 * 실제로 그것 때문에 이슈 #245 를 사흘 동안 엉뚱한 데서 찾았다. 평범한 `400` 이었는데
 * 직렬화 오류로 읽고 매퍼를 뒤졌다(원인은 #251).
 *
 * | 무엇 | 문구 | 사용자가 할 일 |
 * |---|---|---|
 * | [ApiException.Network] | 네트워크 | 연결을 고치고 다시 |
 * | [ApiException.Http] | **서버가 준 `title`** | 거절 사유에 따라 다르다 |
 * | [ApiException.Malformed] | 결과를 확인 못 함 | 다시 누르지 말고 마이에서 확인 |
 *
 * **`Http` 는 서버 문구를 그대로 낸다.** 왜 거절했는지는 서버만 안다 — `VALIDATION_FAILED`
 * 면 "요청 값이 올바르지 않습니다." 가 온다(백엔드 `ErrorCode`). 앱이 한 문구로 뭉개면
 * `400` 과 `500` 이 화면에서 같아진다.
 *
 * **`title` 이 없으면 상태 코드를 보여준다.** 프록시가 HTML 오류 페이지를 돌려주는 경우가
 * 있어(`httpErrorOf` KDoc) 그때는 `problem` 이 null 이다.
 *
 * 처음에는 `code` 를 보여줬는데 **그 자리에서는 늘 `UNKNOWN` 이라 아무 말도 아니었다** —
 * `problem` 이 null 이면 `ApiErrorCode.from(null)` 이 `UNKNOWN` 을 주기 때문이다(#254
 * 리뷰). 실제로 `httpErrorOf(502, "<html>…")` 를 태워 보면 `502 UNKNOWN` 이 나온다.
 * 상태 코드는 그 자리에서도 뜻이 남는다.
 *
 * **`code` 는 화면이 아니라 로그로 간다** — [com.runninggu.app.ui.diagnostic] 참고.
 *
 * **`Malformed` 는 "다시 시도" 라고 하지 않는다.** 이건 **성공 응답을 못 읽은** 것이라
 * (`apiCall` 의 `SerializationException` 갈래) 서버에는 이미 저장돼 있을 수 있다.
 * 다시 누르라고 하면 사용자가 두 번 저장한다.
 */
internal fun ApiException.saveMessage(): String = when (this) {
    is ApiException.Network -> OFFLINE
    is ApiException.Http -> userMessage ?: "저장하지 못했어요. (서버 응답 $status)"
    is ApiException.Malformed -> "저장은 됐을 수 있는데 결과를 확인하지 못했어요. 마이에서 확인해 주세요."
}

/**
 * 출발지 검색 실패 문구. (§4-4)
 *
 * **못 찾은 것과 못 부른 것을 나눈다** — 전자는 검색어를 바꾸면 되고 후자는 다시 눌러야 한다.
 */
internal fun ApiException.searchMessage(): String = when {
    this is ApiException.Network -> OFFLINE
    this is ApiException.Http && code == ApiErrorCode.NO_RESULT ->
        "그런 장소를 못 찾았어요. 다른 이름으로 찾아보세요."
    else -> userMessageOrDefault()
}
