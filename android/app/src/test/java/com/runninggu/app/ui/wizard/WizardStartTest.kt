package com.runninggu.app.ui.wizard

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
import com.runninggu.app.domain.TripPattern
import androidx.lifecycle.SavedStateHandle
import com.runninggu.app.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/**
 * 위저드 진입이 서버 대회를 본다. (SPEC §2.4 · §4.7 · API 명세 §3-4 · 이슈 #140)
 *
 * **예전에는 `SampleData.raceById()` 였다.** 홈·캘린더가 서버로 넘어간 뒤 넘어오는 id 는
 * 숫자 canonical id 인데 샘플 id 는 `chungbuk-past` 같은 슬러그라 절대 안 맞았고,
 * `?: return` 이라 조용히 나가서 **S4 가 "불러오는 중…" 에서 영영 멈췄다.**
 *
 * 그래서 이 파일이 지키는 것은 두 가지다.
 *
 * 1. 조회 결과가 **어떻게 끝나든 로딩으로 남지 않는다** — `race == null` 이 곧 로딩이던
 *    암묵 규칙을 [WizardUiState.contestPhase] 가 타입으로 대체했다
 * 2. **재시도가 소용있는지로 가른다** — S3 [com.runninggu.app.ui.racedetail] 와 같은 기준
 */
class WizardStartTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `프로세스가 복원돼도 대회를 스스로 싣는다`() = runTest(dispatcher) {
        // 시스템이 S5·S6·S7 로 바로 복원하면 S4 가 합성되지 않아 `start()` 를 부르는 곳이
        // 없다. 예전에는 그래서 S5 가 영영 로딩이고, S7 은 기본 상태로 생성을 걸어
        // "조건이 덜 정해졌어요" 가 됐다(#192 리뷰).
        //
        // 그래프 인자가 이미 답을 들고 있으므로 ViewModel 이 직접 읽는다.
        val repository = FakeContestRepository()
        val restored = SavedStateHandle(mapOf(Routes.ARG_RACE_ID to "7"))

        val viewModel = WizardViewModel(repository, restored)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(WizardUiState.Phase.LOADED, state.contestPhase)
        assertEquals(7L, state.race?.serverId)
    }

    @Test
    fun `복원 인자가 없으면 부르지 않는다`() = runTest(dispatcher) {
        // 위저드 밖에서 만들어진 경우다. 없는 대회를 조회하면 안 된다.
        val repository = FakeContestRepository()

        WizardViewModel(repository, SavedStateHandle())
        advanceUntilIdle()

        assertEquals(0, repository.detailCalls)
    }

    @Test
    fun `복원 뒤 화면을 다시 열어도 재조회하지 않는다`() = runTest(dispatcher) {
        // 복원으로 이미 실은 뒤 S4 가 합성되면 `start()` 가 한 번 더 불린다.
        val repository = FakeContestRepository()
        val viewModel = WizardViewModel(repository, SavedStateHandle(mapOf(Routes.ARG_RACE_ID to "7")))
        advanceUntilIdle()

        viewModel.start("7")
        advanceUntilIdle()

        assertEquals(1, repository.detailCalls)
    }

    @Test
    fun `서버 대회를 싣고 기본값을 채운다`() = runTest(dispatcher) {
        val viewModel = WizardViewModel(FakeContestRepository())

        viewModel.start("7")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(WizardUiState.Phase.LOADED, state.contestPhase)
        assertEquals(7L, state.race?.serverId)
        // 기본 종목은 하프 우선, 기본 패턴은 "전후로" 다 (SPEC §4.7 · §4.8).
        // 서버로 옮기면서 이 채움이 빠지면 S4 가 날짜 없이 열려 CTA 가 막힌다.
        assertEquals(EventType.HALF, state.event)
        assertEquals(TripPattern.DEFAULT, state.pattern)
        assertNotNull("기본 패턴의 기간이 안 채워졌다", state.start)
        assertNotNull("기본 패턴의 기간이 안 채워졌다", state.end)
    }

    @Test
    fun `대회장 좌표가 위저드까지 실린다`() = runTest(dispatcher) {
        // S6 숙소·S7 후보가 이 좌표를 기준으로 POI 를 조회한다. 안 실리면 화면이
        // (0.0, 0.0) 으로 폴백해 기니만 앞바다를 뒤진다 (#136 리뷰 · SPEC §4.9)
        val viewModel = WizardViewModel(FakeContestRepository())

        viewModel.start("7")
        advanceUntilIdle()

        val race = viewModel.uiState.value.race
        assertEquals(36.4801, race?.lat)
        assertEquals(127.2890, race?.lng)
    }

    @Test
    fun `조회에 실패해도 로딩으로 남지 않는다`() = runTest(dispatcher) {
        // **이 파일의 핵심이다.** 실패도 `race == null` 이라, 화면이 그걸 로딩으로 읽으면
        // 스피너가 영영 돈다. 상태가 스스로 "실패" 라고 말해야 한다.
        val viewModel = WizardViewModel(
            FakeContestRepository(detailFailure = ApiException.Network(IOException("끊김"))),
        )

        viewModel.start("7")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(WizardUiState.Phase.ERROR, state.contestPhase)
        assertNull(state.race)
        assertNotNull("무엇이 잘못됐는지 화면이 말할 수 없다", state.errorMessage)
    }

    @Test
    fun `404 는 다시 시도를 주지 않는 상태다`() = runTest(dispatcher) {
        // 홈·캘린더에서 넘어온 뒤 그 대회가 비활성으로 바뀐 경우가 실제로 있다 (결정-46).
        // 다시 눌러도 생기지 않으므로 ERROR 와 갈라 둔다.
        val viewModel = WizardViewModel(
            FakeContestRepository(
                detailFailure = ApiException.Http(404, ApiErrorCode.NOT_FOUND, null),
            ),
        )

        viewModel.start("7")
        advanceUntilIdle()

        assertEquals(WizardUiState.Phase.NOT_FOUND, viewModel.uiState.value.contestPhase)
    }

    @Test
    fun `canonical id 가 없으면 서버를 부르지 않는다`() = runTest(dispatcher) {
        // 번들 항목의 id 는 크롤 원천 문자열이다. 숫자로 못 바꾸면 서버에 물을 수 없고,
        // 그건 "서버에 없다" 와 같다 (#139 와 같은 판정).
        val repository = FakeContestRepository()
        val viewModel = WizardViewModel(repository)

        viewModel.start("roadrun-41543")
        advanceUntilIdle()

        assertEquals(WizardUiState.Phase.NOT_FOUND, viewModel.uiState.value.contestPhase)
        assertEquals("헛 왕복이 나갔다", 0, repository.detailCalls)
    }

    @Test
    fun `같은 대회로 다시 들어와도 재조회하지 않는다`() = runTest(dispatcher) {
        // 화면 재구성마다 `LaunchedEffect` 가 start 를 부른다. 그때마다 조회하면
        // 사용자가 고른 종목·기간이 매번 기본값으로 되돌아간다.
        val repository = FakeContestRepository()
        val viewModel = WizardViewModel(repository)

        viewModel.start("7")
        advanceUntilIdle()
        viewModel.onEventSelect(EventType.FULL)
        viewModel.start("7")
        advanceUntilIdle()

        assertEquals(1, repository.detailCalls)
        assertEquals("고른 종목이 되돌아갔다", EventType.FULL, viewModel.uiState.value.event)
    }

    @Test
    fun `다시 시도가 성공하면 위저드가 열린다`() = runTest(dispatcher) {
        val repository = FakeContestRepository(
            detailFailure = ApiException.Network(IOException("끊김")),
            failOnce = true,
        )
        val viewModel = WizardViewModel(repository)

        viewModel.start("7")
        advanceUntilIdle()
        assertEquals(WizardUiState.Phase.ERROR, viewModel.uiState.value.contestPhase)

        viewModel.load()
        advanceUntilIdle()

        assertEquals(WizardUiState.Phase.LOADED, viewModel.uiState.value.contestPhase)
    }
}

/** 대회 상세만 답하는 가짜. 위저드는 목록·축제를 부르지 않는다. */
private class FakeContestRepository(
    private val detailFailure: ApiException? = null,
    /** 첫 조회만 실패시킨다. [WizardViewModel.load] 재시도가 성공하는지 보려고 쓴다. */
    private val failOnce: Boolean = false,
) : ContestRepository {

    var detailCalls = 0
        private set

    override suspend fun detail(id: Long): Contest {
        detailCalls++
        detailFailure?.let { if (!failOnce || detailCalls == 1) throw it }
        return contest(id)
    }

    override suspend fun festivals(id: Long): List<NearbyFestival> =
        throw UnsupportedOperationException("위저드는 축제를 부르지 않는다")

    override suspend fun list(filter: ContestFilter, cursor: String?): ContestPage =
        throw UnsupportedOperationException("위저드는 목록을 부르지 않는다")

    override suspend fun dailyCounts(
        year: Int,
        month: Int,
        filter: ContestFilter,
    ): Map<LocalDate, Int> = throw UnsupportedOperationException("위저드는 집계를 부르지 않는다")

    override suspend fun closingSoon(limit: Int): List<ClosingSoon> =
        throw UnsupportedOperationException("위저드는 마감임박을 부르지 않는다")

    private fun contest(id: Long) = Contest(
        id = id.toString(),
        serverId = id,
        name = "세종 호수공원 마라톤",
        region = "세종",
        venue = "세종 호수공원",
        date = LocalDate.of(2026, 9, 12),
        startTime = null,
        eventTypes = listOf(EventType.HALF, EventType.TEN_K),
        regStart = null,
        regEnd = null,
        regStatusFallback = RegistrationStatus.OPEN,
        organizer = "세종 육상연맹",
        officialUrl = null,
        detailUrl = null,
        imageUrl = null,
        lat = 36.4801,
        lng = 127.2890,
        category = null,
        checked = null,
        active = true,
        sources = listOf("MARATHON_GO"),
    )
}
