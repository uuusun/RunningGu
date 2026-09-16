package com.runninggu.app.ui.course

import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.model.CourseRegion
import com.runninggu.app.data.model.CuratedCourseDetail
import com.runninggu.app.data.model.Difficulty
import com.runninggu.app.data.model.NearbyCourses
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.data.model.SpotLoop
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.CoursePage
import com.runninggu.app.data.repository.CourseRepository
import com.runninggu.app.data.repository.FakeGeocodeRepository
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
 * 걷기 스팟을 탭하면 그 스팟을 진입점으로 순환 경로를 요청한다. (SPEC §4.11-5 · API 명세 §6-5 · 결정-68)
 *
 * 매핑표 S8 "걷기 스팟 선택" 행이 정한 것을 고정한다.
 *
 * | 무엇 | 기대 |
 * |---|---|
 * | 탭 | `loop(lat/lng = 출발지 · entryLat/Lng = 스팟 · targetKm = 슬라이더 · entryName = 스팟 이름)` |
 * | 성공 | 지도에 그 경로 · [저장] 활성 · "저장할 수 없어요" 내림 · 출처에 OSM 합류 |
 * | `route: null` | 핀 유지 · "이 근처엔 자동 경로를 못 만들었어요" · [다시 시도] 없음 |
 * | `503`·네트워크 | 같은 문구 + [다시 시도] |
 * | 같은 스팟 재탭 | 다시 부르지 않는다 |
 * | 다른 스팟 탭 | 이전 선을 지운다 · 늦게 온 답은 그리지 않는다 |
 * | 출발지 | 바뀌지 않는다 · 목록 재조회 없음 |
 */
class SpotRouteTest {

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

    private val park = NearbyItem.Place(
        name = "여의도공원",
        distanceM = 650,
        lat = 37.5264,
        lng = 126.9227,
        category = "공원",
        address = null,
        placeUrl = null,
    )

    private val otherPark = park.copy(name = "샛강생태공원", distanceM = 1240, lat = 37.5201, lng = 126.9260)

    private fun loopRoute(spot: NearbyItem.Place) = NearbyItem.Route(
        routeId = "osm:loop-${spot.name}",
        name = "${spot.name} 주변 5km 평지 러닝코스",
        distanceM = spot.distanceM,
        lat = spot.lat,
        lng = spot.lng,
        dataSource = CourseDataSource.OSM_GENERATED,
        difficulty = Difficulty.EASY,
        routeKm = 5.06,
        durationMin = 46,
        gainM = 21,
        elevationProfileM = listOf(12, 13, 15),
        shortfall = false,
        pathPolyline = "s{~kFmxwdW}A?_@wAaB{@",
    )

    private fun viewModel(repository: LoopStub): CourseViewModel = CourseViewModel(
        repository = repository,
        geocodeRepository = FakeGeocodeRepository,
        savedCourseRepository = NoSaved,
    )

    private fun ready(repository: LoopStub): CourseViewModel {
        val vm = viewModel(repository)
        vm.onOriginChange(origin)
        return vm
    }

    @Test
    fun `스팟을 탭하면 출발지는 그대로 두고 스팟 좌표를 진입점으로 loop 를 부른다`() = runTest(dispatcher) {
        val repo = LoopStub(items = listOf(park), answer = { SpotLoop(loopRoute(park), listOf(OSM)) })
        val vm = ready(repo)
        advanceUntilIdle()

        vm.onItemSelect(park)
        // 부르는 순간 카드는 "만드는 중" 이고 저장할 수 없다는 말은 내리지 않는다
        assertTrue(vm.uiState.value.spotRouteLoading)
        assertFalse(vm.uiState.value.walkSpotPicked)
        advanceUntilIdle()

        val call = repo.calls.single()
        assertEquals(origin.lat, call.lat, 0.0)
        assertEquals(origin.lng, call.lng, 0.0)
        assertEquals(park.lat, call.entryLat, 0.0)
        assertEquals(park.lng, call.entryLng, 0.0)
        assertEquals(vm.uiState.value.targetKm, call.targetKm, 0.0)
        assertEquals(park.name, call.entryName)
        // 출발지도 목록도 그대로다 — near 는 처음 한 번뿐
        assertEquals(origin, vm.uiState.value.origin)
        assertEquals(1, repo.nearCalls)
    }

    @Test
    fun `경로가 오면 지도에 그리고 저장할 수 있고 출처에 OSM 이 합류한다`() = runTest(dispatcher) {
        val vm = ready(LoopStub(items = listOf(park), answer = { SpotLoop(loopRoute(park), listOf(OSM)) }))
        advanceUntilIdle()
        vm.onItemSelect(park)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(loopRoute(park), state.mappedRoute)
        assertEquals(loopRoute(park), state.selectedRoute)
        assertTrue(state.canSave)
        assertFalse(state.walkSpotPicked)
        assertNull(state.spotRouteMessage)
        // 선이 그려졌으니 핀은 없다
        assertTrue(state.mapPins.isEmpty())
        assertNull(state.activePinId)
        // `near` 의 출처 뒤에 OSM 이 붙고, 두 번 적지 않는다
        assertEquals(listOf(KAKAO, OSM), state.displayedAttributions)
    }

    @Test
    fun `route 가 null 이면 핀을 두고 못 만들었다는 문구만 낸다 - 다시 시도는 없다`() = runTest(dispatcher) {
        val vm = ready(LoopStub(items = listOf(park), answer = { SpotLoop(route = null) }))
        advanceUntilIdle()
        vm.onItemSelect(park)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNull(state.mappedRoute)
        assertEquals(CourseUiState.SPOT_ROUTE_NOT_FOUND, state.spotRouteMessage)
        assertFalse(state.canRetrySpotRoute)
        assertTrue(state.walkSpotPicked)
        assertEquals(1, state.mapPins.size)
        assertEquals("nearby-0", state.activePinId)
        assertFalse(state.canSave)
    }

    @Test
    fun `503 이나 네트워크 실패면 같은 문구에 다시 시도가 붙고 누르면 다시 부른다`() = runTest(dispatcher) {
        var fail = true
        val repo = LoopStub(items = listOf(park), answer = {
            if (fail) throw ApiException.Http(503, ApiErrorCode.COURSE_SOURCES_UNAVAILABLE, null)
            SpotLoop(loopRoute(park), listOf(OSM))
        })
        val vm = ready(repo)
        advanceUntilIdle()
        vm.onItemSelect(park)
        advanceUntilIdle()

        assertEquals(CourseUiState.SPOT_ROUTE_NOT_FOUND, vm.uiState.value.spotRouteMessage)
        assertTrue(vm.uiState.value.canRetrySpotRoute)
        assertEquals(1, vm.uiState.value.mapPins.size)

        fail = false
        vm.onSpotRouteRetry()
        advanceUntilIdle()
        assertEquals(2, repo.calls.size)
        assertEquals(loopRoute(park), vm.uiState.value.mappedRoute)

        // 네트워크 끊김도 실패 갈래다
        val offline = ready(LoopStub(items = listOf(park), answer = { throw ApiException.Network(IOException("끊김")) }))
        advanceUntilIdle()
        offline.onItemSelect(park)
        advanceUntilIdle()
        assertTrue(offline.uiState.value.canRetrySpotRoute)
    }

    @Test
    fun `같은 스팟은 세션 안에서 다시 부르지 않는다 - 못 만든 것도 기억한다`() = runTest(dispatcher) {
        val repo = LoopStub(items = listOf(park, otherPark), answer = { call ->
            if (call.entryLat == park.lat) SpotLoop(loopRoute(park), listOf(OSM)) else SpotLoop(route = null)
        })
        val vm = ready(repo)
        advanceUntilIdle()

        vm.onItemSelect(park)
        advanceUntilIdle()
        vm.onItemSelect(otherPark)
        advanceUntilIdle()
        vm.onItemSelect(park)
        advanceUntilIdle()
        vm.onItemSelect(otherPark)
        advanceUntilIdle()

        assertEquals(2, repo.calls.size)
        assertEquals(CourseUiState.SPOT_ROUTE_NOT_FOUND, vm.uiState.value.spotRouteMessage)
        vm.onItemSelect(park)
        assertEquals(loopRoute(park), vm.uiState.value.mappedRoute)
        assertEquals(2, repo.calls.size)
    }

    @Test
    fun `목표 거리가 바뀌어 재조회하면 같은 스팟도 새 거리로 다시 부른다`() = runTest(dispatcher) {
        val repo = LoopStub(items = listOf(park), answer = { SpotLoop(loopRoute(park), listOf(OSM)) })
        val vm = ready(repo)
        advanceUntilIdle()
        vm.onItemSelect(park)
        advanceUntilIdle()

        vm.onTargetKmChange(10.0)
        vm.onTargetKmCommit()
        advanceUntilIdle()
        // 재조회로 목록이 갈리면 스팟 경로는 지운다
        assertEquals(SpotRouteState.Idle, vm.uiState.value.spotRoute)

        vm.onItemSelect(park)
        advanceUntilIdle()
        assertEquals(2, repo.calls.size)
        assertEquals(10.0, repo.calls.last().targetKm, 0.0)
    }

    @Test
    fun `다른 스팟을 고르면 이전 선을 지우고 늦게 온 답은 그리지 않는다`() = runTest(dispatcher) {
        val repo = LoopStub(items = listOf(park, otherPark), answer = { call ->
            if (call.entryLat == park.lat) SpotLoop(loopRoute(park), listOf(OSM)) else SpotLoop(route = null)
        })
        val vm = ready(repo)
        advanceUntilIdle()

        vm.onItemSelect(park)
        // park 의 답이 오기 전에 otherPark 로 옮긴다
        vm.onItemSelect(otherPark)
        assertNull(vm.uiState.value.mappedRoute)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(otherPark, state.selectedItem)
        // park 의 경로가 otherPark 아래에 그려지면 안 된다
        assertNull(state.mappedRoute)
        assertEquals(CourseUiState.SPOT_ROUTE_NOT_FOUND, state.spotRouteMessage)
    }

    @Test
    fun `경로를 고르면 스팟 경로는 지운다`() = runTest(dispatcher) {
        val curated = loopRoute(park).copy(routeId = "cur-1", dataSource = CourseDataSource.API_GPX)
        val vm = ready(LoopStub(items = listOf(curated, park), answer = { SpotLoop(loopRoute(park), listOf(OSM)) }))
        advanceUntilIdle()
        vm.onItemSelect(park)
        advanceUntilIdle()
        assertEquals(loopRoute(park), vm.uiState.value.mappedRoute)

        vm.onItemSelect(curated)
        assertEquals(SpotRouteState.Idle, vm.uiState.value.spotRoute)
        assertEquals(curated, vm.uiState.value.mappedRoute)
        assertEquals(listOf(KAKAO), vm.uiState.value.displayedAttributions)
    }

    private companion object {
        const val OSM = "© OpenStreetMap contributors"
        const val KAKAO = "카카오 로컬"
    }
}

/** `near` 는 정해 둔 목록, `loop` 는 호출마다 [answer] 로 답한다. 호출 인자를 기록한다. */
private class LoopStub(
    private val items: List<NearbyItem>,
    private val answer: (LoopCall) -> SpotLoop,
) : CourseRepository {
    data class LoopCall(
        val lat: Double,
        val lng: Double,
        val entryLat: Double,
        val entryLng: Double,
        val targetKm: Double,
        val entryName: String?,
    )

    val calls = mutableListOf<LoopCall>()
    var nearCalls = 0

    override suspend fun near(
        lat: Double,
        lng: Double,
        targetKm: Double,
        radiusKm: Double,
        size: Int,
    ): NearbyCourses {
        nearCalls++
        return NearbyCourses(items = items, attributions = listOf("카카오 로컬"))
    }

    override suspend fun loop(
        lat: Double,
        lng: Double,
        entryLat: Double,
        entryLng: Double,
        targetKm: Double,
        entryName: String?,
    ): SpotLoop {
        val call = LoopCall(lat, lng, entryLat, entryLng, targetKm, entryName)
        calls += call
        return answer(call)
    }

    override suspend fun byRegion(region: String?, page: Int, size: Int) = CoursePage()
    override suspend fun regions(): List<CourseRegion> = emptyList()
    override suspend fun detail(courseId: String): CuratedCourseDetail = error("이 테스트는 상세를 부르지 않는다")
}

private object NoSaved : com.runninggu.app.data.repository.SavedCourseRepository {
    override suspend fun save(route: NearbyItem.Route) = null
    override suspend fun list(page: Int, size: Int) = com.runninggu.app.data.repository.SavedCoursePage()
    override suspend fun detail(id: Long): com.runninggu.app.data.model.SavedCourseDetail = throw NotImplementedError()
    override suspend fun delete(id: Long) = throw NotImplementedError()
}
