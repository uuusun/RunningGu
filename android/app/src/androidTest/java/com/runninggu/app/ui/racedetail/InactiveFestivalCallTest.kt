package com.runninggu.app.ui.racedetail

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 비활성 대회 상세에서 **인근 축제를 부르지 않는지**. (§3-4 · 결정-46 · #80 리뷰)
 *
 * 화면에서 섹션을 숨기는 것만으로는 부족하다. `GET /contests/{id}/festivals` 는 KTO 를
 * 거치는 외부 프록시라 **호출 자체가 비용**이고, 결정-46 이 막으라고 한 것이 그 호출이다.
 * 지금은 `SampleData` 라 티가 안 나지만 AP-14 로 실제 API 가 붙으면 그대로 샌다.
 *
 * `viewModelScope` 가 메인 디스패처를 쓰므로 계측 테스트로 둔다.
 */
class InactiveFestivalCallTest {

    /** 본문 300ms + 축제 700ms. 넉넉히 기다린 뒤 결과를 본다. */
    private suspend fun awaitLoads() = delay(1500)

    private fun onMain(block: () -> Unit) = runBlocking {
        withContext(Dispatchers.Main) { block() }
    }

    @Test
    fun 비활성_대회는_축제를_부르지_않는다() = runBlocking {
        val viewModel = withContext(Dispatchers.Main) { RaceDetailViewModel() }

        onMain { viewModel.start("jeonbuk-ended") }
        awaitLoads()

        val state = viewModel.uiState.value
        assertEquals(RaceDetailUiState.Phase.LOADED, state.phase)
        assertTrue("비활성 대회여야 한다", state.race?.active == false)
        // 조회를 시작조차 안 했으므로 초기값 그대로다. LOADED 로 바뀌었다면 불린 것이다.
        assertEquals(
            "비활성 대회인데 인근 축제를 불렀다",
            RaceDetailUiState.Phase.LOADING,
            state.festivalPhase,
        )
        assertTrue(state.festivals.isEmpty())
        // 화면에도 안 그린다
        assertTrue(!state.showFestivalSection)
    }

    @Test
    fun 활성_대회는_평소대로_부른다() = runBlocking {
        // 막는 조건이 과하게 걸려 정상 경로까지 죽이지 않았는지 본다
        val viewModel = withContext(Dispatchers.Main) { RaceDetailViewModel() }

        onMain { viewModel.start("seoul-hangang") }
        awaitLoads()

        val state = viewModel.uiState.value
        assertEquals(RaceDetailUiState.Phase.LOADED, state.festivalPhase)
        assertTrue("샘플에 축제가 있는 대회다", state.festivals.isNotEmpty())
        assertTrue(state.showFestivalSection)
    }

    @Test
    fun 비활성_대회는_다시_시도로도_부르지_않는다() = runBlocking {
        // [다시 시도] 는 loadFestivals() 를 직접 부른다 — 그리는 쪽에서 막았으면 여기서 샌다
        val viewModel = withContext(Dispatchers.Main) { RaceDetailViewModel() }

        onMain { viewModel.start("jeonbuk-ended") }
        awaitLoads()
        onMain { viewModel.loadFestivals() }
        awaitLoads()

        assertEquals(
            "다시 시도로 인근 축제를 불렀다",
            RaceDetailUiState.Phase.LOADING,
            viewModel.uiState.value.festivalPhase,
        )
    }
}
