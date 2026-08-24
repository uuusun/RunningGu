package com.runninggu.app.ui.wizard

import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.GenerateItineraryRequest
import com.runninggu.app.data.repository.ItineraryRepository
import com.runninggu.app.ui.sample.SampleData
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

/**
 * 비활성 대회로 동선을 만들려 할 때. (API 명세 §5-1 · SPEC 결정-53 · 결정-46)
 *
 * **재시도가 소용있는지로 갈라야 한다.** `409 CONTEST_INACTIVE` 는 원천에서 사라진
 * 대회라 다시 눌러도 살아나지 않는다. [다시 시도] 를 주면 헛돌고, 사용자는 뭘 더 해야
 * 하는지 모른 채 계속 누른다.
 *
 * 이 경로는 **CTA 를 통과한 뒤 대회가 비활성으로 바뀐 경우**에만 온다 — 평소에는
 * S3 가 동선 CTA 자체를 막는다(#139). 드물어서 눈으로는 잘 안 보이는 자리다.
 */
class InactiveContestTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FailingRepository(private val error: Throwable) : ItineraryRepository {
        override suspend fun generate(request: GenerateItineraryRequest): Nothing = throw error
    }

    private fun http(code: ApiErrorCode, status: Int) =
        FailingRepository(ApiException.Http(status = status, code = code, problem = null))

    /** canonical id 가 있어야 서버를 부른다 — 없으면 그 전에 막힌다(#66). */
    private fun wizard(): WizardUiState {
        val race = SampleData.races.first().copy(serverId = 1L)
        return WizardUiState(race = race, start = race.date, end = race.date)
    }

    @Test
    fun `비활성 대회는 다시 시도를 주지 않는다`() = runTest(dispatcher) {
        val viewModel = ResultViewModel(repository = http(ApiErrorCode.CONTEST_INACTIVE, 409))

        viewModel.generate(wizard())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ResultUiState.Phase.ERROR, state.phase)
        assertFalse("재시도 버튼을 주면 헛돈다", state.canRetry)
    }

    @Test
    fun `비활성 대회는 할 일을 알려 준다`() = runTest(dispatcher) {
        // "동선을 만들지 못했어요" 로만 적으면 사용자는 다시 눌러 보게 된다.
        val viewModel = ResultViewModel(repository = http(ApiErrorCode.CONTEST_INACTIVE, 409))

        viewModel.generate(wizard())
        advanceUntilIdle()

        val message = viewModel.uiState.value.errorMessage.orEmpty()
        assertTrue("무엇을 하라는지 없다: $message", message.contains("다른 대회"))
    }

    @Test
    fun `그 밖의 실패는 다시 시도를 준다`() = runTest(dispatcher) {
        // 네트워크·5xx 는 재시도가 실제로 통한다. 과하게 막으면 안 된다.
        val viewModel = ResultViewModel(
            repository = FailingRepository(ApiException.Network(java.io.IOException("끊김"))),
        )

        viewModel.generate(wizard())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ResultUiState.Phase.ERROR, state.phase)
        assertTrue(state.canRetry)
    }

    @Test
    fun `500 도 다시 시도를 준다`() = runTest(dispatcher) {
        val viewModel = ResultViewModel(repository = http(ApiErrorCode.UNKNOWN, 500))

        viewModel.generate(wizard())
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canRetry)
    }
}
