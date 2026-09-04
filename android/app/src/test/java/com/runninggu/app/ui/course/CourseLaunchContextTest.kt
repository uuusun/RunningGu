package com.runninggu.app.ui.course

import androidx.lifecycle.SavedStateHandle
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
 * S7 동선 → S8 러닝코스 연계. (SPEC §4.10 · §4.11-1 · 매핑표 D-15)
 *
 * S7 에서 [러닝코스에서 보기] 를 누르면 **숙소가 출발지로 채워져 있어야** 한다. 안 그러면
 * 사용자는 방금 고른 숙소를 프리셋에서 다시 찾는다.
 *
 * 목표 거리는 `min(RECOVERY.walk, 5)` 다. **`walk` 는 거리 라벨이 아니라 상한이라**
 * 원본을 그대로 옮기면 틀리는 자리다(AGENTS 6장).
 *
 * `SavedStateHandle` 은 **S8 백스택 항목 하나**를 뜻한다. 진입마다 다른 handle 이고,
 * 같은 handle 로 ViewModel 을 다시 만드는 것이 프로세스 재생성이다(#178 리뷰).
 */
class CourseLaunchContextTest {

    private val dispatcher = StandardTestDispatcher()

    private val stay = CourseLaunchContext.Stay(
        name = "부산 해운대 호텔",
        lat = 35.1587,
        lng = 129.1604,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** S7 이 [러닝코스에서 보기] 로 띄운 S8 항목. */
    private fun launchedFromItinerary(stay: CourseLaunchContext.Stay?, targetKm: Double) =
        SavedStateHandle().also { CourseLaunchContext.set(it, stay = stay, targetKm = targetKm) }

    private fun viewModel(launchState: SavedStateHandle = SavedStateHandle()) = CourseViewModel(
        repository = FakeCourseRepository,
        geocodeRepository = FakeGeocodeRepository,
        launchState = launchState,
    )

    @Test
    fun `숙소가 출발지로 채워진다`() = runTest(dispatcher) {
        // §4.11-1 "S7 연계 진입 시 출발지=숙소 프리필"
        val entry = launchedFromItinerary(stay = stay, targetKm = 5.0)

        val state = viewModel(entry).also { advanceUntilIdle() }.uiState.value
        val origin = state.origin as OriginState.Fixed

        assertEquals("부산 해운대 호텔", origin.name)
        assertEquals(35.1587, origin.lat, 0.0001)
        assertEquals(129.1604, origin.lng, 0.0001)
        assertEquals(OriginState.Fixed.Source.ITINERARY, origin.from)
    }

    @Test
    fun `목표 거리가 넘어온 값으로 채워진다`() = runTest(dispatcher) {
        // 풀 마라톤은 min(3, 5) = 3km 다 — 기본 5km 가 아니다
        val entry = launchedFromItinerary(
            stay = stay,
            targetKm = Recovery.defaultCourseTargetKm(EventType.FULL),
        )

        val state = viewModel(entry).also { advanceUntilIdle() }.uiState.value

        assertEquals(3.0, state.targetKm, 0.0001)
    }

    @Test
    fun `숙소 없이 추천받았으면 목표 거리만 반영한다`() = runTest(dispatcher) {
        // 숙소를 안 고르고 추천받는 경로가 있다(§4.9). 없는 좌표를 지어내지 않는다
        val entry = launchedFromItinerary(stay = null, targetKm = 3.0)

        val state = viewModel(entry).also { advanceUntilIdle() }.uiState.value

        assertEquals(3.0, state.targetKm, 0.0001)
        assertTrue("출발지를 지어내면 안 된다", state.origin is OriginState.Undecided)
    }

    @Test
    fun `연계로 들어오지 않으면 아무것도 안 바뀐다`() = runTest(dispatcher) {
        // 탭바로 그냥 열었을 때다. 기본값이 흔들리면 안 된다
        val state = viewModel().also { advanceUntilIdle() }.uiState.value

        assertTrue(state.origin is OriginState.Undecided)
        assertEquals(CourseTargetKm.DEFAULT, state.targetKm, 0.0001)
    }

    @Test
    fun `연계로 열린 뒤 탭바로 다시 열면 프리필이 없다`() = runTest(dispatcher) {
        // 진입마다 백스택 항목이 다르다. 전역에 두면 여기서 이전 숙소가 되살아난다
        val itineraryEntry = launchedFromItinerary(stay = stay, targetKm = 5.0)
        viewModel(itineraryEntry).also { advanceUntilIdle() }

        val tabEntry = SavedStateHandle()
        val second = viewModel(tabEntry).also { advanceUntilIdle() }.uiState.value

        assertTrue("탭바 진입에 이전 숙소가 남았다", second.origin is OriginState.Undecided)
        assertEquals(CourseTargetKm.DEFAULT, second.targetKm, 0.0001)
    }

    @Test
    fun `연계가 중간에 끊겨도 다음 진입에 남지 않는다`() = runTest(dispatcher) {
        // S7 이 값을 담은 뒤 S8 이 열리지 않고 흐름이 끝난 경우다. 값은 그 항목과 함께
        // 사라지므로 **S8 을 여는 데 쓰이지 않은 handle** 은 다음 진입에 영향이 없다
        launchedFromItinerary(stay = stay, targetKm = 5.0)

        val state = viewModel(SavedStateHandle()).also { advanceUntilIdle() }.uiState.value

        assertTrue(state.origin is OriginState.Undecided)
        assertEquals(CourseTargetKm.DEFAULT, state.targetKm, 0.0001)
    }

    @Test
    fun `프로세스가 재생성돼도 같은 진입이면 프리필이 돌아온다`() = runTest(dispatcher) {
        // 항목이 살아 있으면 상태도 복원된다. 값을 읽고 지우면 여기서 빈 화면이 된다
        val entry = launchedFromItinerary(stay = stay, targetKm = 3.0)
        viewModel(entry).also { advanceUntilIdle() }

        val restored = viewModel(entry).also { advanceUntilIdle() }.uiState.value

        assertEquals("부산 해운대 호텔", (restored.origin as OriginState.Fixed).name)
        assertEquals(3.0, restored.targetKm, 0.0001)
    }
}
