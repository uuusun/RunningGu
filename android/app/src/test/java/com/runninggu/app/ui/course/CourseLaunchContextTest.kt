package com.runninggu.app.ui.course

import com.runninggu.app.data.local.LocationProvider
import com.runninggu.app.data.local.LocationResult
import com.runninggu.app.data.model.CourseTargetKm
import com.runninggu.app.data.model.PoiItem
import com.runninggu.app.data.repository.FakeCourseRepository
import com.runninggu.app.data.repository.FakeGeocodeRepository
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.Recovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S7 동선 → S8 러닝코스 연계. (SPEC §4.10 · §4.11-1 · 매핑표 D-15 개정)
 *
 * S7 에서 [러닝코스에서 보기] 를 누르면 **숙소가 출발지로 채워져 있어야** 한다. 안 그러면
 * 사용자는 방금 고른 숙소를 프리셋에서 다시 찾는다.
 *
 * 목표 거리는 `min(RECOVERY.walk, 5)` 다. **`walk` 는 거리 라벨이 아니라 상한이라**
 * 원본을 그대로 옮기면 틀리는 자리다(AGENTS 6장).
 */
class CourseLaunchContextTest {

    private val dispatcher = StandardTestDispatcher()

    private val stay = PoiItem(
        name = "부산 해운대 호텔",
        address = "부산 해운대구",
        description = "",
        lat = 35.1587,
        lng = 129.1604,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        CourseLaunchContext.resetForTest()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        CourseLaunchContext.resetForTest()
    }

    private fun viewModel() = CourseViewModel(
        repository = FakeCourseRepository,
        geocodeRepository = FakeGeocodeRepository,
        locationProvider = DeniedProvider,
    )

    @Test
    fun `숙소가 출발지로 채워진다`() = runTest(dispatcher) {
        // §4.11-1 "S7 연계 진입 시 출발지=숙소 프리필"
        CourseLaunchContext.set(stay = stay, targetKm = 5.0)

        val state = viewModel().also { advanceUntilIdle() }.uiState.value
        val origin = state.origin as OriginState.Fixed

        assertEquals("부산 해운대 호텔", origin.name)
        assertEquals(35.1587, origin.lat, 0.0001)
        assertEquals(129.1604, origin.lng, 0.0001)
        assertEquals(OriginState.Fixed.Source.ITINERARY, origin.from)
    }

    @Test
    fun `목표 거리가 넘어온 값으로 채워진다`() = runTest(dispatcher) {
        // 풀 마라톤은 min(3, 5) = 3km 다 — 기본 5km 가 아니다
        CourseLaunchContext.set(stay = stay, targetKm = Recovery.defaultCourseTargetKm(EventType.FULL))

        val state = viewModel().also { advanceUntilIdle() }.uiState.value

        assertEquals(3.0, state.targetKm, 0.0001)
    }

    @Test
    fun `숙소 없이 추천받았으면 목표 거리만 반영한다`() = runTest(dispatcher) {
        // 숙소를 안 고르고 추천받는 경로가 있다(§4.9). 없는 좌표를 지어내지 않는다
        CourseLaunchContext.set(stay = null, targetKm = 3.0)

        val state = viewModel().also { advanceUntilIdle() }.uiState.value

        assertEquals(3.0, state.targetKm, 0.0001)
        assertTrue("출발지를 지어내면 안 된다", state.origin is OriginState.Undecided)
    }

    @Test
    fun `한 번만 쓰인다`() = runTest(dispatcher) {
        // 탭바로 S8 을 다시 열면 프리필이 되살아나면 안 된다 — 그사이 사용자가 출발지를
        // 바꿨을 수 있고, 그 선택이 이겨야 한다
        CourseLaunchContext.set(stay = stay, targetKm = 5.0)
        viewModel().also { advanceUntilIdle() }

        val second = viewModel().also { advanceUntilIdle() }.uiState.value

        assertTrue(second.origin is OriginState.Undecided)
    }

    @Test
    fun `연계로 들어오지 않으면 아무것도 안 바뀐다`() = runTest(dispatcher) {
        // 탭바로 그냥 열었을 때다. 기본값이 흔들리면 안 된다
        val state = viewModel().also { advanceUntilIdle() }.uiState.value

        assertTrue(state.origin is OriginState.Undecided)
        assertEquals(CourseTargetKm.DEFAULT, state.targetKm, 0.0001)
    }
}

/** 이 테스트는 GPS 를 안 쓴다. */
private object DeniedProvider : LocationProvider {
    override suspend fun current(): LocationResult = LocationResult.PermissionDenied
}
