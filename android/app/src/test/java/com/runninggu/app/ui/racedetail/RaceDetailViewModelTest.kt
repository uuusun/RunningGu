package com.runninggu.app.ui.racedetail

import com.runninggu.app.ui.common.DataOrigin
import com.runninggu.app.ui.common.OFFLINE_FAVORITE_BLOCKED
import com.runninggu.app.ui.favorite.FavoriteStore
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.repository.FavoritePage
import com.runninggu.app.data.repository.FavoriteRepository
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import com.runninggu.app.data.repository.ContestDetailResult
import com.runninggu.app.data.model.Contest
import com.runninggu.app.data.model.NearbyFestival
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.ClosingSoon
import com.runninggu.app.data.repository.ClosingSoonResult
import com.runninggu.app.data.repository.ContestFilter
import com.runninggu.app.data.repository.ContestPage
import com.runninggu.app.data.repository.ContestRepository
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.RegistrationStatus
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
import java.time.LocalDate

/**
 * S3 대회 상세가 서버를 본다. (SPEC §4.6 · API 명세 §3-4 · §3-5 · AP-14)
 *
 * 이 파일이 지키는 것은 **"재시도가 소용있는가"** 하나다. 세 가지가 서로 다르다.
 *
 * | 상황 | 상태 | [다시 시도] |
 * |---|---|---|
 * | `404 CONTEST_NOT_FOUND` | `NOT_FOUND` | 없음 — 다시 눌러도 안 생긴다 |
 * | 네트워크·`502` | `ERROR` | 있음 |
 * | 축제 `409 CONTEST_LOCATION_UNAVAILABLE` | `LOCATION_UNAVAILABLE` | **없음** — 좌표는 안 생긴다 |
 *
 * 뭉뚱그리면 헛도는 버튼이 생기거나, 있는 대회를 "없다" 고 적게 된다.
 */
class RaceDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    /**
     * ViewModel 은 만들면 **끝나지 않는 구독**을 하나 연다 — `FavoriteStore.favoriteIds` 다.
     * 끊지 않으면 `resetMain()` 뒤에도 살아남아 나중에 다른 테스트가 찜을 바꿀 때
     * 사라진 Main 으로 재개되면서 엉뚱한 테스트를 깨뜨린다(`CalendarViewModelTest` 와 같다).
     */
    private val viewModels = mutableListOf<RaceDetailViewModel>()

    /**
     * **뒷정리가 이 파일만의 일이 아니다.** [FavoriteStore] 는 싱글턴이라 여기서 갈아 끼운
     * 스텁과 세션이 다음 테스트 클래스로 넘어간다. 실제로 정리를 빼고 돌렸더니 이 파일은
     * 다 통과하는데 `FavoriteStoreTest` 와 `FavoriteRacesStateTest` 가 대신 깨졌다 —
     * 죽은 `Dispatchers.Main` 으로 재개되는 `DispatchException` 과 꺼진 하트다.
     *
     * 순서가 있다. **구독을 먼저 끊고**(끊기 전에 `resetMain()` 하면 그 구독이 죽은 Main 을
     * 잡는다), 세션을 내리고, 저장소를 빈 것으로 되돌린 뒤 마지막에 Main 을 푼다.
     */
    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        SessionStore.signOut()
        FavoriteStore.resetForTest(CountingFavoriteRepository())
        Dispatchers.resetMain()
    }

    /**
     * 찜은 **로그인해야 서버로 나간다**([FavoriteStore.toggle] 첫 줄). 로그인 없이 두면
     * `LoginRequired` 로 빠져서 **캐시 잠금이 아니라 로그인 때문에** 저장소가 안 불린다 —
     * 그러면 통과해도 아무것도 증명하지 못한다.
     */
    private fun signIn() {
        SessionStore.signIn(
            SessionProfile(
                nickname = "테스터",
                email = "tester@example.com",
                loginProvider = LoginProvider.EMAIL,
            ),
        )
    }

    @Test
    fun `본문과 축제를 이어서 받는다`() = runTest(dispatcher) {
        val viewModel = newViewModel(FakeContestRepository(festivals = listOf(festival())))

        viewModel.start("7")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(RaceDetailUiState.Phase.LOADED, state.phase)
        assertEquals(7L, state.race?.serverId)
        assertEquals(RaceDetailUiState.FestivalPhase.LOADED, state.festivalPhase)
        assertEquals(1, state.festivals.size)
    }

    @Test
    fun `404 는 재시도 없는 상태다`() = runTest(dispatcher) {
        val viewModel = newViewModel(
            FakeContestRepository(detailFailure = http(ApiErrorCode.NOT_FOUND)),
        )

        viewModel.start("7")
        advanceUntilIdle()

        assertEquals(RaceDetailUiState.Phase.NOT_FOUND, viewModel.uiState.value.phase)
    }

    @Test
    fun `네트워크 오류는 재시도 있는 상태다`() = runTest(dispatcher) {
        val viewModel = newViewModel(
            FakeContestRepository(detailFailure = ApiException.Network(java.io.IOException("끊김"))),
        )

        viewModel.start("7")
        advanceUntilIdle()

        // NOT_FOUND 로 떨어뜨리면 있는 대회를 "없다" 고 적게 된다
        assertEquals(RaceDetailUiState.Phase.ERROR, viewModel.uiState.value.phase)
    }

    @Test
    fun `축제 409 는 재시도를 주지 않는 별도 상태다`() = runTest(dispatcher) {
        val viewModel = newViewModel(
            FakeContestRepository(festivalFailure = http(ApiErrorCode.CONTEST_LOCATION_UNAVAILABLE)),
        )

        viewModel.start("7")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(RaceDetailUiState.FestivalPhase.LOCATION_UNAVAILABLE, state.festivalPhase)
        // 본문은 그대로다 — 축제 실패가 대회 정보를 가리면 안 된다
        assertEquals(RaceDetailUiState.Phase.LOADED, state.phase)
    }

    @Test
    fun `축제가 죽어도 본문은 남는다`() = runTest(dispatcher) {
        val viewModel = newViewModel(
            FakeContestRepository(festivalFailure = ApiException.Network(java.io.IOException("끊김"))),
        )

        viewModel.start("7")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(RaceDetailUiState.FestivalPhase.ERROR, state.festivalPhase)
        assertEquals(RaceDetailUiState.Phase.LOADED, state.phase)
    }

    @Test
    fun `canonical id 가 없는 대회는 서버를 부르지 않는다`() = runTest(dispatcher) {
        val repository = FakeContestRepository()

        // 번들 항목의 id 다. 숫자로 바꿔 보내면 엉뚱한 대회를 묻게 된다.
        val viewModel = newViewModel(repository)
        viewModel.start("roadrun-41543")
        advanceUntilIdle()

        assertEquals(RaceDetailUiState.Phase.NOT_FOUND, viewModel.uiState.value.phase)
        assertNull(repository.lastDetailId)
    }

    @Test
    fun `비활성 대회는 축제를 부르지 않는다`() = runTest(dispatcher) {
        // 원천이 사라진 대회의 주변 축제를 보여주면 아직 열리는 대회처럼 읽힌다 (결정-46).
        val repository = FakeContestRepository(active = false)

        val viewModel = newViewModel(repository)
        viewModel.start("7")
        advanceUntilIdle()

        assertNull(repository.lastFestivalsId)
    }

    private fun ApiErrorCode.status(): Int =
        if (this == ApiErrorCode.NOT_FOUND) 404 else 409

    private fun http(code: ApiErrorCode) =
        ApiException.Http(status = code.status(), code = code, problem = null)

    private fun festival() = NearbyFestival(
        contentId = "fest-1",
        name = "세종 물빛축제",
        startDate = LocalDate.of(2026, 8, 20),
        endDate = LocalDate.of(2026, 8, 24),
        distanceKm = 3.2,
        imageUrl = null,
        address = "세종특별자치시",
    )

    @Test
    fun `캐시로 되살린 상세는 언제 것인지를 화면까지 들고 온다`() = runTest(dispatcher) {
        // 대회 상세는 접수 마감이 걸려 있다. 캐시된 값을 지금 값처럼 그리면 이미 끝난
        // 접수를 열려 있다고 보여주게 된다 (#307)
        val viewModel = newViewModel(FakeContestRepository(detailCachedAt = CACHED_AT))

        viewModel.start("7")
        advanceUntilIdle()

        assertEquals(DataOrigin.LocalCache(CACHED_AT), viewModel.uiState.value.origin)
        assertEquals(CACHED_AT, viewModel.uiState.value.cachedAt)
    }

    @Test
    fun `서버에서 막 받은 상세에는 출처 표시가 없다`() = runTest(dispatcher) {
        val viewModel = newViewModel(FakeContestRepository())

        viewModel.start("7")
        advanceUntilIdle()

        assertEquals(DataOrigin.Server, viewModel.uiState.value.origin)
        assertNull(viewModel.uiState.value.cachedAt)
    }

    /**
     * **캐시로 그린 상세에서는 찜 요청이 서버로 안 나간다.** (매핑표 공통 오프라인 읽기 · #307)
     *
     * 앱바 하트를 `enabled = false` 로 잠그지만 **여기서 보는 것은 "저장소가 안 불린다"** 이지
     * "버튼이 회색이다" 가 아니다. 잠금은 그리는 쪽 사정이라 다음에 누가 `enabled` 를 떼면
     * 요청이 조용히 나간다(#314 리뷰).
     *
     * 캘린더는 [CalendarViewModelTest] 가 같은 것을 지킨다. **두 화면의 로직이 같아서 더
     * 필요하다** — 한쪽만 지키면 상세를 건드렸을 때 캘린더만 빨간불이 나고 상세는 조용히
     * 통과한다(#314 리뷰 후속).
     */
    @Test
    fun `캐시로 그린 상세에서는 찜 요청이 서버로 안 나간다`() = runTest(dispatcher) {
        val favorites = CountingFavoriteRepository()
        FavoriteStore.resetForTest(favorites)
        signIn()
        val viewModel = newViewModel(FakeContestRepository(detailCachedAt = CACHED_AT))

        viewModel.start("7")
        advanceUntilIdle()
        assertFalse("캐시 상세인데 찜이 열려 있다", viewModel.uiState.value.canFavorite)

        viewModel.onFavoriteToggle()
        advanceUntilIdle()

        assertEquals("캐시인데 서버로 나갔다", 0, favorites.writes)
        assertEquals(OFFLINE_FAVORITE_BLOCKED, viewModel.message.value)
    }

    @Test
    fun `서버에서 받은 상세에서는 찜이 그대로 나간다`() = runTest(dispatcher) {
        val favorites = CountingFavoriteRepository()
        FavoriteStore.resetForTest(favorites)
        signIn()
        val viewModel = newViewModel(FakeContestRepository())

        viewModel.start("7")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canFavorite)

        viewModel.onFavoriteToggle()
        advanceUntilIdle()

        assertEquals("서버 상세인데 안 나갔다", 1, favorites.writes)
    }

    /** 캐시로 그린 뒤 재조회가 성공하면 잠긴 채 남지 않는다. (#307) */
    @Test
    fun `온라인으로 다시 받으면 상세 찜이 열린다`() = runTest(dispatcher) {
        val favorites = CountingFavoriteRepository()
        FavoriteStore.resetForTest(favorites)
        signIn()
        val repository = FakeContestRepository(detailCachedAt = CACHED_AT)
        val viewModel = newViewModel(repository)

        viewModel.start("7")
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.canFavorite)

        repository.detailCachedAt = null
        viewModel.load()
        advanceUntilIdle()

        assertTrue("재조회가 성공했는데 잠긴 채다", viewModel.uiState.value.canFavorite)
        viewModel.onFavoriteToggle()
        advanceUntilIdle()
        assertEquals(1, favorites.writes)
    }

    /**
     * **이 클래스의 ViewModel 은 전부 이걸로 만든다.** 하나라도 직접 생성하면 그 인스턴스의
     * `FavoriteStore.favoriteIds` 수집이 [tearDown] 을 그냥 통과해, 뒤따르는
     * `resetForTest`·`resetMain` 과 겹치면서 **다음 클래스로 누수가 넘어간다**(#320 리뷰 · 선경님).
     */
    private fun newViewModel(repository: FakeContestRepository) =
        RaceDetailViewModel(repository).also { viewModels += it }
}

/** 고정 시각. 되살린 상세가 이 값을 화면까지 들고 오는지 본다 (#307). */
private val CACHED_AT: java.time.Instant = java.time.Instant.parse("2026-09-06T12:00:00Z")

private class FakeContestRepository(
    private val festivals: List<NearbyFestival> = emptyList(),
    private val detailFailure: ApiException? = null,
    private val festivalFailure: ApiException? = null,
    private val active: Boolean = true,
    /**
     * 캐시로 되살린 상세면 저장 시각. null 이면 서버에서 막 받은 것이다 (#307).
     *
     * `var` 인 이유 — 캐시로 그린 뒤 **온라인 재조회가 성공하는** 경우를 한 인스턴스로
     * 이어서 봐야 한다. 새 저장소로 갈아 끼우면 재조회가 아니라 다른 화면이 된다.
     */
    var detailCachedAt: java.time.Instant? = null,
) : ContestRepository {

    var lastDetailId: Long? = null
        private set
    var lastFestivalsId: Long? = null
        private set

    override suspend fun detail(id: Long): ContestDetailResult {
        detailFailure?.let { throw it }
        lastDetailId = id
        return ContestDetailResult(contest(id, active), detailCachedAt)
    }

    override suspend fun festivals(id: Long): List<NearbyFestival> {
        festivalFailure?.let { throw it }
        lastFestivalsId = id
        return festivals
    }

    override suspend fun list(filter: ContestFilter, cursor: String?): ContestPage =
        throw UnsupportedOperationException("S3 는 부르지 않는다")

    override suspend fun dailyCounts(
        year: Int,
        month: Int,
        filter: ContestFilter,
    ): Map<LocalDate, Int> = throw UnsupportedOperationException("S3 는 부르지 않는다")

    override suspend fun closingSoon(limit: Int): ClosingSoonResult =
        throw UnsupportedOperationException("S3 는 부르지 않는다")

    private fun contest(id: Long, active: Boolean) = Contest(
        id = id.toString(),
        serverId = id,
        name = "세종 호수공원 마라톤",
        region = "세종",
        venue = "세종 호수공원",
        date = LocalDate.of(2026, 9, 12),
        startTime = null,
        eventTypes = listOf(EventType.HALF),
        regStart = null,
        regEnd = null,
        regStatusFallback = RegistrationStatus.OPEN,
        organizer = "세종 육상연맹",
        officialUrl = null,
        detailUrl = null,
        imageUrl = null,
        lat = null,
        lng = null,
        category = null,
        checked = null,
        active = active,
        sources = listOf("MARATHON_GO"),
    )
}

/** 쓰기가 몇 번 나갔는지만 센다. (#307 오프라인 쓰기 잠금) */
private class CountingFavoriteRepository : FavoriteRepository {
    var writes = 0
        private set

    override suspend fun loadFavoriteIds(): Result<Set<String>> = Result.success(emptySet())
    override suspend fun list(page: Int, size: Int): FavoritePage =
        FavoritePage(contests = emptyList(), hasNext = false, totalElements = 0)
    override suspend fun add(contestId: String): Result<Unit> {
        writes++
        return Result.success(Unit)
    }
    override suspend fun remove(contestId: String): Result<Unit> {
        writes++
        return Result.success(Unit)
    }
}
