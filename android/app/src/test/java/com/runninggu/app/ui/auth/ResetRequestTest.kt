package com.runninggu.app.ui.auth

import com.runninggu.app.ui.OFFLINE
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.AuthRepository
import com.runninggu.app.data.repository.AuthSession
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/**
 * A3 재설정 메일 발송. (SPEC §4.3 · API 명세 §1-11 · AP-08 · AP-14)
 *
 * 이 화면은 **성공을 가리지 않는다.** 서버가 가입 여부와 무관하게 `202` 를 주므로
 * (§4.3-1 계정 존재 비노출), 앱이 응답을 더 가릴 것이 없다. 대신 **실패는 갈라야 한다** —
 * 종류마다 사용자가 할 일이 다르기 때문이다.
 *
 * | 실패 | 사용자가 할 일 |
 * |---|---|
 * | 통신 실패 | 연결을 고친다 |
 * | `429 SEND_COOLDOWN` | **아무것도 안 해도 된다.** 직전 요청이 이미 나갔다 |
 * | 그 밖 | 잠시 후 다시 시도 |
 *
 * 쿨다운을 일반 실패로 덮으면 사용자는 실패한 줄 알고 버튼을 계속 누르고, 그 요청이 또
 * 쿨다운에 걸려 상황이 안 풀린다. A1 의 `RATE_LIMITED`(결정-55)와 같은 자리다.
 */
class ResetRequestTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `발송에 성공하면 가입 여부를 말하지 않는 안내로 넘어간다`() = runTest(dispatcher) {
        val repository = StubAuthRepository(Result.success(Unit))

        val state = submit(repository)

        assertTrue(state.sent)
        assertNull(state.errorMessage)
        assertEquals("runner@test.com", repository.requestedEmail)
    }

    @Test
    fun `쿨다운은 일반 실패로 덮지 않는다`() = runTest(dispatcher) {
        // 429 SEND_COOLDOWN 은 **직전 요청이 이미 나갔다**는 뜻이다(§1-11 쿨다운 60초).
        // "보내지 못했어요" 로 뭉치면 실패한 줄 알고 계속 누른다.
        val repository = StubAuthRepository(
            Result.failure(ApiException.Http(429, ApiErrorCode.SEND_COOLDOWN, null)),
        )

        val state = submit(repository)

        assertEquals(
            "조금 전에 보냈어요. 메일함을 확인하고, 없으면 1분 뒤에 다시 시도해 주세요.",
            state.errorMessage,
        )
        // 보냈다고 단정하지도 않는다 — 다시 시도할 수 있게 폼에 남긴다.
        assertFalse(state.sent)
    }

    @Test
    fun `통신 실패를 보냈다고 말하지 않는다`() = runTest(dispatcher) {
        // §4.3 의 비노출은 "서버가 202 를 준다" 는 뜻이지 통신 실패까지 성공으로
        // 보이라는 게 아니다. 비행기 모드에서 "보냈어요" 를 띄우면 오지 않을 메일을 기다린다.
        val repository = StubAuthRepository(
            Result.failure(ApiException.Network(IOException())),
        )

        val state = submit(repository)

        assertFalse(state.sent)
        assertEquals(OFFLINE, state.errorMessage)
    }

    @Test
    fun `그 밖의 서버 실패는 다시 시도하라고 한다`() = runTest(dispatcher) {
        val repository = StubAuthRepository(
            Result.failure(ApiException.Http(500, ApiErrorCode.INTERNAL_SERVER_ERROR, null)),
        )

        val state = submit(repository)

        assertFalse(state.sent)
        assertEquals("메일을 보내지 못했어요. 잠시 후 다시 시도해 주세요.", state.errorMessage)
    }

    @Test
    fun `앞뒤 공백은 떼고 보낸다`() = runTest(dispatcher) {
        // 키보드 자동완성이 뒤에 공백을 붙인다. 서버가 정규화하지만(§1-5) 그 전에
        // `isEmailValid` 가 막으면 사용자는 보이지 않는 공백 때문에 버튼을 못 누른다.
        val repository = StubAuthRepository(Result.success(Unit))

        submit(repository, email = "  runner@test.com  ")

        assertEquals("runner@test.com", repository.requestedEmail)
    }

    @Test
    fun `보내는 중에는 다시 누를 수 없다`() = runTest(dispatcher) {
        val repository = StubAuthRepository(Result.success(Unit))
        val viewModel = ResetViewModel(repository)
        viewModel.onEmailChange("runner@test.com")

        viewModel.onSubmit()
        viewModel.onSubmit() // 연타
        advanceUntilIdle()

        assertEquals(1, repository.calls)
    }

    @Test
    fun `보내는 사이 이메일이 바뀌어도 요청은 한 번이다`() = runTest(dispatcher) {
        // `MutableStateFlow.update` 는 CAS 에 실패하면 람다를 **다시 평가한다.** 안에
        // 네트워크 호출을 두면 그때 요청이 한 번 더 나가고, 첫 요청으로 메일이 갔는데도
        // 두 번째가 `429 SEND_COOLDOWN` 을 받아 화면은 쿨다운 문구가 된다(#187 리뷰).
        val repository = GatedAuthRepository()
        val viewModel = ResetViewModel(repository)
        viewModel.onEmailChange("runner@test.com")

        viewModel.onSubmit()
        advanceUntilIdle() // 요청이 응답을 기다리는 중이다

        viewModel.onEmailChange("other@test.com") // 그 사이 입력이 바뀐다
        repository.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, repository.calls)
        assertEquals("runner@test.com", repository.requestedEmail)
    }

    @Test
    fun `보내는 중에는 이메일이 바뀌지 않는다`() = runTest(dispatcher) {
        // 요청한 주소와 화면에 보이는 주소가 엇갈리면 사용자는 새 주소로 갔다고 읽는다.
        val repository = GatedAuthRepository()
        val viewModel = ResetViewModel(repository)
        viewModel.onEmailChange("runner@test.com")

        viewModel.onSubmit()
        advanceUntilIdle()
        viewModel.onEmailChange("other@test.com")

        assertEquals("runner@test.com", viewModel.uiState.value.email)
        repository.gate.complete(Unit)
        advanceUntilIdle()
    }

    private fun TestScope.submit(
        repository: StubAuthRepository,
        email: String = "runner@test.com",
    ): ResetUiState {
        val viewModel = ResetViewModel(repository)
        viewModel.onEmailChange(email)
        viewModel.onSubmit()
        advanceUntilIdle()
        return viewModel.uiState.value
    }

    /**
     * 응답을 붙들어 둔다. "보내는 중" 에 다른 일이 끼어드는 상황을 실제로 만들려면 필요하다.
     */
    private class GatedAuthRepository : AuthRepository by StubAuthRepository(Result.success(Unit)) {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
            private set
        var requestedEmail: String? = null
            private set

        override suspend fun requestPasswordReset(email: String): Result<Unit> {
            calls++
            requestedEmail = email
            gate.await()
            return Result.success(Unit)
        }
    }

    /** 재설정 요청만 실제로 동작시킨다. 나머지는 이 테스트가 부르지 않는다. */
    private class StubAuthRepository(private val result: Result<Unit>) : AuthRepository {
        var calls = 0
            private set
        var requestedEmail: String? = null
            private set

        override suspend fun requestPasswordReset(email: String): Result<Unit> {
            calls++
            requestedEmail = email
            return result
        }

        override suspend fun emailExists(email: String): Result<Boolean> = TODO()
        override suspend fun nicknameExists(nickname: String): Result<Boolean> = TODO()
        override suspend fun login(email: String, password: String): Result<AuthSession> = TODO()
        override suspend fun sendSignupCode(email: String): Result<Unit> = TODO()
        override suspend fun verifySignupCode(email: String, code: String): Result<Unit> = TODO()
        override suspend fun signup(
            email: String,
            password: String,
            nickname: String,
            marketingAgreed: Boolean,
        ageOver14: Boolean,
        ): Result<AuthSession> = TODO()

        override suspend fun logout(refreshToken: String): Result<Unit> = Result.success(Unit)
    }
}
