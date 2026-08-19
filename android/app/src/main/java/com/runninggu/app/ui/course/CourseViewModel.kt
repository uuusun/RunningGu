package com.runninggu.app.ui.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.CourseRepository
import com.runninggu.app.data.repository.FakeCourseRepository
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(CourseUiState())
    val uiState: StateFlow<CourseUiState> = _uiState.asStateFlow()

    /** 슬라이더를 끌 때마다 조회하면 요청이 쏟아진다 — 이전 조회를 취소하고 마지막 것만 남긴다. */
    private var nearbyJob: Job? = null

    init {
        loadRegions()
        loadRegionCourses()
    }

    fun onTabChange(tab: CourseUiState.Tab) {
        _uiState.update { it.copy(tab = tab) }
    }

    /** 출발지가 정해지면 바로 조회한다. (§4.11-1) */
    fun onOriginChange(origin: OriginState) {
        _uiState.update { it.copy(origin = origin) }
        if (origin is OriginState.Fixed) refreshNearby()
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
        fun factory(repository: CourseRepository = FakeCourseRepository) = viewModelFactory {
            initializer { CourseViewModel(repository) }
        }
    }

    fun loadRegionCourses() {
        viewModelScope.launch {
            _uiState.update { it.copy(regionCourses = RegionCoursesState.Loading) }
            val state = try {
                val page = repository.byRegion(_uiState.value.selectedRegion)
                if (page.courses.isEmpty()) {
                    RegionCoursesState.Empty
                } else {
                    RegionCoursesState.Content(page.courses, page.hasNext)
                }
            } catch (e: ApiException) {
                RegionCoursesState.Error(e.userMessageOrDefault())
            }
            _uiState.update { it.copy(regionCourses = state) }
        }
    }
}

/** 슬라이더 값을 계약 범위·단위로 맞춘다. 1~21km, 0.5 단위. (SPEC §4.11-2) */
internal fun snapTargetKm(raw: Double): Double {
    val step = CourseUiState.TARGET_STEP_KM
    val snapped = (raw / step).roundToInt() * step
    return snapped.coerceIn(CourseUiState.MIN_TARGET_KM, CourseUiState.MAX_TARGET_KM)
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

/** 서버가 준 문구가 있으면 그걸 쓰고, 없으면 기본 문구. (§0-3 — detail 은 개발자용이라 안 쓴다) */
internal fun ApiException.userMessageOrDefault(): String =
    (this as? ApiException.Http)?.userMessage ?: "정보를 불러오지 못했어요."
