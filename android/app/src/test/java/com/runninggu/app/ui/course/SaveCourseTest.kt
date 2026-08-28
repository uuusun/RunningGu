package com.runninggu.app.ui.course

import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.model.Difficulty
import com.runninggu.app.data.model.NearbyCourses
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.data.model.SaveCourseResult
import com.runninggu.app.data.model.SavedCourseDetail
import com.runninggu.app.data.repository.CoursePage
import com.runninggu.app.data.local.SessionStore
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
    fun `고르기 전에도 지도에 뜬 코스를 저장할 수 있다`() = runTest(dispatcher) {
        // **저장은 지도를 따라간다.** 조회 직후 지도에는 첫 코스가 그려지는데
        // 그때 [저장] 이 회색이면 "코스가 떠 있는데 저장이 안 되는" 화면이 된다(#166 리뷰).
        val viewModel = loaded(viewModel())

        assertTrue(viewModel.uiState.value.canSave)
        assertEquals("r-1", viewModel.uiState.value.selectedRoute?.routeId)
    }

    @Test
    fun `걷기 스팟을 고르면 저장할 수 없다`() = runTest(dispatcher) {
        // 스팟을 고르면 지도에서 경로선이 사라진다(§4.11-4). 그때도 저장이 눌리면
        // **화면에 없는 코스**가 저장돼 사용자가 무엇을 저장했는지 알 수 없다.
        val viewModel = loaded(viewModel())

        viewModel.onItemSelect(place)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedRoute)
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
        // 고른 것은 **새 목록의 첫 경로** 로 다시 잡힌다 — 이전 선택이 남지 않는다(#190 리뷰)
        assertEquals("r-1", (viewModel.uiState.value.selectedItem as? NearbyItem.Route)?.routeId)
    }

    @Test
    fun `보내는 사이 다른 코스를 고르면 결과가 그쪽에 붙지 않는다`() = runTest(dispatcher) {
        // A 저장 중에 B 를 고르면 `onItemSelect` 가 `save` 를 Idle 로 되돌린다. 그 뒤
        // A 응답이 도착해 "저장했어요" 를 다시 쓰면 **B 아래에 붙는다** — 사용자는
        // 누른 적 없는 코스를 저장한 것으로 읽는다(#166 리뷰).
        val gate = CompletableDeferred<Unit>()
        val viewModel = loaded(viewModel(saved = RecordingSavedCourses(gate = gate)))

        viewModel.onItemSelect(route("r-1"))
        viewModel.onSaveCourse()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.save is SaveCourseState.Saving)

        viewModel.onItemSelect(route("r-2"))
        advanceUntilIdle()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "A 의 결과가 B 아래에 붙었다",
            SaveCourseState.Idle,
            viewModel.uiState.value.save,
        )
    }

    @Test
    fun `다시 조회한 목록에는 이전 저장 결과가 붙지 않는다`() = runTest(dispatcher) {
        // **`routeId` 비교만으로는 못 막는다.** §6-1 이 그걸 "near 응답 안에서만 유효한
        // 불투명 식별자" 로 정의해서, 새 조회가 같은 id 를 재사용할 수 있다 —
        // `FakeCourseRepository` 도 목표 거리가 바뀌어도 `osm:demo-1` 을 다시 쓴다.
        // 그때 A 의 완료 문구가 **새 목록 아래** 다시 붙는다(#166 리뷰).
        val gate = CompletableDeferred<Unit>()
        val viewModel = loaded(viewModel(saved = RecordingSavedCourses(gate = gate)))

        viewModel.onItemSelect(route("r-1"))
        viewModel.onSaveCourse()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.save is SaveCourseState.Saving)

        // 같은 routeId 를 그대로 돌려주는 새 조회
        viewModel.refreshNearby()
        advanceUntilIdle()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "다시 조회한 목록에 이전 결과가 붙었다",
            SaveCourseState.Idle,
            viewModel.uiState.value.save,
        )
    }

    @Test
    fun `고른 코스가 그대로면 결과가 붙는다`() = runTest(dispatcher) {
        // 과하게 버리면 정상 저장도 아무 말이 없어진다
        val gate = CompletableDeferred<Unit>()
        val viewModel = loaded(viewModel(saved = RecordingSavedCourses(gate = gate)))

        viewModel.onItemSelect(route("r-1"))
        viewModel.onSaveCourse()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        val save = viewModel.uiState.value.save
        assertTrue("정상 저장인데 아무 말이 없다: $save", save is SaveCourseState.Done)
    }

    @Test
    fun `보내는 사이 세션이 죽어도 로그인 모달을 띄운다`() = runTest(dispatcher) {
        // 세대 가드가 이 결과를 버리면 **정작 로그인하라는 말을 해야 할 때 아무 말도 못 한다.**
        // 게스트 모달을 살리려고 만든 기능이 가장 필요한 순간에 삼켜지는 셈이다(#166 리뷰).
        val viewModel = loaded(viewModel(saved = ExpiringSavedCourses()))

        viewModel.onItemSelect(route("r-1"))
        viewModel.onSaveCourse()
        advanceUntilIdle()

        assertEquals(SaveCourseState.NeedsLogin, viewModel.uiState.value.save)
    }

    @Test
    fun `결과를 버리더라도 저장 버튼은 풀어 준다`() = runTest(dispatcher) {
        // `Saving` 인 채로 두면 `canSave` 가 계속 false 라 "저장 중…" 이 굳는다.
        // 같은 코스를 다시 눌러도 `onItemSelect` 가 아무것도 안 해서 빠져나올 수 없다.
        val viewModel = loaded(viewModel(saved = SignOutThenSucceed()))

        viewModel.onItemSelect(route("r-1"))
        viewModel.onSaveCourse()
        advanceUntilIdle()

        assertTrue(
            "저장 중인 채로 굳으면 버튼이 영영 안 풀린다: ${viewModel.uiState.value.save}",
            viewModel.uiState.value.save !is SaveCourseState.Saving,
        )
        assertTrue(viewModel.uiState.value.canSave)
    }
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

/**
 * 응답 **전에** 세션이 죽는 가짜. (#166 리뷰)
 *
 * 실제 순서가 이렇다 — `POST /me/courses` 가 `401` 을 받으면 재발급이 먼저 끼어들고,
 * 그것이 만료로 끝나면 `onGiveUp` 이 `signOut()` 으로 세대를 올린다. **그 다음에야**
 * 원래 요청의 `401` 이 화면에 닿는다. 즉 결과가 도착할 때 세대는 이미 달라져 있다.
 */
private class ExpiringSavedCourses : SavedCourseRepository {

    override suspend fun save(route: NearbyItem.Route): SaveCourseResult? {
        SessionStore.signOut(expectedEpoch = SessionStore.sessionEpoch)
        throw ApiException.Http(401, ApiErrorCode.UNAUTHORIZED, null)
    }

    override suspend fun list(page: Int, size: Int) = SavedCoursePage()

    override suspend fun detail(id: Long): SavedCourseDetail = throw NotImplementedError()

    override suspend fun delete(id: Long) = Unit
}

/** 세대는 바뀌었는데 결과는 성공인 가짜. 결과를 **버리는** 쪽 경로를 본다. */
private class SignOutThenSucceed : SavedCourseRepository {

    override suspend fun save(route: NearbyItem.Route): SaveCourseResult {
        SessionStore.signOut(expectedEpoch = SessionStore.sessionEpoch)
        return SaveCourseResult(id = 1L, created = true)
    }

    override suspend fun list(page: Int, size: Int) = SavedCoursePage()

    override suspend fun detail(id: Long): SavedCourseDetail = throw NotImplementedError()

    override suspend fun delete(id: Long) = Unit
}
