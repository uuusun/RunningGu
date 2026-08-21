package com.runninggu.app.ui.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.model.CourseTargetKm
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.ui.userMessageOrDefault
import com.runninggu.app.data.repository.CourseRepository
import com.runninggu.app.data.repository.FakeCourseRepository
import com.runninggu.app.data.repository.FakeGeocodeRepository
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(CourseUiState())
    val uiState: StateFlow<CourseUiState> = _uiState.asStateFlow()

    /** 슬라이더를 끌 때마다 조회하면 요청이 쏟아진다 — 이전 조회를 취소하고 마지막 것만 남긴다. */
    private var nearbyJob: Job? = null

    /** 칩을 빠르게 두 번 누르면 먼저 보낸 응답이 늦게 도착해 목록이 어긋난다 — 같은 이유로 끊는다. */
    private var regionJob: Job? = null

    /** 검색 버튼 연타도 마지막 것만 남긴다. */
    private var searchJob: Job? = null

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

    /** 출발지가 정해지면 바로 조회한다. (§4.11-1) */
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

    fun onItemSelect(routeId: String?) {
        _uiState.update { it.copy(selectedRouteId = routeId) }
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
            _uiState.update { it.copy(nearby = state, selectedRouteId = null) }
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
        fun factory(
            repository: CourseRepository = FakeCourseRepository,
            geocodeRepository: GeocodeRepository = FakeGeocodeRepository,
        ) = viewModelFactory {
            initializer { CourseViewModel(repository, geocodeRepository) }
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
