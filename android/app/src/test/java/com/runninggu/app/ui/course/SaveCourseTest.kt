package com.runninggu.app.ui.course

import com.runninggu.app.data.local.LocationProvider
import com.runninggu.app.data.local.LocationResult
import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.model.Difficulty
import com.runninggu.app.data.model.NearbyCourses
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.data.model.SaveCourseResult
import com.runninggu.app.data.model.SavedCourseDetail
import com.runninggu.app.data.repository.CoursePage
import com.runninggu.app.data.repository.CourseRepository
import com.runninggu.app.data.repository.FakeGeocodeRepository
import com.runninggu.app.data.repository.SavedCoursePage
import com.runninggu.app.data.repository.SavedCourseRepository
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S8 [저장] — `POST /me/courses`. (SPEC §4.11-6 · API 명세 §7-A)
 *
 * 요청 본문을 만드는 일은 매퍼가(`SavedCourseMapperTest`), 호출은 저장소가
 * (`RemoteSavedCourseRepositoryTest`) 이미 고정하고 있다. **여기서 보는 것은 화면이 결과를
 * 어떻게 말하느냐**다 — 저장·중복·게스트·실패가 사용자에게 각각 다르게 읽혀야 한다.
 */
class SaveCourseTest {

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

    private fun route(id: String) = NearbyItem.Route(
        routeId = id,
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

    /** 걷기 스팟만 있는 목록 — 수도권의 기본 경험이다 (SPEC §4.11 📌 · AGENTS 6장). */
    private val place = NearbyItem.Place(
        name = "여의도공원",
        distanceM = 210,
        lat = 37.5264,
        lng = 126.9245,
        category = "공원",
        address = "서울 영등포구",
        placeUrl = null,
    )

    private fun guest() = RecordingSavedCourses(
        error = ApiException.Http(401, ApiErrorCode.UNAUTHORIZED, null),
    )

    private fun viewModel(
        items: List<NearbyItem> = listOf(route("r-1"), route("r-2")),
        saved: SavedCourseRepository = RecordingSavedCourses(),
    ) = CourseViewModel(
        repository = StubCourseRepository(items),
        geocodeRepository = FakeGeocodeRepository,
        locationProvider = DeniedLocationProvider,
        savedCourseRepository = saved,
    )

    /** 출발지를 정하면 목록이 채워진다. 저장은 그 뒤의 이야기다. */
    private suspend fun kotlinx.coroutines.test.TestScope.loaded(
        viewModel: CourseViewModel,
    ): CourseViewModel {
        viewModel.onOriginChange(origin)
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `아무것도 안 골랐으면 저장을 못 누른다`() = runTest(dispatcher) {
        // 무엇이 저장되는지 알 수 없는 상태에서 버튼이 눌리면 안 된다
        val viewModel = loaded(viewModel())

        assertFalse(viewModel.uiState.value.canSave)
    }

    @Test
    fun `걷기 스팟만 있으면 저장할 대상이 없다`() = runTest(dispatcher) {
        // 서울 반경 8km 는 코스 0건에 스팟만 나오는 것이 기본이다 (AGENTS 6장)
        val viewModel = loaded(viewModel(items = listOf(place)))

        assertNull(viewModel.uiState.value.selectedRoute)
        assertFalse(viewModel.uiState.value.canSave)
    }

    @Test
    fun `저장하면 마이에서 볼 수 있다고 알린다`() = runTest(dispatcher) {
        val saved = RecordingSavedCourses(result = SaveCourseResult(id = 7L, created = true))
        val viewModel = loaded(viewModel(saved = saved))

        viewModel.onItemSelect(route("r-1"))
        viewModel.onSaveCourse()
        advanceUntilIdle()

        // 서버가 준 항목을 그대로 넘겼는가 — 화면이 값을 다시 조립하면 안 된다(이슈 #62)
        assertEquals("r-1", saved.savedRoutes.single().routeId)
        val done = viewModel.uiState.value.save as SaveCourseState.Done
        assertEquals("저장했어요. 마이에서 볼 수 있어요.", done.message)
        assertFalse(done.failed)
    }

    @Test
    fun `이미 저장한 코스는 실패가 아니다`() = runTest(dispatcher) {
        // 멱등이라 서버가 새 행 대신 기존 id 를 준다 (§7-A). 사용자가 잘못한 게 없다
        val saved = RecordingSavedCourses(result = SaveCourseResult(id = 7L, created = false))
        val viewModel = loaded(viewModel(saved = saved))

        viewModel.onItemSelect(route("r-1"))
        viewModel.onSaveCourse()
        advanceUntilIdle()

        val done = viewModel.uiState.value.save as SaveCourseState.Done
        assertEquals("이미 저장한 코스예요.", done.message)
        assertFalse(done.failed)
    }

    @Test
    fun `게스트에게는 로그인 모달을 띄운다`() = runTest(dispatcher) {
        // 문구 한 줄이면 어디로 가야 하는지 모른 채 버튼만 다시 누른다 (매핑표 S8 "게스트 modal")
        val viewModel = loaded(viewModel(saved = guest()))

        viewModel.onItemSelect(route("r-1"))
        viewModel.onSaveCourse()
        advanceUntilIdle()

        assertEquals(SaveCourseState.NeedsLogin, viewModel.uiState.value.save)
    }

    @Test
    fun `모달을 닫아도 고른 코스는 그대로다`() = runTest(dispatcher) {
        // 로그인하고 돌아와 다시 누를 수 있어야 한다 — 저장을 예약하지는 않는다 (D-27)
        val viewModel = loaded(viewModel(saved = guest()))
        viewModel.onItemSelect(route("r-1"))
        viewModel.onSaveCourse()
        advanceUntilIdle()

        viewModel.onLoginPromptDismiss()

        assertEquals(SaveCourseState.Idle, viewModel.uiState.value.save)
        assertEquals("r-1", (viewModel.uiState.value.selectedItem as? NearbyItem.Route)?.routeId)
        assertTrue(viewModel.uiState.value.canSave)
    }

    @Test
    fun `그 밖의 실패는 다시 시도하라고 알린다`() = runTest(dispatcher) {
        val saved = RecordingSavedCourses(
            error = ApiException.Http(500, ApiErrorCode.INTERNAL_SERVER_ERROR, null),
        )
        val viewModel = loaded(viewModel(saved = saved))

        viewModel.onItemSelect(route("r-1"))
        viewModel.onSaveCourse()
        advanceUntilIdle()

        val done = viewModel.uiState.value.save as SaveCourseState.Done
        assertEquals("저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.", done.message)
        assertTrue(done.failed)
    }

    @Test
    fun `경로 정보가 없으면 저장할 수 없다고 알린다`() = runTest(dispatcher) {
        // 매퍼가 null 을 준 경우 — geometry 가 없어 fingerprint 를 만들 수 없다
        val saved = RecordingSavedCourses(result = null)
        val viewModel = loaded(viewModel(saved = saved))

        viewModel.onItemSelect(route("r-1"))
        viewModel.onSaveCourse()
        advanceUntilIdle()

        val done = viewModel.uiState.value.save as SaveCourseState.Done
        assertEquals("이 코스는 경로 정보가 없어 저장할 수 없어요.", done.message)
        assertTrue(done.failed)
    }

    @Test
    fun `다른 코스를 고르면 이전 저장 결과가 사라진다`() = runTest(dispatcher) {
        // 안 지우면 "저장했어요" 가 아직 안 누른 코스 아래에 남는다
        val viewModel = loaded(viewModel())
        viewModel.onItemSelect(route("r-1"))
        viewModel.onSaveCourse()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.save is SaveCourseState.Done)

        viewModel.onItemSelect(route("r-2"))

        assertEquals(SaveCourseState.Idle, viewModel.uiState.value.save)
    }

    @Test
    fun `같은 코스를 다시 누르면 결과가 남아 있다`() = runTest(dispatcher) {
        // 고른 것이 안 바뀌었는데 안내만 사라지면 눌러도 아무 일 없는 것처럼 보인다
        val viewModel = loaded(viewModel())
        viewModel.onItemSelect(route("r-1"))
        viewModel.onSaveCourse()
        advanceUntilIdle()

        viewModel.onItemSelect(route("r-1"))

        assertTrue(viewModel.uiState.value.save is SaveCourseState.Done)
    }

    @Test
    fun `보내는 중에는 다시 못 누른다`() = runTest(dispatcher) {
        // 연타로 같은 코스가 두 번 나가면, 멱등이라 서버는 버티지만 헛 왕복이 생긴다.
        // 응답을 붙들어 "보내는 중" 을 실제로 만들어 둔다
        val gate = CompletableDeferred<Unit>()
        val saved = RecordingSavedCourses(gate = gate)
        val viewModel = loaded(viewModel(saved = saved))

        viewModel.onItemSelect(route("r-1"))
        viewModel.onSaveCourse()
        runCurrent()

        assertEquals(SaveCourseState.Saving, viewModel.uiState.value.save)
        assertFalse(viewModel.uiState.value.canSave)

        // 이때 한 번 더 누른다
        viewModel.onSaveCourse()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, saved.savedRoutes.size)
    }

    @Test
    fun `다시 조회하면 저장 결과를 지운다`() = runTest(dispatcher) {
        // 목록이 갈렸으니 사라진 코스에 붙은 안내가 남으면 안 된다
        val viewModel = loaded(viewModel())
        viewModel.onItemSelect(route("r-1"))
        viewModel.onSaveCourse()
        advanceUntilIdle()

        viewModel.refreshNearby()
        advanceUntilIdle()

        assertEquals(SaveCourseState.Idle, viewModel.uiState.value.save)
        assertNull(viewModel.uiState.value.selectedItem)
    }
}

/** 이 테스트는 [내 위치] 를 안 쓴다 — 출발지는 프리셋으로 정한다. */
private object DeniedLocationProvider : LocationProvider {
    override suspend fun current(): LocationResult = LocationResult.PermissionDenied
}

/** 정해 둔 목록만 돌려주는 가짜. 지역별은 이 테스트가 안 본다. */
private class StubCourseRepository(private val items: List<NearbyItem>) : CourseRepository {

    override suspend fun near(
        lat: Double,
        lng: Double,
        targetKm: Double,
        radiusKm: Double,
        size: Int,
    ) = NearbyCourses(items = items, attributions = listOf("© OpenStreetMap contributors"))

    override suspend fun byRegion(region: String?, page: Int, size: Int) = CoursePage()

    override suspend fun regions() = emptyList<com.runninggu.app.data.model.CourseRegion>()
}

/** 무엇을 저장하라고 시켰는지 적어 두는 가짜. */
private class RecordingSavedCourses(
    private val result: SaveCourseResult? = SaveCourseResult(id = 1L, created = true),
    private val error: ApiException? = null,
    /** 주면 이걸 풀어 줄 때까지 응답을 붙든다 — "보내는 중" 을 관찰하려고. */
    private val gate: CompletableDeferred<Unit>? = null,
) : SavedCourseRepository {

    val savedRoutes = mutableListOf<NearbyItem.Route>()

    override suspend fun save(route: NearbyItem.Route): SaveCourseResult? {
        savedRoutes += route
        gate?.await()
        error?.let { throw it }
        return result
    }

    override suspend fun list(page: Int, size: Int) = SavedCoursePage()

    override suspend fun detail(id: Long): SavedCourseDetail = throw NotImplementedError()

    override suspend fun delete(id: Long) = Unit
}
