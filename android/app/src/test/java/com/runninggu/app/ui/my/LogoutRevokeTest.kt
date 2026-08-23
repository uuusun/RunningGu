package com.runninggu.app.ui.my

import com.runninggu.app.data.local.AuthTokens
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.local.SessionStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 로그아웃이 **서버에서 먼저 revoke** 하는가. (API 명세 §1-10 · 이슈 #113)
 *
 * 이 파일이 지키는 것은 하나다 — **서버에 못 닿았으면 기기 토큰을 남긴다.**
 *
 * 기기만 비우면 리프레시 토큰이 사라져 revoke 할 자격을 잃는다. 그러면 서버에는 쓸 수
 * 있는 세션이 남는데 **사용자는 로그아웃했다고 믿는다.** 남의 기기에서 계정이 살아 있는
 * 쪽이라, 실패했을 때는 로그인 상태를 유지하고 다시 시도하게 한다.
 */
class LogoutRevokeTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        SessionStore.signIn(
            SessionProfile("러너", "runner@test.com", LoginProvider.EMAIL),
            AuthTokens(accessToken = "A1", refreshToken = "R1"),
        )
    }

    @After
    fun tearDown() {
        SessionStore.signOut()
        Dispatchers.resetMain()
    }

    @Test
    fun `로그아웃은 서버에 지금 리프레시를 넘긴다`() = runTest(dispatcher) {
        val repository = RecordingAuthRepository()

        AccountViewModel(repository).onLogout()
        advanceUntilIdle()

        // 옛 토큰을 보내면 서버는 멱등 204 를 주고 **지금 세션은 살아남는다**
        assertEquals("R1", repository.lastRefreshToken)
    }

    @Test
    fun `서버가 성공하면 기기에서도 지운다`() = runTest(dispatcher) {
        val viewModel = AccountViewModel(RecordingAuthRepository())

        viewModel.onLogout()
        advanceUntilIdle()

        assertNull(SessionStore.tokens)
        assertTrue(viewModel.uiState.value.signedOut)
    }

    @Test
    fun `서버에 못 닿으면 기기 토큰을 남긴다`() = runTest(dispatcher) {
        val viewModel = AccountViewModel(
            RecordingAuthRepository(failure = ApiException.Network(java.io.IOException("끊김"))),
        )

        viewModel.onLogout()
        advanceUntilIdle()

        // 이 단언이 이 파일의 전부다 — 여기서 지우면 서버 세션이 살아남는다
        assertNotNull("기기 토큰이 지워졌다", SessionStore.tokens)
        assertFalse("로그아웃된 것으로 넘어갔다", viewModel.uiState.value.signedOut)
        assertNotNull("재시도 안내가 없다", viewModel.uiState.value.message)
    }

    @Test
    fun `지울 토큰이 없으면 서버를 부르지 않는다`() = runTest(dispatcher) {
        // 게스트이거나 이미 정리된 상태다. 물어볼 자격 자체가 없다.
        SessionStore.signOut()
        val repository = RecordingAuthRepository()

        AccountViewModel(repository).onLogout()
        advanceUntilIdle()

        assertNull(repository.lastRefreshToken)
    }
}

private class RecordingAuthRepository(
    private val failure: ApiException? = null,
) : AuthRepository {

    var lastRefreshToken: String? = null
        private set

    override suspend fun logout(refreshToken: String): Result<Unit> {
        lastRefreshToken = refreshToken
        return failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

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

    private fun <T> unused(): T = throw UnsupportedOperationException("계정 관리는 부르지 않는다")
}
