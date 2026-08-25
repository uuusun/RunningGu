package com.runninggu.app.ui.my

import com.runninggu.app.data.local.AuthTokens
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.model.SavedItinerary
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.FakeFavoriteRepository
import com.runninggu.app.data.repository.GenerateItineraryRequest
import com.runninggu.app.data.repository.ItineraryRepository
import com.runninggu.app.data.repository.SavedItineraryPage
import com.runninggu.app.data.model.ItineraryResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

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
        com.runninggu.app.ui.favorite.FavoriteStore.resetForTest(FakeFavoriteRepository)
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
            // 기본값은 ServiceLocator 라 실제 서버를 부른다 — 이 테스트는 동선만 본다
            favoriteRepository = FakeFavoriteRepository,
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
    fun `더 보기를 누르면 받는 동안 버튼이 잠긴다`() = runTest(dispatcher) {
        // 눌린 것이 화면에 안 보이면 사용자는 안 눌렸다고 여기고 또 누른다 (§3-5 · #181 리뷰).
        // 요청을 묵살하는 것과 "받는 중" 이라고 말하는 것은 다르다
        val repository = PagedItineraries()
        val viewModel = TestScopeViewModel(repository)
        advanceUntilIdle()

        repository.gate = CompletableDeferred()
        viewModel.loadMoreItineraries()
        advanceUntilIdle()

        val loading = viewModel.uiState.value.itineraries as SavedItinerariesState.Content
        assertTrue("받는 중이라고 말하지 않는다", loading.loadingMore)
        assertFalse("받는 중인데 버튼이 열려 있다", loading.canLoadMore)
        assertEquals("받는 동안 목록이 흔들렸다", 20, loading.itineraries.size)

        repository.gate?.complete(Unit)
        advanceUntilIdle()

        val done = viewModel.uiState.value.itineraries as SavedItinerariesState.Content
        assertFalse(done.loadingMore)
        assertEquals(40, done.itineraries.size)
        assertTrue("아직 7건 남았는데 버튼이 닫혔다", done.canLoadMore)
    }

    @Test
    fun `받는 중에 또 눌러도 요청은 한 번만 간다`() = runTest(dispatcher) {
        // 버튼을 잠그는 것과 별개로 상태도 스스로를 막는다 — 화면이 잠깐 어긋나도 새지 않는다
        val repository = PagedItineraries()
        val viewModel = TestScopeViewModel(repository)
        advanceUntilIdle()

        repository.gate = CompletableDeferred()
        viewModel.loadMoreItineraries()
        advanceUntilIdle()
        viewModel.loadMoreItineraries()
        advanceUntilIdle()
        repository.gate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(0, 1), repository.requestedPages)
    }

    @Test
    fun `다음 장을 못 받아도 목록은 남고 다시 누를 수 있다`() = runTest(dispatcher) {
        // 보이던 20건이 사라지면 안 된다. 그리고 재시도가 **그 자리에서** 돼야 한다 —
        // 스낵바만 띄우고 버튼이 잠긴 채면 사용자는 화면을 떠났다 와야 한다
        val repository = PagedItineraries()
        val viewModel = TestScopeViewModel(repository)
        advanceUntilIdle()

        repository.failOnce = true
        viewModel.loadMoreItineraries()
        advanceUntilIdle()

        val failed = viewModel.uiState.value.itineraries as SavedItinerariesState.Content
        assertEquals("실패에 목록이 날아갔다", 20, failed.itineraries.size)
        assertEquals("더 불러오지 못했어요.", failed.moreMessage)
        assertFalse(failed.loadingMore)
        assertTrue("재시도할 수 없다", failed.canLoadMore)

        viewModel.loadMoreItineraries()
        advanceUntilIdle()

        val retried = viewModel.uiState.value.itineraries as SavedItinerariesState.Content
        assertEquals(40, retried.itineraries.size)
        assertEquals("성공했는데 오류 문구가 남았다", null, retried.moreMessage)
    }

    @Test
    fun `다음 장을 받는 사이에 지운 동선은 되살아나지 않는다`() = runTest(dispatcher) {
        // 요청 전에 잡아 둔 목록을 응답이 통째 덮으면 지운 카드가 사라졌다 다시 나타난다.
        // 그 응답은 **삭제 전 offset 으로 뜬 것**이라 이어 붙여도 안 되므로 버린다(#181 리뷰).
        val repository = PagedItineraries()
        val viewModel = TestScopeViewModel(repository)
        advanceUntilIdle()

        repository.gate = CompletableDeferred()
        viewModel.loadMoreItineraries()
        advanceUntilIdle()

        viewModel.onDeleteItinerary("3")
        advanceUntilIdle()

        // 붙들려 있던 다음 장이 이제 도착한다 — 이미 버려진 요청이다
        repository.gate?.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value.itineraries as SavedItinerariesState.Content
        val shown = state.itineraries.map { it.id }
        assertFalse("지운 카드가 되살아났다", "3" in shown)
        // 보고 있던 범위(1장)를 다시 받아 서버와 맞춘 상태여야 한다
        assertEquals(repository.storedIds.take(shown.size), shown)
        assertEquals("버린 다음 장이 그대로 붙었다", 20, shown.size)
        assertFalse("버튼이 잠긴 채 남았다", state.loadingMore)
    }

    @Test
    fun `삭제 뒤 다음 장을 받아도 경계 항목이 빠지지 않는다`() = runTest(dispatcher) {
        // **`GET /api/itineraries` 는 offset Pageable 이다**(§0-4). 앞 장에서 하나를 지우면
        // 뒤 항목이 전부 한 칸씩 당겨지므로, 지우기 전 기준으로 다음 장을 받으면 경계에
        // 있던 항목 하나가 **조용히 건너뛰어진다** — 전체 새로고침 전까지 안 보인다(#181 리뷰).
        val repository = PagedItineraries()
        val viewModel = TestScopeViewModel(repository)
        advanceUntilIdle()

        viewModel.onDeleteItinerary("3")
        advanceUntilIdle()

        viewModel.loadMoreItineraries()
        advanceUntilIdle()

        val shown = (viewModel.uiState.value.itineraries as SavedItinerariesState.Content)
            .itineraries.map { it.id }
        // 화면 목록은 서버 순서의 앞부분과 **정확히** 같아야 한다. 빠진 id 가 있으면 여기서 갈린다.
        assertEquals(repository.storedIds.take(shown.size), shown)
    }

    @Test
    fun `더 보기 중 삭제 후 재조회가 실패해도 버튼 잠금은 풀린다`() = runTest(dispatcher) {
        // 버리는 load-more 가 켜 둔 `loadingMore` 가 그대로 남으면 `canLoadMore` 가 false 라,
        // 사용자는 화면을 나갔다 오기 전까지 다음 장을 다시 받을 수 없다(#181 리뷰).
        val repository = PagedItineraries()
        val viewModel = TestScopeViewModel(repository)
        advanceUntilIdle()

        repository.gate = CompletableDeferred()
        viewModel.loadMoreItineraries()
        advanceUntilIdle()

        repository.failPageZeroOnce = true // 삭제 뒤 범위 재조회가 실패한다
        viewModel.onDeleteItinerary("3")
        advanceUntilIdle()

        val state = viewModel.uiState.value.itineraries as SavedItinerariesState.Content
        assertFalse("버튼이 잠긴 채 남았다", state.loadingMore)
        assertTrue("다시 누를 수 없다", state.canLoadMore)
        // 재조회는 실패했어도 지운 것은 빠진 채로 보이던 목록이 남아야 한다
        assertFalse("지운 카드가 남았다", state.itineraries.any { it.id == "3" })
        assertEquals(19, state.itineraries.size)
    }

    @Test
    fun `연속으로 지워도 먼저 뜬 재조회가 나중에 덮지 않는다`() = runTest(dispatcher) {
        // 삭제마다 범위 재조회가 뜨는데, 먼저 뜬 조회는 **그 삭제가 반영되기 전 목록**을
        // 들고 온다. 늦게 도착해 적용되면 이미 지운 카드가 다시 뜬다(#181 리뷰).
        val repository = PagedItineraries()
        val viewModel = TestScopeViewModel(repository)
        advanceUntilIdle()

        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        repository.pageZeroGates += first
        repository.pageZeroGates += second

        viewModel.onDeleteItinerary("3") // 재조회 A — 이 시점 목록에는 "5" 가 아직 있다
        advanceUntilIdle()
        viewModel.onDeleteItinerary("5") // 재조회 B
        advanceUntilIdle()

        // 나중 것부터 도착시킨다. A 가 뒤늦게 와도 화면을 덮으면 안 된다.
        second.complete(Unit)
        advanceUntilIdle()
        first.complete(Unit)
        advanceUntilIdle()

        val shown = (viewModel.uiState.value.itineraries as SavedItinerariesState.Content)
            .itineraries.map { it.id }
        assertFalse("지운 카드가 되살아났다", "5" in shown)
        assertEquals(repository.storedIds.take(shown.size), shown)
    }

    @Test
    fun `게스트는 서버를 부르지 않는다`() = runTest(dispatcher) {
        // 마이 진입 자체가 로그인 필요다(결정-4). 헛 왕복을 만들지 않는다
        val stub = StubItineraries(emptyList())
        MyViewModel(
            savedCourseRepository = EmptyCourses,
            itineraryRepository = stub,
            favoriteRepository = FakeFavoriteRepository,
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

/**
 * 47건을 20건씩 주는 가짜. [gate] 로 다음 장을 잡아 두고 "받는 중" 상태를 들여다본다.
 *
 * 첫 장은 곧바로 답한다 — 화면이 열리는 것까지 문에 걸리면 준비 자체가 안 된다.
 */
private class PagedItineraries(
    private val pageSize: Int = 20,
    total: Int = 47,
) : ItineraryRepository {

    /**
     * 서버가 들고 있는 목록. **[delete] 가 실제로 줄인다.**
     *
     * no-op 으로 두면 offset 이 밀리는 것을 재현하지 못한다 — 삭제 뒤 다음 장을 받으면
     * 경계 항목 하나가 조용히 빠지는데, 그게 안 보인다(#181 리뷰).
     */
    private val stored = (0 until total).map { it.toString() }.toMutableList()

    /** 지금 서버에 남아 있는 순서. 화면 목록이 이 앞부분과 정확히 같아야 한다. */
    val storedIds: List<String> get() = stored.toList()

    /** 완료시킬 때까지 다음 장을 붙들어 둔다. null 이면 곧바로 답한다. */
    var gate: CompletableDeferred<Unit>? = null

    /** 다음 장 조회를 **한 번만** 실패시킨다. 재시도는 성공한다. */
    var failOnce = false

    /** 첫 장 조회를 **한 번만** 실패시킨다. 삭제 뒤 범위 재조회가 깨지는 상황을 만든다. */
    var failPageZeroOnce = false

    /**
     * 첫 장 조회를 순서대로 붙들어 둔다. 재조회 둘을 **겹치게** 만들려고 쓴다.
     *
     * 응답은 **부를 때의 목록**으로 만든다 — 먼저 뜬 조회가 늦게 도착하면 그때는 이미
     * 지운 항목이 들어 있어야 재현이 된다(#181 리뷰).
     */
    val pageZeroGates = ArrayDeque<CompletableDeferred<Unit>>()

    /** 요청이 두 번 가지 않았는지 보려고 순서대로 쌓는다. */
    val requestedPages = mutableListOf<Int>()

    override suspend fun list(page: Int, size: Int): SavedItineraryPage {
        requestedPages += page
        if (page > 0) {
            gate?.await()
            if (failOnce) {
                failOnce = false
                throw ApiException.Network(IOException("끊김"))
            }
        }
        if (page == 0 && failPageZeroOnce) {
            failPageZeroOnce = false
            throw ApiException.Network(IOException("끊김"))
        }
        // **부르는 시점**의 목록을 찍어 둔다. 문에 걸린 사이 삭제가 지나가도 이 응답은
        // 그때의 목록 그대로다 — 실제 서버 응답이 그렇다.
        val snapshot = stored.toList()
        if (page == 0) pageZeroGates.removeFirstOrNull()?.await()
        val start = page * pageSize
        val ids = snapshot.drop(start).take(pageSize)
        return SavedItineraryPage(
            itineraries = ids.map { itinerary(it) },
            hasNext = start + ids.size < snapshot.size,
            totalElements = snapshot.size.toLong(),
        )
    }

    private fun itinerary(id: String) = SavedItinerary(
        id = id,
        title = "부산 2박 3일",
        raceName = "부산 마라톤",
        event = "HALF",
        recoveryLabel = null,
        period = "09.05~09.07",
        placeCount = 8,
        needsRegeneration = false,
        active = true,
    )

    override suspend fun delete(id: Long) {
        stored.remove(id.toString())
    }

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
