package com.runninggu.app.ui.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.model.CourseTargetKm
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.ui.userMessageOrDefault
import com.runninggu.app.data.repository.CourseRepository
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.local.LocationProvider
import com.runninggu.app.data.local.LocationResult
import com.runninggu.app.data.repository.FakeCourseRepository
import com.runninggu.app.data.repository.GeocodeRepository
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
    private val locationProvider: LocationProvider = ServiceLocator.locationProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CourseUiState())
    val uiState: StateFlow<CourseUiState> = _uiState.asStateFlow()

    /** 슬라이더를 끌 때마다 조회하면 요청이 쏟아진다 — 이전 조회를 취소하고 마지막 것만 남긴다. */
    private var nearbyJob: Job? = null

    /** 칩을 빠르게 두 번 누르면 먼저 보낸 응답이 늦게 도착해 목록이 어긋난다 — 같은 이유로 끊는다. */
    private var regionJob: Job? = null

    /** 검색 버튼 연타도 마지막 것만 남긴다. */
    private var searchJob: Job? = null

    /** [내 위치] 연타. 앞 조회를 끊고 마지막 것만 남긴다. */
    private var locationJob: Job? = null

    /**
     * 위치 조회 세대. **출발지가 바뀔 때마다 올라간다.**
     *
     * GPS 는 최대 6초가 걸리는데 그동안 사용자가 프리셋이나 검색으로 출발지를 고를 수 있다.
     * 그때 늦게 도착한 GPS 결과가 사용자의 선택을 덮으면, 화면에 보이는 출발지와 서버에
     * 조회한 좌표가 어긋난다(#92 리뷰). 세대가 다르면 결과를 버린다.
     */
    private var locationRequestId = 0

    /** 지역별 목록에서 지금까지 받은 페이지 번호. 지역을 바꾸면 0 으로 돌아간다. */
    private var regionPage = 0

    init {
        loadRegions()
        // 지역별 목록은 그 탭에 들어갈 때 부른다 — 기본 탭은 내 주변이라 미리 부르면 헛 호출이다
    }

    fun onTabChange(tab: CourseUiState.Tab) {
        _uiState.update { it.copy(tab = tab) }
        // 지역별을 처음 열 때 한 번만 부른다. 이후 갱신은 칩 선택·재시도가 맡는다
        if (tab == CourseUiState.Tab.BY_REGION && regionJob == null) loadRegionCourses()
    }

    /**
     * [내 위치]. (SPEC §4.11-1 ①)
     *
     * **실패해도 화면을 막지 않는다.** 출발지는 검색·프리셋으로도 정할 수 있어서(NFR-15),
     * 여기서 못 잡으면 안내만 하고 원래 상태로 돌려놓는다.
     *
     * 실패 종류마다 할 말이 다르다 — 권한을 거부한 사람에게 "잠시 뒤 다시" 는 눌러도
     * 계속 실패하고, 위치가 늦게 잡히는 사람에게 "권한을 허용해 주세요" 는 이미 허용한
     * 걸 또 하라는 말이 된다.
     */
    fun onUseMyLocation() {
        locationJob?.cancel()
        val requestId = ++locationRequestId
        locationJob = viewModelScope.launch {
            val previous = _uiState.value.origin
            _uiState.update { it.copy(origin = OriginState.Locating, locationMessage = null) }

            val result = locationProvider.current()

            // 조회하는 동안 사용자가 직접 출발지를 골랐으면 **그 선택이 이긴다** (#92 리뷰).
            // 성공이든 실패든 손대지 않는다 — 실패 복구도 사용자의 선택을 되돌리는 셈이다
            if (requestId != locationRequestId) return@launch

            when (result) {
                is LocationResult.Found -> onOriginChange(
                    OriginState.Fixed(
                        name = MY_LOCATION_LABEL,
                        lat = result.point.lat,
                        lng = result.point.lng,
                        from = OriginState.Fixed.Source.GPS,
                    ),
                )

                // 실패 종류별 문구는 순수 함수로 빼 두었다 — 기기 없이 고정할 수 있다
                else -> restoreOrigin(previous, result.originFailureMessage())
            }
        }
    }

    /**
     * 권한 요청 자체를 거부당했다. 화면이 시스템 대화상자 결과를 그대로 넘긴다.
     *
     * 조회를 시작하지 않았으므로 출발지는 건드리지 않는다.
     */
    fun onLocationPermissionDenied() {
        _uiState.update {
            it.copy(locationMessage = LocationResult.PermissionDenied.originFailureMessage())
        }
    }

    /** 조회 전 출발지로 되돌린다. 이미 고른 프리셋이 [내 위치] 실패로 날아가면 안 된다. */
    private fun restoreOrigin(previous: OriginState, message: String) {
        _uiState.update { it.copy(origin = previous, locationMessage = message) }
    }

    /**
     * 출발지가 정해지면 바로 조회한다. (§4.11-1)
     *
     * 프리셋·검색·S7 연계가 모두 여기로 온다. **진행 중이던 GPS 조회는 여기서 무효가 된다**
     * — 사용자가 직접 고른 것이 늦게 온 GPS 결과보다 우선이다(#92 리뷰).
     */
    fun onOriginChange(origin: OriginState) {
        locationRequestId++
        // 출발지가 정해졌으면 "아래에서 골라 주세요" 안내는 할 일을 다 했다. 그대로 두면
        // 사용자가 안내대로 골랐는데도 문구가 남아 뭔가 덜 된 것처럼 읽힌다 (#92 리뷰)
        _uiState.update { it.copy(locationMessage = null) }
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

    fun onItemSelect(item: NearbyItem?) {
        _uiState.update { it.copy(selectedItem = item) }
    }

    fun refreshNearby() {
        val origin = _uiState.value.origin as? OriginState.Fixed ?: return
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
            _uiState.update { it.copy(nearby = state, selectedItem = null) }
        }
    }

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
         * 백엔드가 준비되면 [com.runninggu.app.data.repository.RemoteCourseRepository] 로 바꾼다.
         * 화면은 안 건드린다 — Repository 인터페이스만 보기 때문이다(AGENTS 4장).
         */
        /** 좌표를 그대로 보여줄 수는 없다. 목업도 "내 위치" 로 적는다. */
        private const val MY_LOCATION_LABEL = "내 위치"

        /**
         * 출발지 검색만 서버로 옮겼다. (AP-14)
         *
         * 코스 자체는 아직 가짜다 — 서버에 `/api/courses`·`/api/courses/near` 가 없다
         * (AP-07 · AP-25). 없는 엔드포인트를 부르면 화면이 오류만 보여줘서 만든 것을
         * 확인할 수 없다. 서면 [ServiceLocator.courseRepository] 로 바꾼다.
         */
        fun factory(
            repository: CourseRepository = FakeCourseRepository,
            geocodeRepository: GeocodeRepository = ServiceLocator.geocodeRepository,
            locationProvider: LocationProvider = ServiceLocator.locationProvider,
        ) = viewModelFactory {
            initializer { CourseViewModel(repository, geocodeRepository, locationProvider) }
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
 * 내 주변 오류 문구. (§4.11-7)
 *
 * 원천이 다 죽어 표시할 게 없는 경우(`503`)와 그 밖의 실패를 구분한다 —
 * 전자는 "잠시 뒤 다시" 가 맞고, 후자는 네트워크 문제일 수 있다.
 */
internal fun ApiException.nearbyMessage(): String = when {
    this is ApiException.Network -> "네트워크에 연결할 수 없어요."
    this is ApiException.Http && code == ApiErrorCode.COURSE_SOURCES_UNAVAILABLE ->
        "코스 정보를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요."
    else -> userMessageOrDefault()
}

/**
 * 출발지 검색 실패 문구. (§4-4)
 *
 * **못 찾은 것과 못 부른 것을 나눈다** — 전자는 검색어를 바꾸면 되고 후자는 다시 눌러야 한다.
 */
internal fun ApiException.searchMessage(): String = when {
    this is ApiException.Network -> "네트워크에 연결할 수 없어요."
    this is ApiException.Http && code == ApiErrorCode.NO_RESULT ->
        "그런 장소를 못 찾았어요. 다른 이름으로 찾아보세요."
    else -> userMessageOrDefault()
}
/**
 * [내 위치] 실패 문구. (SPEC §4.11-1 ① · NFR-15)
 *
 * **종류마다 사용자가 할 일이 다르다.** 권한을 거부한 사람에게 "잠시 뒤 다시" 라고 하면
 * 눌러도 계속 실패하고, 위치가 늦게 잡히는 사람에게 "권한을 허용해 주세요" 라고 하면
 * 이미 허용한 걸 또 하라는 말이 된다.
 *
 * 어느 문구든 **아래에서 고르라고 함께 알린다** — 출발지는 검색·프리셋으로도 정할 수 있고,
 * 그게 이 화면이 권한 거부에도 동작하는 이유다.
 */
internal fun LocationResult.originFailureMessage(): String = when (this) {
    // 못 잡은 게 아니라 잡은 것이다. 호출부가 이 가지로 오면 안 된다
    is LocationResult.Found -> ""
    LocationResult.PermissionDenied -> "위치 권한이 없어요. 아래에서 출발지를 골라 주세요."
    LocationResult.Timeout -> "위치를 확인하지 못했어요. 다시 시도하거나 아래에서 골라 주세요."
    LocationResult.Unavailable -> "위치 서비스가 꺼져 있어요. 켜거나 아래에서 골라 주세요."
}
