package com.runninggu.app.ui.course

import com.runninggu.app.data.local.LocationProvider
import com.runninggu.app.data.local.LocationResult
import com.runninggu.app.domain.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [내 위치] 와 수동 출발지 선택의 경합. (SPEC §4.11-1 ① · #92 리뷰)
 *
 * GPS 는 최대 6초가 걸린다. **그동안 사용자는 가만히 있지 않는다** — 기다리다 프리셋을
 * 누르거나 검색으로 출발지를 정한다. 그때 늦게 도착한 GPS 결과가 그 선택을 덮으면,
 * 화면에 보이는 출발지와 서버에 조회한 좌표가 어긋난다.
 */
class MyLocationRaceTest {

    private val dispatcher = StandardTestDispatcher()

    /** 사용자가 직접 고른 출발지. 서울시청 프리셋과 같은 자리다. */
    private val picked = OriginState.Fixed(
        name = "서울시청",
        lat = 37.5663,
        lng = 126.9779,
        from = OriginState.Fixed.Source.PRESET,
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(provider: LocationProvider) =
        CourseViewModel(
            repository = com.runninggu.app.data.repository.FakeCourseRepository,
            geocodeRepository = com.runninggu.app.data.repository.FakeGeocodeRepository,
            locationProvider = provider,
        )

    @Test
    fun `늦게 온 GPS 성공이 사용자가 고른 출발지를 덮지 않는다`() = runTest(dispatcher) {
        val slowGps = SlowLocationProvider(
            delayMs = 3_000,
            result = LocationResult.Found(LatLng(35.1587, 129.1604)), // 해운대
        )
        val viewModel = viewModel(slowGps)

        viewModel.onUseMyLocation()
        advanceTimeBy(100)
        // 기다리다 사용자가 직접 고른다
        viewModel.onOriginChange(picked)
        advanceUntilIdle()

        assertEquals(picked, viewModel.uiState.value.origin)
    }

    @Test
    fun `늦게 온 GPS 실패도 사용자가 고른 출발지를 되돌리지 않는다`() = runTest(dispatcher) {
        // 실패 복구도 결국 사용자의 선택을 덮는 일이다
        val slowGps = SlowLocationProvider(delayMs = 3_000, result = LocationResult.Timeout)
        val viewModel = viewModel(slowGps)

        viewModel.onUseMyLocation()
        advanceTimeBy(100)
        viewModel.onOriginChange(picked)
        advanceUntilIdle()

        assertEquals(picked, viewModel.uiState.value.origin)
        assertNull("고른 뒤에 실패 문구가 떴다", viewModel.uiState.value.locationMessage)
    }

    @Test
    fun `아무도 끼어들지 않으면 GPS 결과가 출발지가 된다`() = runTest(dispatcher) {
        val gps = SlowLocationProvider(
            delayMs = 3_000,
            result = LocationResult.Found(LatLng(35.1587, 129.1604)),
        )
        val viewModel = viewModel(gps)

        viewModel.onUseMyLocation()
        advanceUntilIdle()

        val origin = viewModel.uiState.value.origin as OriginState.Fixed
        assertEquals(OriginState.Fixed.Source.GPS, origin.from)
        assertEquals(35.1587, origin.lat, 0.0001)
    }

    @Test
    fun `조회 중에는 위치를 확인하는 중 상태다`() = runTest(dispatcher) {
        val gps = SlowLocationProvider(delayMs = 3_000, result = LocationResult.Timeout)
        val viewModel = viewModel(gps)

        viewModel.onUseMyLocation()
        advanceTimeBy(100)

        assertEquals(OriginState.Locating, viewModel.uiState.value.origin)
        advanceUntilIdle()
    }

    @Test
    fun `연타하면 마지막 조회 결과만 반영된다`() = runTest(dispatcher) {
        // 앞 요청이 늦게 도착해 뒤 요청 결과를 덮으면 안 된다
        val gps = SlowLocationProvider(
            delayMs = 3_000,
            result = LocationResult.Found(LatLng(35.1587, 129.1604)),
        )
        val viewModel = viewModel(gps)

        viewModel.onUseMyLocation()
        advanceTimeBy(100)
        viewModel.onUseMyLocation()
        advanceUntilIdle()

        assertEquals(1, gps.completedCount)
        assertTrue(viewModel.uiState.value.origin is OriginState.Fixed)
    }

    @Test
    fun `끼어든 뒤에는 GPS 를 다시 눌러야 반영된다`() = runTest(dispatcher) {
        // 무효화가 영구히 막아 버리면 [내 위치] 가 죽은 버튼이 된다
        val gps = SlowLocationProvider(
            delayMs = 3_000,
            result = LocationResult.Found(LatLng(35.1587, 129.1604)),
        )
        val viewModel = viewModel(gps)

        viewModel.onUseMyLocation()
        advanceTimeBy(100)
        viewModel.onOriginChange(picked)
        advanceUntilIdle()

        viewModel.onUseMyLocation()
        advanceUntilIdle()

        val origin = viewModel.uiState.value.origin as OriginState.Fixed
        assertEquals(OriginState.Fixed.Source.GPS, origin.from)
    }
}

/** 느리게 답하는 가짜 위치 제공자. 실제 GPS 가 6초까지 걸리는 것을 흉내 낸다. */
private class SlowLocationProvider(
    private val delayMs: Long,
    private val result: LocationResult,
) : LocationProvider {

    /** 끝까지 간 조회 횟수. 앞 요청이 취소됐는지 확인한다. */
    var completedCount = 0
        private set

    override suspend fun current(): LocationResult {
        delay(delayMs)
        completedCount++
        return result
    }
}
