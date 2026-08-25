package com.runninggu.app.ui

import com.runninggu.app.data.repository.PoiRepository
import com.runninggu.app.data.model.PoiSearchResult
import com.runninggu.app.domain.PoiCategory
import com.runninggu.app.ui.wizard.StayUiState
import com.runninggu.app.ui.wizard.StayViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * 취소는 실패가 아니다. (#173 리뷰의 규칙을 화면 쪽에도 적용)
 *
 * `runCatching` 은 [CancellationException] 까지 잡는다. suspend 호출을 그걸로 감싸면
 * **호출자가 사라졌다는 신호가 "요청이 실패했다" 로 바뀐다.** 화면을 벗어나 코루틴이
 * 취소된 뒤에도 `onFailure` 가 돌아 오류 상태를 쓰고, 낙관적으로 바꾼 값을 되돌린다.
 *
 * `RemoteFavoriteRepository.withServerId` 가 같은 이유로 같은 규칙을 쓴다(#173 리뷰).
 * 그때 "여기서도 막아 둔다 — 다음에 다른 자리에서 부를 때 같은 사고가 나지 않게" 라고
 * 적어 둔 그 다른 자리가 여기다.
 */
class CancellationIsNotFailureTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `취소는 다시 던진다`() {
        val thrown = runCatching {
            runCatchingUnlessCancelled { throw CancellationException("호출자가 사라졌다") }
        }

        assertTrue(
            "취소가 Result 에 담겼다 — 실패로 접힌 것이다",
            thrown.exceptionOrNull() is CancellationException,
        )
    }

    @Test
    fun `그 밖의 예외는 실패로 담는다`() {
        val result = runCatchingUnlessCancelled { throw IOException("끊김") }

        assertTrue("실패로 안 담겼다", result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun `취소는 오류 화면으로 접히지 않는다`() = runTest(dispatcher) {
        // S6 숙소 조회가 취소되면 ERROR 로 넘어가면 안 된다 — 화면을 벗어나는 중인데
        // "숙소를 불러오지 못했어요" 를 쓰는 것은 사실도 아니고 쓸모도 없다.
        val viewModel = StayViewModel(CancellingPois)

        viewModel.start(lat = 37.5663, lng = 126.9779)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("취소가 실패로 접혔다", StayUiState.Phase.LOADING, state.phase)
        assertNull("취소인데 오류 문구가 붙었다", state.errorMessage)
    }

    @Test
    fun `조회 실패는 그대로 오류 화면이다`() = runTest(dispatcher) {
        // 취소만 가려낸다. 진짜 실패까지 조용해지면 사용자는 빈 화면을 보고 기다린다.
        val viewModel = StayViewModel(FailingPois)

        viewModel.start(lat = 37.5663, lng = 126.9779)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(StayUiState.Phase.ERROR, state.phase)
        assertEquals("숙소를 불러오지 못했어요.", state.errorMessage)
    }
}

/** 호출자가 사라진 상황을 흉내낸다. */
private object CancellingPois : PoiRepository {
    override suspend fun search(
        category: PoiCategory,
        lat: Double,
        lng: Double,
        query: String?,
        size: Int,
    ): PoiSearchResult = throw CancellationException("호출자가 사라졌다")
}

/** 진짜 실패. */
private object FailingPois : PoiRepository {
    override suspend fun search(
        category: PoiCategory,
        lat: Double,
        lng: Double,
        query: String?,
        size: Int,
    ): PoiSearchResult = throw com.runninggu.app.data.remote.ApiException.Network(IOException("끊김"))
}
