package com.runninggu.app.ui.my

import com.runninggu.app.data.local.AuthTokens
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.model.Contest
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.ui.favorite.FavoriteStore
import com.runninggu.app.data.repository.FakeFavoriteRepository
import com.runninggu.app.data.repository.FavoritePage
import com.runninggu.app.data.repository.FavoriteRepository
import com.runninggu.app.data.repository.SavedCoursePage
import com.runninggu.app.data.repository.SavedCourseRepository
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.data.model.SaveCourseResult
import com.runninggu.app.data.model.SavedCourseDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S10 [찜한 대회] 가 **서버 목록**을 네 상태로 그리는가. (이슈 #163 · API 명세 §7-C · §3-5)
 *
 * 예전에는 `SampleData.allRaces` 를 찜 id 로 걸러서 그렸다. 그래서
 *
 * - 조회 중·실패해도 "찜한 대회가 없어요" 가 떴고 (§3-5 위반)
 * - **샘플에 없는 대회는 찜해도 목록에 안 나왔다**
 *
 * 지금은 `GET /me/favorites` 가 목록을 주고, 하트만 `FavoriteStore` 를 본다.
 */
class FavoriteRacesStateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // **명시적으로 물린다.** 안 하면 FavoriteStore 가 ServiceLocator 의 서버 구현을
        // 잡아 실제 HTTP 를 시도하고, 앞선 테스트가 무엇을 남겼는지에 결과가 갈린다.
        FavoriteStore.resetForTest(FakeFavoriteRepository)
    }

    @After
    fun tearDown() {
        SessionStore.signOut()
        FavoriteStore.resetForTest(FakeFavoriteRepository)
        Dispatchers.resetMain()
    }

    private fun signIn() = SessionStore.signIn(
        SessionProfile("러너", "runner@test.com", LoginProvider.EMAIL),
        AuthTokens("A1", "R1"),
    )

    private fun TestScope.viewModel(favorites: FavoriteRepository): MyViewModel =
        MyViewModel(savedCourseRepository = StubSavedCourseRepository, favoriteRepository = favorites)
            .also { advanceUntilIdle() }

    @Test
    fun `로그인하면 서버 찜 목록을 그린다`() = runTest(dispatcher) {
        signIn()
        val repository = FakeFavorites(
            listOf(FavoritePage(listOf(contest(1, "서울마라톤")), hasNext = false, totalElements = 1)),
        )

        val state = viewModel(repository).uiState.value.favorites

        assertTrue(state is FavoriteRacesState.Content)
        assertEquals("서울마라톤", (state as FavoriteRacesState.Content).races.first().name)
    }

    @Test
    fun `0건이면 빈 상태다`() = runTest(dispatcher) {
        signIn()
        val repository = FakeFavorites(
            listOf(FavoritePage(emptyList(), hasNext = false, totalElements = 0)),
        )

        assertEquals(FavoriteRacesState.Empty, viewModel(repository).uiState.value.favorites)
    }

    @Test
    fun `못 불러오면 빈 상태가 아니라 오류다`() = runTest(dispatcher) {
        // 이 자리가 예전 구현의 결함이다 — 실패해도 "찜한 대회가 없어요" 가 떴다.
        signIn()
        val repository = FakeFavorites(emptyList(), failure = ApiException.Network(IOException()))

        val state = viewModel(repository).uiState.value.favorites

        assertTrue(state is FavoriteRacesState.Error)
        assertEquals("찜한 대회를 불러오지 못했어요.", (state as FavoriteRacesState.Error).message)
    }

    @Test
    fun `비활성 대회도 목록에 남는다`() = runTest(dispatcher) {
        // 공개 목록과 달리 찜은 유지하는 게 계약이다 (§7-C 🔒 · 결정-46).
        signIn()
        val repository = FakeFavorites(
            listOf(
                FavoritePage(
                    listOf(contest(2, "사라진대회", active = false)),
                    hasNext = false,
                    totalElements = 1,
                ),
            ),
        )

        val state = viewModel(repository).uiState.value.favorites as FavoriteRacesState.Content

        assertEquals(1, state.races.size)
        assertFalse(state.races.first().active)
    }

    @Test
    fun `더 보기가 다음 장을 이어 붙인다`() = runTest(dispatcher) {
        signIn()
        val repository = FakeFavorites(
            listOf(
                FavoritePage(listOf(contest(1, "첫장")), hasNext = true, totalElements = 2),
                FavoritePage(listOf(contest(2, "둘째장")), hasNext = false, totalElements = 2),
            ),
        )
        val viewModel = viewModel(repository)

        viewModel.loadMoreFavorites()
        advanceUntilIdle()

        val state = viewModel.uiState.value.favorites as FavoriteRacesState.Content
        assertEquals(listOf("첫장", "둘째장"), state.races.map { it.name })
        assertFalse(state.hasNext)
    }

    @Test
    fun `더 보기가 실패해도 받은 목록은 지우지 않는다`() = runTest(dispatcher) {
        signIn()
        val repository = FakeFavorites(
            listOf(FavoritePage(listOf(contest(1, "첫장")), hasNext = true, totalElements = 2)),
            failFrom = 1,
        )
        val viewModel = viewModel(repository)

        viewModel.loadMoreFavorites()
        advanceUntilIdle()

        val state = viewModel.uiState.value.favorites as FavoriteRacesState.Content
        assertEquals(listOf("첫장"), state.races.map { it.name })
        assertEquals("더 불러오지 못했어요.", state.moreMessage)
        assertFalse(state.loadingMore)
    }

    @Test
    fun `게스트는 서버를 부르지 않는다`() = runTest(dispatcher) {
        // 마이 진입 자체가 로그인 필요다 (결정-4).
        val repository = FakeFavorites(emptyList())

        val viewModel = viewModel(repository)

        assertEquals(0, repository.calls)
        assertEquals(FavoriteRacesState.Empty, viewModel.uiState.value.favorites)
    }
}

// ── 가짜 ────────────────────────────────────────────────────────

private class FakeFavorites(
    private val pages: List<FavoritePage>,
    private val failure: ApiException? = null,
    private val failFrom: Int = -1,
) : FavoriteRepository {
    var calls = 0
        private set

    override suspend fun list(page: Int, size: Int): FavoritePage {
        calls++
        if (failure != null) throw failure
        if (failFrom >= 0 && page >= failFrom) throw ApiException.Network(IOException())
        return pages.getOrElse(page) { FavoritePage(emptyList(), hasNext = false, totalElements = 0) }
    }

    override suspend fun loadFavoriteIds(): Result<Set<String>> = Result.success(emptySet())
    override suspend fun add(contestId: String): Result<Unit> = Result.success(Unit)
    override suspend fun remove(contestId: String): Result<Unit> = Result.success(Unit)
}

/** 이 파일은 저장 코스를 보지 않는다. */
private object StubSavedCourseRepository : SavedCourseRepository {
    override suspend fun save(route: NearbyItem.Route): SaveCourseResult? = null
    override suspend fun list(page: Int, size: Int): SavedCoursePage =
        SavedCoursePage(courses = emptyList(), hasNext = false, totalElements = 0)

    override suspend fun detail(id: Long): SavedCourseDetail =
        throw UnsupportedOperationException("쓰지 않는다")

    override suspend fun delete(id: Long) = Unit
}

private fun contest(id: Long, name: String, active: Boolean = true) = Contest(
    id = id.toString(),
    serverId = id,
    active = active,
    name = name,
    region = "서울",
    venue = "광화문",
    date = LocalDate.of(2026, 10, 11),
    startTime = null,
    eventTypes = emptyList(),
    regStart = null,
    regEnd = null,
    regStatusFallback = null,
    organizer = null,
    officialUrl = null,
    detailUrl = null,
    imageUrl = null,
    lat = null,
    lng = null,
    category = null,
    checked = null,
    sources = emptyList(),
)
