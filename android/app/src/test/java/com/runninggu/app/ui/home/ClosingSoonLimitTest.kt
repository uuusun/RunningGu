package com.runninggu.app.ui.home

import com.runninggu.app.ui.common.valueOrNull
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
 * 홈 마감 임박 노출 건수. (SPEC §4.4-3 🔒 · 결정-28 · #102 리뷰)
 *
 * **서버 계약과 같은 값이어야 한다.** `GET /api/contests/closing-soon` 의 `limit` 은 허용
 * 범위가 `1~4` 이고 벗어나면 `400 VALIDATION_FAILED` 다(API 명세 §3-3). 지금은 임시
 * 데이터라 넘겨도 티가 안 나지만, **서버가 붙는 순간 홈의 한 영역이 통째로 오류가 된다.**
 */
class ClosingSoonLimitTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `마감 임박은 상위 4건까지만 낸다`() = runTest(dispatcher) {
        val viewModel = HomeViewModel()
        advanceUntilIdle()

        val races = viewModel.uiState.value.closingSoon.valueOrNull.orEmpty()

        // 임시 데이터에 대회가 12건 넘게 있으므로, 이 단언은 6 이던 값을 실제로 잡는다.
        assertEquals(4, races.size)
    }

    @Test
    fun `마감이 이른 것부터 나온다`() = runTest(dispatcher) {
        // 상위 n 건을 자르는 규칙이라 정렬이 틀리면 엉뚱한 대회가 잘린다.
        val viewModel = HomeViewModel()
        advanceUntilIdle()

        val dates = viewModel.uiState.value.closingSoon.valueOrNull.orEmpty().mapNotNull { it.regEnd }

        assertEquals(dates.sorted(), dates)
    }
}
