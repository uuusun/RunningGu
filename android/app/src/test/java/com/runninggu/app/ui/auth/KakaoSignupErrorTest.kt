package com.runninggu.app.ui.auth

import com.runninggu.app.ui.OFFLINE
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.AuthRepository
import com.runninggu.app.data.repository.AuthSession
import com.runninggu.app.data.repository.KakaoLoginOutcome
import java.io.IOException
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

/**
 * 카카오 가입이 실패했을 때 **사용자가 나갈 길을 아는가.** (API 명세 §1-8 · AP-08 · #216 리뷰)
 *
 * A2 의 카카오 갈래에는 버튼이 [가입 완료] 하나뿐이다. 그래서 **다시 눌러서 풀리지 않는
 * 오류를 "다시 시도해 주세요" 로 뭉뚱그리면 사용자가 그 화면에 갇힌다** — 같은 버튼만
 * 계속 누르게 된다.
 *
 * 갇히는 오류가 둘이다.
 *
 * - `409 KAKAO_ACCOUNT_DUPLICATED` — 이미 가입돼 있다. 서버가 **중복 시 자동 로그인을
 *   시켜 주지 않기로 했으므로**(§1-8) A1 로 돌아가는 것 말고는 방법이 없다
 * - `401 INVALID_KAKAO_TOKEN` — 닉네임 고르는 사이 토큰이 만료됐다. 여기 남은 토큰은
 *   이미 죽었다
 *
 * 이 파일이 고정하는 것은 문구 자체가 아니라 **셋이 서로 다르다는 것**이다.
 */
class KakaoSignupErrorTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        SessionStore.resetForTest()
        Dispatchers.resetMain()
    }

    /** 카카오 핸드오프를 거쳐 [가입 완료] 를 누른 상태까지 만든다. */
    private fun submit(failure: Throwable): SignupViewModel {
        val viewModel = SignupViewModel(repository = FakeKakaoSignupRepository(failure))
        viewModel.startKakaoSignup(
            kakaoAccessToken = "kakao-access",
            nickname = "카카오러너",
            email = null,
        )
        viewModel.onInfoNext()
        return viewModel
    }

    @Test
    fun `이미 가입된 계정이면 로그인 화면으로 안내한다`() = runTest(dispatcher) {
        val viewModel = submit(
            ApiException.Http(status = 409, code = ApiErrorCode.KAKAO_ACCOUNT_DUPLICATED, problem = null),
        )
        advanceUntilIdle()

        // "다시 시도" 로 적으면 몇 번을 눌러도 같은 409 가 온다
        assertEquals(
            "이미 가입된 카카오 계정이에요. 로그인 화면에서 [카카오로 시작하기]를 눌러 주세요.",
            viewModel.uiState.value.errorMessage,
        )
        assertNull(SessionStore.session.value)
    }

    @Test
    fun `카카오 토큰이 만료되면 처음부터 다시 하라고 말한다`() = runTest(dispatcher) {
        val viewModel = submit(
            ApiException.Http(status = 401, code = ApiErrorCode.INVALID_KAKAO_TOKEN, problem = null),
        )
        advanceUntilIdle()

        // 화면에 남은 토큰은 이미 죽었다. 같은 버튼으로는 안 풀린다
        assertEquals(
            "카카오 인증이 만료됐어요. 로그인 화면에서 다시 시작해 주세요.",
            viewModel.uiState.value.errorMessage,
        )
        assertNull(SessionStore.session.value)
    }

    @Test
    fun `네트워크 실패는 다시 눌러 볼 수 있다고 말한다`() = runTest(dispatcher) {
        val viewModel = submit(ApiException.Network(IOException("끊김")))
        advanceUntilIdle()

        // 위 둘과 달리 **여기서는 다시 누르는 게 맞다.** 문구가 같으면 그 차이가 사라진다
        assertEquals(
            OFFLINE,
            viewModel.uiState.value.errorMessage,
        )
    }

    @Test
    fun `그 밖의 실패는 일반 문구로 둔다`() = runTest(dispatcher) {
        val viewModel = submit(
            ApiException.Http(status = 500, code = ApiErrorCode.UNKNOWN, problem = null),
        )
        advanceUntilIdle()

        assertEquals("가입에 실패했어요. 다시 시도해 주세요.", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `세 문구가 서로 다르다`() = runTest(dispatcher) {
        // 하나씩 보면 통과하는데 둘이 같아지는 사고를 막는다 — 가르는 것이 이 파일의 목적이다
        val messages = listOf(
            ApiException.Http(status = 409, code = ApiErrorCode.KAKAO_ACCOUNT_DUPLICATED, problem = null),
            ApiException.Http(status = 401, code = ApiErrorCode.INVALID_KAKAO_TOKEN, problem = null),
            ApiException.Http(status = 500, code = ApiErrorCode.UNKNOWN, problem = null),
        ).map { cause ->
            submit(cause).also { advanceUntilIdle() }.uiState.value.errorMessage
        }

        assertTrue("문구가 겹친다: $messages", messages.toSet().size == 3)
        assertTrue("문구가 비었다: $messages", messages.all { !it.isNullOrBlank() })
    }

    @Test
    fun `실패해도 가입 완료로 넘어가지 않는다`() = runTest(dispatcher) {
        val viewModel = submit(
            ApiException.Http(status = 409, code = ApiErrorCode.KAKAO_ACCOUNT_DUPLICATED, problem = null),
        )
        advanceUntilIdle()

        // 넘어가 버리면 세션 없이 완료 화면이 뜬다 — 홈에서 요청마다 401 이 난다
        assertTrue(viewModel.uiState.value.step != SignupStep.DONE)
        assertTrue(!viewModel.uiState.value.isSubmitting)
    }
}

private class FakeKakaoSignupRepository(private val failure: Throwable) : AuthRepository {
    override suspend fun kakaoSignup(
        kakaoAccessToken: String,
        nickname: String,
        marketingAgreed: Boolean,
    ): Result<AuthSession> = Result.failure(failure)

    override suspend fun kakaoLogin(kakaoAccessToken: String): Result<KakaoLoginOutcome> = unused()
    override suspend fun logout(refreshToken: String): Result<Unit> = unused()
    override suspend fun emailExists(email: String): Result<Boolean> = unused()
    override suspend fun nicknameExists(nickname: String): Result<Boolean> = unused()
    override suspend fun login(email: String, password: String): Result<AuthSession> = unused()
    override suspend fun sendSignupCode(email: String): Result<Unit> = unused()
    override suspend fun verifySignupCode(email: String, code: String): Result<Unit> = unused()
    override suspend fun signup(
        email: String,
        password: String,
        nickname: String,
        marketingAgreed: Boolean,
    ): Result<AuthSession> = unused()
    override suspend fun requestPasswordReset(email: String): Result<Unit> = unused()

    private fun <T> unused(): T =
        throw UnsupportedOperationException("이 테스트는 카카오 가입만 부른다")
}
