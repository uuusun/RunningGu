package com.runninggu.app.ui.course

import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.model.CourseRegion
import com.runninggu.app.data.model.CourseSource
import com.runninggu.app.data.model.Difficulty
import com.runninggu.app.data.model.NearbyCourses
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.CoursePage
import com.runninggu.app.data.repository.CourseRepository
import com.runninggu.app.data.repository.FakeGeocodeRepository
import com.runninggu.app.data.local.LocationProvider
import com.runninggu.app.data.local.LocationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * S8 [내 주변]이 서버를 볼 때의 네 갈래. (SPEC §4.11-7 · §3-5 · API 명세 §6-1)
 *
 * **스텁일 때는 이 셋 중 하나만 났다.** `FakeCourseRepository` 는 늘 같은 목록을
 * 성공으로 돌려줘서 Empty·Error·부분 실패가 화면에 뜬 적이 없다. 서버로 바꾸는 순간
 * 넷 다 실제로 나므로 여기서 고정한다.
 *
 * 가르는 기준은 **원천이 실패했는가**와 **보여줄 게 있는가** 둘이다(§6-1).
 *
 * | 응답 | 화면 |
 * |---|---|
 * | `200` · 항목 0건 | Empty — 실패가 아니다 |
 * | `200` · 항목 있음 + `degradedSources` | Content + **비차단 안내** |
 * | `503 COURSE_SOURCES_UNAVAILABLE` | Error |
 * | 네트워크 끊김 | Error (문구가 다르다) |
 */
class NearbyStateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val origin = OriginState.Fixed(
        name = "서울시청",
        lat = 37.5663,
        lng = 126.9779,
        from = OriginState.Fixed.Source.PRESET,
    )

    private suspend fun kotlinx.coroutines.test.TestScope.nearby(
        repository: CourseRepository,
    ): NearbyState {
        val viewModel = CourseViewModel(
            repository = repository,
            geocodeRepository = FakeGeocodeRepository,
            locationProvider = NoLocation,
            savedCourseRepository = NoSavedCourses,
        )
        viewModel.onOriginChange(origin)
        advanceUntilIdle()
        return viewModel.uiState.value.nearby
    }

    @Test
    fun `정상 0건은 빈 상태지 오류가 아니다`() = runTest(dispatcher) {
        // 반경 안에 코스가 없는 것은 사실이다. 오류로 그리면 "다시 시도" 를 권하게 되는데
        // 다시 눌러도 같은 0건이 온다 (§4.11-7)
        val state = nearby(StubCourses(NearbyCourses()))

        assertEquals(NearbyState.Empty, state)
    }

    @Test
    fun `원천 실패로 표시할 게 없으면 오류다`() = runTest(dispatcher) {
        // 서버가 `503 COURSE_SOURCES_UNAVAILABLE` 을 준다. 0건과 같은 화면을 주면
        // "이 근처엔 코스가 없다" 로 읽혀 **사실과 다르다**
        val state = nearby(
            StubCourses(
                error = ApiException.Http(503, ApiErrorCode.COURSE_SOURCES_UNAVAILABLE, null),
            ),
        )

        assertTrue("503 이 빈 상태로 접혔다: $state", state is NearbyState.Error)
        assertEquals(
            "코스 정보를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.",
            (state as NearbyState.Error).message,
        )
    }

    @Test
    fun `네트워크 실패는 다른 문구다`() = runTest(dispatcher) {
        // 서버가 못 준 것과 내가 못 부른 것은 사용자가 할 일이 다르다
        val state = nearby(StubCourses(error = ApiException.Network(IOException("끊김"))))

        assertEquals("네트워크에 연결할 수 없어요.", (state as NearbyState.Error).message)
    }

    @Test
    fun `일부 원천이 실패해도 받은 항목은 그대로 보여준다`() = runTest(dispatcher) {
        // 표시할 게 있으면 서버가 `200` + `degradedSources` 를 준다. 이때 오류로 넘기면
        // **멀쩡히 받은 코스를 안 보여주게 된다** (§6-1 · 명세 예외 항목)
        val state = nearby(
            StubCourses(
                NearbyCourses(
                    items = listOf(route()),
                    degradedSources = listOf(CourseSource.OSM),
                ),
            ),
        )

        val content = state as NearbyState.Content
        assertEquals(1, content.items.size)
        assertEquals(listOf(CourseSource.OSM), content.degradedSources)
        assertTrue("부분 실패 안내가 없다", content.degradedMessage != null)
    }

    @Test
    fun `장소가 앞에 와도 저장 대상은 강조된 경로다`() = runTest(dispatcher) {
        // **서버는 경로와 장소를 거리순으로 섞는다**(§6-1). 스팟이 더 가까우면 목록 1번이
        // 스팟이고 경로는 뒤에 온다. 지도는 그 경로를 그리는데 선택이 비어 있으면 목록
        // 카드가 강조되지 않아, 사용자는 **어느 코스인지 모르는 채 [저장]** 을 누른다(#190 리뷰).
        val viewModel = CourseViewModel(
            repository = StubCourses(NearbyCourses(items = listOf(place(), route()))),
            geocodeRepository = FakeGeocodeRepository,
            locationProvider = NoLocation,
            savedCourseRepository = NoSavedCourses,
        )

        viewModel.onOriginChange(origin)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // 지도가 그리는 것 · 목록에서 강조되는 것 · 저장되는 것이 **셋 다 같아야 한다**
        assertEquals("r-1", (state.selectedItem as? NearbyItem.Route)?.routeId)
        assertEquals("r-1", state.mappedRoute?.routeId)
        assertEquals("r-1", state.selectedRoute?.routeId)
        assertTrue("저장이 잠겼다", state.canSave)
    }

    @Test
    fun `경로가 없으면 아무것도 고르지 않고 저장도 잠긴다`() = runTest(dispatcher) {
        // 수도권 기본값이다 — 코스 0건에 걷기 스팟만 온다(§4.11 📌 · AGENTS 6장).
        // 그릴 경로가 없으니 저장할 것도 없다.
        val viewModel = CourseViewModel(
            repository = StubCourses(NearbyCourses(items = listOf(place()))),
            geocodeRepository = FakeGeocodeRepository,
            locationProvider = NoLocation,
            savedCourseRepository = NoSavedCourses,
        )

        viewModel.onOriginChange(origin)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.selectedItem)
        assertNull(state.mappedRoute)
        assertFalse("경로가 없는데 저장이 열려 있다", state.canSave)
    }

    private fun place() = NearbyItem.Place(
        name = "여의도 한강공원",
        distanceM = 120,
        lat = 37.5285,
        lng = 126.9327,
        category = "공원",
        address = "서울 영등포구 여의동로 330",
        placeUrl = null,
    )

    private fun route() = NearbyItem.Route(
        routeId = "r-1",
        name = "여의도 한강 순환 5km",
        distanceM = 320,
        lat = 37.5263,
        lng = 126.9294,
        dataSource = CourseDataSource.OSM_GENERATED,
        difficulty = Difficulty.EASY,
        routeKm = 5.0,
        durationMin = 32,
        gainM = 12,
        elevationProfileM = listOf(3, 5, 8),
        shortfall = false,
        pathPolyline = "s{~kFmxwdW}A?_@wAaB{@",
    )
}

/** 이 테스트는 [내 위치] 를 안 쓴다 — 출발지는 프리셋으로 정한다. */
private object NoLocation : LocationProvider {
    override suspend fun current(): LocationResult = LocationResult.PermissionDenied
}

/** 정해 둔 응답이나 오류만 돌려주는 가짜. */
private class StubCourses(
    private val result: NearbyCourses = NearbyCourses(),
    private val error: ApiException? = null,
) : CourseRepository {

    override suspend fun near(
        lat: Double,
        lng: Double,
        targetKm: Double,
        radiusKm: Double,
        size: Int,
    ): NearbyCourses {
        error?.let { throw it }
        return result
    }

    override suspend fun byRegion(region: String?, page: Int, size: Int) = CoursePage()

    override suspend fun regions(): List<CourseRegion> = emptyList()
}

/** S8 조회만 보는 테스트라 저장은 부르지 않는다. */
private object NoSavedCourses : com.runninggu.app.data.repository.SavedCourseRepository {
    override suspend fun save(route: NearbyItem.Route) = null
    override suspend fun list(page: Int, size: Int) =
        com.runninggu.app.data.repository.SavedCoursePage()
    override suspend fun detail(id: Long): com.runninggu.app.data.model.SavedCourseDetail =
        throw UnsupportedOperationException("이 테스트는 상세를 부르지 않는다")
    override suspend fun delete(id: Long) = Unit
}
