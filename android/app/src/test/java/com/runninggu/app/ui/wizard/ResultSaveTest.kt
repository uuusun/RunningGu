package com.runninggu.app.ui.wizard

import com.runninggu.app.data.model.ItineraryResult
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.FakeItineraryRepository
import com.runninggu.app.data.repository.GenerateItineraryRequest
import com.runninggu.app.data.repository.ItineraryRepository
import com.runninggu.app.data.repository.SaveOutcome
import com.runninggu.app.domain.BlockType
import com.runninggu.app.ui.sample.SampleData
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

/**
 * S7 동선 저장. (SPEC §4.10 · API 명세 §5-2 · 매핑표 "새 동선 저장")
 *
 * 저장은 **성공하면 화면을 떠난다** — §4.10 이 마이[동선]으로 옮기라고 했다. 그래서
 * 성공 문구는 이 화면이 아니라 [SaveItineraryState.Saved] 에 실려 나간다. 반대로 실패는
 * 화면에 남아야 다시 누를 수 있다.
 */
class ResultSaveTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // 싱글턴이라 올려 둔 세대가 다음 케이스로 샌다
        SessionStore.resetForTest()
    }

    /**
     * 생성은 가짜 fixture 를 그대로 쓰고 저장만 바꿔 낀다.
     *
     * [FakeItineraryRepository] 를 **그대로 넘기지 않는 이유**가 있다 — `ResultViewModel`
     * 은 그 객체일 때만 데모 대회 id 를 쓰므로, 위임하면 서버 경로와 같은 조건이 된다.
     */
    private class SavingRepository(
        private val outcome: suspend () -> SaveOutcome,
    ) : ItineraryRepository {
        var saveCount = 0
            private set

        override suspend fun generate(request: GenerateItineraryRequest): ItineraryResult =
            FakeItineraryRepository.generate(request)

        override suspend fun save(result: ItineraryResult): SaveOutcome {
            saveCount++
            return outcome()
        }
    }

    private fun failing(error: Throwable) = SavingRepository { throw error }

    /** canonical id 가 있어야 서버를 부른다 — 없으면 생성 단계에서 막힌다(#66). */
    private fun wizard(): WizardUiState {
        val race = SampleData.races.first().copy(serverId = 1L)
        return WizardUiState(race = race, start = race.date, end = race.date)
    }

    private fun loaded(repository: ItineraryRepository): ResultViewModel =
        ResultViewModel(repository = repository).also { it.generate(wizard()) }

    @Test
    fun `저장하면 마이로 가져갈 문구를 든다`() = runTest(dispatcher) {
        val viewModel = loaded(SavingRepository { SaveOutcome(id = 42L, replaced = false) })
        advanceUntilIdle()

        viewModel.onSave()
        advanceUntilIdle()

        val save = viewModel.uiState.value.save
        assertTrue("저장 성공이 아니다: $save", save is SaveItineraryState.Saved)
        save as SaveItineraryState.Saved
        assertEquals(42L, save.id)
        assertFalse(save.replaced)
        assertTrue("어디에 저장됐는지 없다: ${save.message}", save.message.contains("마이"))
    }

    @Test
    fun `교체는 새로 담은 것과 문구가 다르다`() = runTest(dispatcher) {
        // 같은 (대회, 기간) 이면 서버가 기존 동선을 갈아 끼운다(§5-2). 사용자에게는
        // "새로 저장했다" 와 "덮어썼다" 가 다른 일이다.
        val viewModel = loaded(SavingRepository { SaveOutcome(id = 42L, replaced = true) })
        advanceUntilIdle()

        viewModel.onSave()
        advanceUntilIdle()

        val save = viewModel.uiState.value.save as SaveItineraryState.Saved
        assertTrue(save.replaced)
        assertTrue("덮어썼다는 말이 없다: ${save.message}", save.message.contains("바꿨"))
    }

    @Test
    fun `게스트는 문구가 아니라 모달이다`() = runTest(dispatcher) {
        // 로그인은 화면을 옮겨야 끝나는 일이라 안내 한 줄로는 부족하다(매핑표 S7 게스트 modal).
        val viewModel = loaded(
            failing(ApiException.Http(status = 401, code = ApiErrorCode.UNKNOWN, problem = null)),
        )
        advanceUntilIdle()

        viewModel.onSave()
        advanceUntilIdle()

        assertEquals(SaveItineraryState.NeedsLogin, viewModel.uiState.value.save)
    }

    @Test
    fun `모달을 닫으면 버튼이 다시 눌린다`() = runTest(dispatcher) {
        // 로그인하고 돌아와도 저장이 저절로 일어나지 않는다(D-27). 다시 누를 수 있어야 한다.
        val viewModel = loaded(
            failing(ApiException.Http(status = 401, code = ApiErrorCode.UNKNOWN, problem = null)),
        )
        advanceUntilIdle()
        viewModel.onSave()
        advanceUntilIdle()

        viewModel.onLoginPromptDismiss()

        assertEquals(SaveItineraryState.Idle, viewModel.uiState.value.save)
        assertTrue(viewModel.uiState.value.canSave)
    }

    @Test
    fun `실패는 화면에 남는다`() = runTest(dispatcher) {
        val viewModel = loaded(failing(ApiException.Network(java.io.IOException("끊김"))))
        advanceUntilIdle()

        viewModel.onSave()
        advanceUntilIdle()

        val save = viewModel.uiState.value.save
        assertTrue("실패가 아니다: $save", save is SaveItineraryState.Failed)
        // 화면을 안 떠나므로 다시 누를 수 있어야 한다
        assertTrue(viewModel.uiState.value.canSave)
    }

    @Test
    fun `보내는 중에는 두 번 나가지 않는다`() = runTest(dispatcher) {
        // 교체 규칙이 있어 두 벌이 쌓이지는 않지만, 늦은 두 번째 응답이 이미 옮겨 간
        // 화면을 다시 건드린다.
        val gate = CompletableDeferred<SaveOutcome>()
        val repository = SavingRepository { gate.await() }
        val viewModel = loaded(repository)
        advanceUntilIdle()

        viewModel.onSave()
        advanceUntilIdle()
        assertEquals(SaveItineraryState.Saving, viewModel.uiState.value.save)
        assertFalse("저장 중에는 버튼이 막혀야 한다", viewModel.uiState.value.canSave)

        viewModel.onSave()
        advanceUntilIdle()
        assertEquals(1, repository.saveCount)

        gate.complete(SaveOutcome(id = 42L, replaced = false))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.save is SaveItineraryState.Saved)
    }

    @Test
    fun `내용을 고치면 이전 저장 결과가 사라진다`() = runTest(dispatcher) {
        // "저장하지 못했어요" 가 남은 채 장소를 바꾸면, 방금 바꾼 것이 실패한 줄로 읽힌다.
        val viewModel = loaded(failing(ApiException.Network(java.io.IOException("끊김"))))
        advanceUntilIdle()
        viewModel.onSave()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.save is SaveItineraryState.Failed)

        val userBlock = viewModel.uiState.value.activeDay!!.blocks
            .first { it.blockType == BlockType.USER }
        viewModel.onRemoveBlock(userBlock.id)

        assertEquals(SaveItineraryState.Idle, viewModel.uiState.value.save)
    }

    // ── 화면을 떠난 뒤 (SPEC §4.10 · #214 리뷰) ──────────────────

    @Test
    fun `성공을 쓰고 나면 비운다`() = runTest(dispatcher) {
        // 안 비우면 마이에서 뒤로 왔을 때 이 화면이 같은 상태로 다시 합성돼 곧바로
        // 마이로 튕긴다 — 뒤로가기가 막힌다
        val viewModel = loaded(SavingRepository { SaveOutcome(id = 42L, replaced = false) })
        advanceUntilIdle()
        viewModel.onSave()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.save is SaveItineraryState.Saved)

        viewModel.onSavedHandled()

        assertEquals(SaveItineraryState.Idle, viewModel.uiState.value.save)
    }

    // ── 기다리는 사이 세션이 바뀌면 (#166 리뷰 · S8 과 같은 장치) ──

    @Test
    fun `보내는 사이 세션이 바뀌면 그 결과를 버린다`() = runTest(dispatcher) {
        // 남의 계정에 저장된 결과를 들고 "마이에 저장했어요" 로 옮겨 가면, 로그아웃된
        // 사용자가 저장됐다고 믿는다
        val viewModel = loaded(
            SavingRepository {
                SessionStore.signOut(expectedEpoch = SessionStore.sessionEpoch)
                SaveOutcome(id = 42L, replaced = false)
            },
        )
        advanceUntilIdle()

        viewModel.onSave()
        advanceUntilIdle()

        val save = viewModel.uiState.value.save
        assertTrue("남의 결과를 그대로 들었다: $save", save !is SaveItineraryState.Saved)
        // 버리더라도 버튼은 풀어 준다 — Saving 인 채로 두면 "저장 중…" 이 굳는다
        assertEquals(SaveItineraryState.Idle, save)
        assertTrue(viewModel.uiState.value.canSave)
    }

    @Test
    fun `보내는 사이 세션이 죽어도 로그인 안내는 살린다`() = runTest(dispatcher) {
        // 세대가 오르는 흔한 이유가 바로 "세션이 죽었다" 이다. 이것까지 버리면 정작
        // 로그인하라는 말을 해야 할 때 아무 말도 못 한다
        val viewModel = loaded(
            SavingRepository {
                SessionStore.signOut(expectedEpoch = SessionStore.sessionEpoch)
                throw ApiException.Http(status = 401, code = ApiErrorCode.UNKNOWN, problem = null)
            },
        )
        advanceUntilIdle()

        viewModel.onSave()
        advanceUntilIdle()

        assertEquals(SaveItineraryState.NeedsLogin, viewModel.uiState.value.save)
    }

    @Test
    fun `결과가 없으면 아무 일도 하지 않는다`() = runTest(dispatcher) {
        // 로딩 중에 눌리는 경로는 화면이 막지만, 여기서 한 번 더 막는다.
        val repository = SavingRepository { SaveOutcome(id = 42L, replaced = false) }
        val viewModel = ResultViewModel(repository = repository)

        viewModel.onSave()
        advanceUntilIdle()

        assertEquals(0, repository.saveCount)
        assertEquals(SaveItineraryState.Idle, viewModel.uiState.value.save)
    }
}
