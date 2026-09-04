package com.runninggu.app.ui.auth

import com.runninggu.app.ui.OFFLINE
import com.runninggu.app.data.local.AuthTokens
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.AuthRepository
import com.runninggu.app.data.repository.AuthSession
import com.runninggu.app.data.repository.KakaoLoginOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 카카오 로그인이 **두 결말을 어디로 보내는가.** (SPEC §4.1 · API 명세 §1-7 · AP-08)
 *
 * 서버가 한 `200` 으로 기존 가입자와 미가입자를 함께 돌려주므로(§1-7), 화면이 그 둘을
 * 서로 다른 곳으로 보내야 한다. **잘못 갈라지면 미가입자가 세션 없이 홈으로 간다** —
 * 로그인은 된 것처럼 보이는데 요청마다 401 이 나는, 원인을 찾기 제일 어려운 상태다.
 *
 * SDK 에서 토큰을 받는 부분은 기기가 있어야 하므로 여기서 보지 않는다. 이 파일이 고정하는
 * 것은 **토큰을 받은 다음**이다.
 */
class KakaoLoginBranchTest {

    private val dispatcher = StandardTestDispatcher()

    private val 카카오프로필 = SessionProfile(
        nickname = "카카오러너",
        email = null,
        loginProvider = LoginProvider.KAKAO,
        marketingAgreed = false,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        SessionStore.resetForTest()
        Dispatchers.resetMain()
    }

    @Test
    fun `기존 가입자는 세션을 세우고 홈으로 간다`() = runTest(dispatcher) {
        val session = AuthSession(
            tokens = AuthTokens(accessToken = "A1", refreshToken = "R1"),
            profile = 카카오프로필,
        )
        val viewModel = LoginViewModel(
            FakeKakaoRepository(Result.success(KakaoLoginOutcome.Session(session))),
        )

        viewModel.onKakaoToken("kakao-token")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.loggedIn)
        // 가입 화면으로 새지 않는다
        assertNull(viewModel.uiState.value.kakaoSignup)
        assertEquals("카카오러너", SessionStore.session.value?.nickname)
        assertEquals("A1", SessionStore.tokens?.accessToken)
    }

    @Test
    fun `미가입자는 세션 없이 가입 화면으로 간다`() = runTest(dispatcher) {
        val viewModel = LoginViewModel(
            FakeKakaoRepository(
                Result.success(
                    KakaoLoginOutcome.NewUser(
                        kakaoAccessToken = "kakao-token",
                        nickname = "카카오이름",
                        email = "kakao@example.com",
                    ),
                ),
            ),
        )

        viewModel.onKakaoToken("kakao-token")
        advanceUntilIdle()

        val handoff = viewModel.uiState.value.kakaoSignup
        assertNotNull(handoff)
        // **토큰을 그대로 들고 간다** — A2 의 kakaoSignup 이 같은 토큰을 다시 요구한다
        assertEquals("kakao-token", handoff?.kakaoAccessToken)
        assertEquals("카카오이름", handoff?.nickname)
        assertEquals("kakao@example.com", handoff?.email)

        // 여기서 세션이 서면 미가입자가 홈으로 가고 요청마다 401 이 난다
        assertFalse(viewModel.uiState.value.loggedIn)
        assertNull(SessionStore.session.value)
    }

    @Test
    fun `카카오가 프로필을 안 줘도 가입 화면으로 간다`() = runTest(dispatcher) {
        // 동의 항목에 따라 닉네임도 이메일도 안 온다(§1-7). 그래도 가입은 이어져야 한다
        val viewModel = LoginViewModel(
            FakeKakaoRepository(
                Result.success(
                    KakaoLoginOutcome.NewUser(
                        kakaoAccessToken = "kakao-token",
                        nickname = null,
                        email = null,
                    ),
                ),
            ),
        )

        viewModel.onKakaoToken("kakao-token")
        advanceUntilIdle()

        val handoff = viewModel.uiState.value.kakaoSignup
        assertNotNull(handoff)
        assertNull(handoff?.nickname)
        assertNull(handoff?.email)
    }

    @Test
    fun `가입 화면으로 보내고 나면 비운다`() = runTest(dispatcher) {
        val viewModel = LoginViewModel(
            FakeKakaoRepository(
                Result.success(
                    KakaoLoginOutcome.NewUser("kakao-token", nickname = null, email = null),
                ),
            ),
        )

        viewModel.onKakaoToken("kakao-token")
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.kakaoSignup)

        // 안 비우면 A2 에서 돌아왔을 때 또 넘어간다 — 뒤로가기가 안 먹는 것처럼 보인다
        viewModel.onKakaoSignupHandled()
        assertNull(viewModel.uiState.value.kakaoSignup)
    }

    @Test
    fun `네트워크 실패와 그 밖의 실패는 문구가 다르다`() = runTest(dispatcher) {
        val network = LoginViewModel(
            FakeKakaoRepository(Result.failure(ApiException.Network(java.io.IOException()))),
        )
        network.onKakaoToken("kakao-token")
        advanceUntilIdle()
        assertEquals(OFFLINE, network.uiState.value.errorMessage)

        val rejected = LoginViewModel(
            FakeKakaoRepository(
                Result.failure(
                    ApiException.Http(
                        status = 401,
                        code = ApiErrorCode.INVALID_KAKAO_TOKEN,
                        problem = null,
                    ),
                ),
            ),
        )
        rejected.onKakaoToken("kakao-token")
        advanceUntilIdle()
        assertEquals(
            "카카오 로그인에 실패했어요. 잠시 후 다시 시도해 주세요.",
            rejected.uiState.value.errorMessage,
        )
        // 실패에 세션이 서면 안 된다
        assertNull(SessionStore.session.value)
    }
}

private class FakeKakaoRepository(
    private val result: Result<KakaoLoginOutcome>,
) : AuthRepository {
    override suspend fun kakaoLogin(kakaoAccessToken: String): Result<KakaoLoginOutcome> = result

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
        ageOver14: Boolean,
    ): Result<AuthSession> = unused()
    override suspend fun requestPasswordReset(email: String): Result<Unit> = unused()

    private fun <T> unused(): T =
        throw UnsupportedOperationException("이 테스트는 카카오 로그인만 부른다")
}
