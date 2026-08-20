package com.runninggu.app.ui.wizard

import com.runninggu.app.data.repository.GenerateItineraryRequest
import com.runninggu.app.data.repository.ItineraryRepository
import com.runninggu.app.ui.sample.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 생성을 못 부르는 상황에서 화면이 멈추지 않는지. (#66 리뷰)
 *
 * canonical id 가 없는 대회(번들·오프라인)는 서버 생성을 부를 수 없다. 예전에는 조용히
 * `return` 해서 **스피너가 영원히 도는** 상태가 됐다 — 화면은 어떤 이유로든 무반응이면 안 된다.
 */
class GenerateBlockedTest {

    /** 서버 저장소를 흉내낸다 — 데모 폴백이 없는 상태다. */
    private object NeverCalledRepository : ItineraryRepository {
        override suspend fun generate(request: GenerateItineraryRequest) =
            error("canonical id 가 없으면 서버를 부르면 안 된다")
    }

    private fun wizardWithSampleRace(): WizardUiState {
        // 샘플 대회는 serverId 가 null 이다 (#66 리뷰 — 가짜 canonical id 를 만들지 않는다)
        val race = SampleData.races.first()
        return WizardUiState(race = race, start = race.date, end = race.date)
    }

    @Test
    fun `canonical id 가 없으면 오류 상태로 떨어진다`() {
        val viewModel = ResultViewModel(repository = NeverCalledRepository)

        viewModel.generate(wizardWithSampleRace())

        val state = viewModel.uiState.value
        assertEquals(ResultUiState.Phase.ERROR, state.phase)
        assertNotNull(state.errorMessage)
    }

    @Test
    fun `조건이 덜 정해진 것과 서버를 못 부르는 것을 다르게 적는다`() {
        val viewModel = ResultViewModel(repository = NeverCalledRepository)

        // 날짜가 없다 — 되돌아가면 되는 경우
        viewModel.generate(WizardUiState(race = SampleData.races.first()))
        val incomplete = viewModel.uiState.value.errorMessage

        // 조건은 다 있는데 canonical id 가 없다 — 사용자가 할 수 있는 게 없는 경우
        val other = ResultViewModel(repository = NeverCalledRepository)
        other.generate(wizardWithSampleRace())

        assertNotNull(incomplete)
        assertNotNull(other.uiState.value.errorMessage)
        assert(incomplete != other.uiState.value.errorMessage)
    }

    // 스텁 저장소(데모) 경로는 여기서 검증하지 않는다 — viewModelScope 가 Main 디스패처를
    // 요구해서 코루틴 테스트 의존성이 필요한데, 새 라이브러리라 팀 합의가 먼저다(AGENTS 7장).
    // 이 테스트가 막는 것은 "무반응" 이고, 그건 코루틴을 타기 전에 갈리므로 위 둘로 충분하다.
}
