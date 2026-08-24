package com.runninggu.app.ui.my

import com.runninggu.app.data.local.AuthTokens
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.model.SavedItinerary
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.GenerateItineraryRequest
import com.runninggu.app.data.repository.ItineraryRepository
import com.runninggu.app.data.repository.SavedItineraryPage
import com.runninggu.app.data.model.ItineraryResult
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
 * 마이 [동선] 세그먼트가 서버를 본다. (SPEC §4.13 · §3-5 · API 명세 §5-4)
 *
 * **예전에는 하드코딩 2건이 떴다.** 사용자가 S7 에서 저장하고 마이에 오면 자기 것이
 * 아닌 동선이 보였다.
 *
 * 그리고 목록만 들고 있어서 **조회 중이거나 실패해도 "저장한 동선이 없어요"** 가 떴다 —
 * 저장 코스에서 #107 이 고친 것과 같은 결함이 여기 남아 있었다.
 */
class SavedItinerariesStateTest {

    private val dispatcher = StandardTestDispatcher()

    private val profile = SessionProfile(
        nickname = "민지",
        email = "minji@example.test",
        loginProvider = LoginProvider.EMAIL,
        marketingAgreed = false,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        SessionStore.resetForTest()
        // 찜 조회가 지연을 남기면 다음 테스트의 Main 이 깨진다 — 즉시 답하게 바꾼다
        com.runninggu.app.ui.favorite.FavoriteStore.resetForTest(EmptyFavorites)
    }

    @After
    fun tearDown() {
        // **세션을 먼저 비운다.** `resetForTest` 가 `_session` 을 바꾸면 ViewModel 의
        // 수집기가 Main 에서 깨어나는데, 그 전에 `resetMain()` 을 하면 디스패처가 없다.
        SessionStore.resetForTest()
        Dispatchers.resetMain()
    }

    private fun itinerary(i: Int, needsRegeneration: Boolean = false, active: Boolean = true) =
        SavedItinerary(
            id = i.toString(),
            title = "부산 2박 3일",
            raceName = "부산 마라톤",
            event = "HALF",
            recoveryLabel = null,
            period = "09.05~09.07",
            placeCount = 8,
            needsRegeneration = needsRegeneration,
            active = active,
        )

    private fun TestScopeViewModel(repository: ItineraryRepository): MyViewModel {
        SessionStore.signIn(profile, AuthTokens("A1", "R1"))
        return MyViewModel(
            savedCourseRepository = EmptyCourses,
            itineraryRepository = repository,
        )
    }

    @Test
    fun `서버 목록을 그대로 보여준다`() = runTest(dispatcher) {
        // 하드코딩 2건이 아니라 서버가 준 것이 떠야 한다
        val viewModel = TestScopeViewModel(StubItineraries(listOf(itinerary(1), itinerary(2))))
        advanceUntilIdle()

        val state = viewModel.uiState.value.itineraries
        assertTrue("서버 목록이 안 왔다: $state", state is SavedItinerariesState.Content)
        assertEquals(2, (state as SavedItinerariesState.Content).itineraries.size)
    }

    @Test
    fun `0건은 빈 상태다`() = runTest(dispatcher) {
        val viewModel = TestScopeViewModel(StubItineraries(emptyList()))
        advanceUntilIdle()

        assertEquals(SavedItinerariesState.Empty, viewModel.uiState.value.itineraries)
    }

    @Test
    fun `실패는 빈 상태가 아니라 오류다`() = runTest(dispatcher) {
        // **여기가 핵심이다.** "저장한 동선이 없어요" 가 뜨면 사용자는 다시 시도해야
        // 할 상황인지 알 수 없다 (§3-5)
        val viewModel = TestScopeViewModel(
            StubItineraries(error = ApiException.Http(500, ApiErrorCode.INTERNAL_SERVER_ERROR, null)),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value.itineraries
        assertTrue("실패가 빈 상태로 접혔다: $state", state is SavedItinerariesState.Error)
    }

    @Test
    fun `비활성 대회의 동선도 목록에 남는다`() = runTest(dispatcher) {
        // 걸러 내면 사용자가 저장한 것이 말없이 사라진다 (§5-4)
        val viewModel = TestScopeViewModel(StubItineraries(listOf(itinerary(1, active = false))))
        advanceUntilIdle()

        val state = viewModel.uiState.value.itineraries as SavedItinerariesState.Content
        assertEquals(1, state.itineraries.size)
        assertEquals(false, state.itineraries.single().active)
    }

    @Test
    fun `게스트는 서버를 부르지 않는다`() = runTest(dispatcher) {
        // 마이 진입 자체가 로그인 필요다(결정-4). 헛 왕복을 만들지 않는다
        val stub = StubItineraries(emptyList())
        MyViewModel(
            savedCourseRepository = EmptyCourses,
            itineraryRepository = stub,
        )
        advanceUntilIdle()

        assertEquals(0, stub.calls)
    }
}

/** 정해 둔 목록이나 오류만 돌려주는 가짜. */
private class StubItineraries(
    private val itineraries: List<SavedItinerary> = emptyList(),
    private val error: Throwable? = null,
) : ItineraryRepository {

    var calls = 0
        private set

    override suspend fun list(page: Int, size: Int): SavedItineraryPage {
        calls++
        error?.let { throw it }
        return SavedItineraryPage(
            itineraries = itineraries,
            hasNext = false,
            totalElements = itineraries.size.toLong(),
        )
    }

    override suspend fun delete(id: Long) = Unit

    override suspend fun generate(request: GenerateItineraryRequest): ItineraryResult =
        throw UnsupportedOperationException("이 테스트는 생성을 부르지 않는다")
}

/** 즉시 빈 목록을 주는 저장 코스 스텁. 지연이 남으면 다음 테스트의 Main 이 깨진다. */
private object EmptyCourses : com.runninggu.app.data.repository.SavedCourseRepository {
    override suspend fun save(route: com.runninggu.app.data.model.NearbyItem.Route) = null
    override suspend fun list(page: Int, size: Int) =
        com.runninggu.app.data.repository.SavedCoursePage()
    override suspend fun detail(id: Long): com.runninggu.app.data.model.SavedCourseDetail =
        throw UnsupportedOperationException("이 테스트는 상세를 부르지 않는다")
    override suspend fun delete(id: Long) = Unit
}

/** 즉시 빈 찜을 주는 스텁. */
private object EmptyFavorites : com.runninggu.app.ui.favorite.FavoriteRepository {
    override suspend fun loadFavoriteIds(): Result<Set<String>> = Result.success(emptySet())
    override suspend fun add(contestId: String): Result<Unit> = Result.success(Unit)
    override suspend fun remove(contestId: String): Result<Unit> = Result.success(Unit)
}
