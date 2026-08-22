package com.runninggu.app.ui.racedetail

import com.runninggu.app.data.model.Contest
import com.runninggu.app.data.model.NearbyFestival
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.ClosingSoon
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
import org.junit.Assert.assertNull
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

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `본문과 축제를 이어서 받는다`() = runTest(dispatcher) {
        val viewModel = RaceDetailViewModel(FakeContestRepository(festivals = listOf(festival())))

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
        val viewModel = RaceDetailViewModel(
            FakeContestRepository(detailFailure = http(ApiErrorCode.NOT_FOUND)),
        )

        viewModel.start("7")
        advanceUntilIdle()

        assertEquals(RaceDetailUiState.Phase.NOT_FOUND, viewModel.uiState.value.phase)
    }

    @Test
    fun `네트워크 오류는 재시도 있는 상태다`() = runTest(dispatcher) {
        val viewModel = RaceDetailViewModel(
            FakeContestRepository(detailFailure = ApiException.Network(java.io.IOException("끊김"))),
        )

        viewModel.start("7")
        advanceUntilIdle()

        // NOT_FOUND 로 떨어뜨리면 있는 대회를 "없다" 고 적게 된다
        assertEquals(RaceDetailUiState.Phase.ERROR, viewModel.uiState.value.phase)
    }

    @Test
    fun `축제 409 는 재시도를 주지 않는 별도 상태다`() = runTest(dispatcher) {
        val viewModel = RaceDetailViewModel(
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
        val viewModel = RaceDetailViewModel(
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
        val viewModel = RaceDetailViewModel(repository)
        viewModel.start("roadrun-41543")
        advanceUntilIdle()

        assertEquals(RaceDetailUiState.Phase.NOT_FOUND, viewModel.uiState.value.phase)
        assertNull(repository.lastDetailId)
    }

    @Test
    fun `비활성 대회는 축제를 부르지 않는다`() = runTest(dispatcher) {
        // 원천이 사라진 대회의 주변 축제를 보여주면 아직 열리는 대회처럼 읽힌다 (결정-46).
        val repository = FakeContestRepository(active = false)

        val viewModel = RaceDetailViewModel(repository)
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
}

private class FakeContestRepository(
    private val festivals: List<NearbyFestival> = emptyList(),
    private val detailFailure: ApiException? = null,
    private val festivalFailure: ApiException? = null,
    private val active: Boolean = true,
) : ContestRepository {

    var lastDetailId: Long? = null
        private set
    var lastFestivalsId: Long? = null
        private set

    override suspend fun detail(id: Long): Contest {
        detailFailure?.let { throw it }
        lastDetailId = id
        return contest(id, active)
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

    override suspend fun closingSoon(limit: Int): List<ClosingSoon> =
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
