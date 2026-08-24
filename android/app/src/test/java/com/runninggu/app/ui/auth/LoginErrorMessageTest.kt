package com.runninggu.app.ui.auth

import com.runninggu.app.data.local.AuthTokens
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.AuthRepository
import com.runninggu.app.data.repository.AuthSession
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
import java.io.IOException

/**
 * A1 로그인 실패 문구. (SPEC §4.1 · 결정-55 · API 명세 §1-6)
 *
 * **세 갈래를 하나로 묶으면 사용자가 할 일을 못 찾는다.**
 *
 * 특히 `429 RATE_LIMITED` 를 일반 실패로 덮으면 비밀번호가 틀린 줄 알고 **다시 누른다.**
 * 그 요청이 또 IP 창에 쌓여 상황이 나빠지고, 결정-55 가 "성공 시 이메일 창만 초기화하고
 * IP 창은 유지" 라 다른 계정으로 로그인해도 안 풀린다.
 */
class LoginErrorMessageTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** 실패를 심고 로그인을 눌러 화면에 남는 문구를 돌려준다. */
    private suspend fun kotlinx.coroutines.test.TestScope.message(failure: Throwable): String {
        val viewModel = LoginViewModel(repository = FailingAuthRepository(failure)).apply {
            onEmailChange("runner@example.test")
            onPasswordChange("password12")
        }
        viewModel.onSubmit()
        advanceUntilIdle()
        return viewModel.uiState.value.errorMessage.orEmpty()
    }

    @Test
    fun `시도 제한은 기다리라고 알린다`() = runTest(dispatcher) {
        // 비밀번호가 맞아도 여기로 온다. "확인해 주세요" 를 보여주면 고쳐서 다시 누르고,
        // 그 요청이 또 IP 창에 쌓인다 (결정-55).
        val rateLimited = ApiException.Http(429, ApiErrorCode.RATE_LIMITED, null)

        val message = message(rateLimited)

        assertTrue("기다리라는 말이 없다: $message", message.contains("잠시 후"))
        assertTrue("비밀번호 탓으로 읽히면 안 된다: $message", !message.contains("비밀번호"))
    }

    @Test
    fun `인증 실패는 사유를 감춘다`() = runTest(dispatcher) {
        // 계정 존재 여부를 노출하지 않는다 (§4.1)
        val failed = ApiException.Http(401, ApiErrorCode.LOGIN_FAILED, null)

        assertEquals("이메일 또는 비밀번호를 확인해 주세요", message(failed))
    }

    @Test
    fun `통신 실패는 연결을 확인하라고 한다`() = runTest(dispatcher) {
        // 여기서 "비밀번호를 확인해 주세요" 가 뜨면 계속 고쳐 입력하게 된다
        val message = message(ApiException.Network(IOException("끊김")))

        assertTrue(message.contains("연결"))
    }

    @Test
    fun `서버 오류는 일반 실패로 둔다`() = runTest(dispatcher) {
        // 500 에 "시도가 많다" 를 붙이면 기다려도 안 풀리는 것을 기다리게 된다
        val error = ApiException.Http(500, ApiErrorCode.INTERNAL_SERVER_ERROR, null)

        assertEquals("이메일 또는 비밀번호를 확인해 주세요", message(error))
    }
}

private class FailingAuthRepository(private val failure: Throwable) : AuthRepository {

    override suspend fun login(email: String, password: String): Result<AuthSession> =
        Result.failure(failure)

    override suspend fun emailExists(email: String): Result<Boolean> = unused()
    override suspend fun nicknameExists(nickname: String): Result<Boolean> = unused()
    override suspend fun sendSignupCode(email: String): Result<Unit> = unused()
    override suspend fun verifySignupCode(email: String, code: String): Result<Unit> = unused()
    override suspend fun signup(
        email: String,
        password: String,
        nickname: String,
        marketingAgreed: Boolean,
    ): Result<AuthSession> = unused()
    override suspend fun requestPasswordReset(email: String): Result<Unit> = unused()
    override suspend fun logout(refreshToken: String): Result<Unit> = unused()

    private fun <T> unused(): T = throw UnsupportedOperationException("이 테스트는 로그인만 부른다")
}
