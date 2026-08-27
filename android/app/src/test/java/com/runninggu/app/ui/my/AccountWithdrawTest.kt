package com.runninggu.app.ui.my

import com.runninggu.app.data.local.AuthTokens
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.AuthRepository
import com.runninggu.app.data.repository.AuthSession
import com.runninggu.app.data.repository.MemberRepository
import com.runninggu.app.data.repository.ReauthCredential
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 회원 탈퇴가 **재인증하고, 서버가 지운 뒤에, 기기를 정리하는가.**
 * (`POST /me/reauth` · `DELETE /me` · §2-2 · D-23 · AP-13)
 *
 * 예전에는 `delay(300)` 뒤 곧바로 로그아웃했다. **비밀번호를 받아만 놓고 쓰지 않아서 틀려도
 * 탈퇴됐고**, 서버에는 계정이 그대로 남았다. 되돌릴 수 없는 조작이라 비밀번호 변경보다
 * 나쁜 자리였다.
 *
 * 이 파일이 지키는 것은 셋이다.
 *
 * 1. **순서** — 서버가 지운 뒤에 기기를 비운다. 먼저 로그아웃하면 탈퇴가 안 된 채 세션만
 *    사라져서, 사용자는 지웠다고 믿는데 계정이 남는다(#198 KDoc · #89 와 같은 자리)
 * 2. **재인증 실패는 탈퇴가 아니다** — `401 REAUTH_FAILED` 에 세션이 지워지면 안 된다
 * 3. 세 오류가 **서로 다른 일**을 시킨다 — 다시 입력 / 처음부터 / 다른 수단
 */
class AccountWithdrawTest {

    private val dispatcher = StandardTestDispatcher()

    private val 원래프로필 = SessionProfile(
        nickname = "러너",
        email = "runner@test.com",
        loginProvider = LoginProvider.EMAIL,
        marketingAgreed = false,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        SessionStore.signIn(원래프로필, AuthTokens(accessToken = "A1", refreshToken = "R1"))
    }

    @After
    fun tearDown() {
        SessionStore.signOut()
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(member: MemberRepository): AccountViewModel =
        AccountViewModel(repository = WithdrawAuthStub(), memberRepository = member)
            .also { advanceUntilIdle() }

    @Test
    fun `재인증 토큰으로 탈퇴하고 기기를 비운다`() = runTest(dispatcher) {
        val member = FakeWithdrawRepository()
        val viewModel = viewModel(member)

        viewModel.onWithdrawOpen()
        viewModel.onWithdraw("Runner123")
        advanceUntilIdle()

        // 받은 토큰을 그대로 넘긴다 — 새로 만들거나 저장하지 않는다
        assertEquals(ReauthCredential.Password("Runner123"), member.sentCredential)
        assertEquals(FakeWithdrawRepository.REAUTH_TOKEN, member.sentReauthToken)
        assertNull(viewModel.uiState.value.withdraw)
        assertTrue(viewModel.uiState.value.signedOut)
        assertNull(SessionStore.session.value)
    }

    @Test
    fun `서버가 지운 뒤에 기기를 비운다`() = runTest(dispatcher) {
        // 순서가 이 기능의 전부다. 먼저 로그아웃하면 계정이 남은 채 세션만 사라진다
        val member = FakeWithdrawRepository()
        val viewModel = viewModel(member)

        viewModel.onWithdrawOpen()
        viewModel.onWithdraw("Runner123")
        advanceUntilIdle()

        assertTrue(
            "세션이 DELETE /me 보다 먼저 지워졌다",
            member.signedOutWhenWithdrawCalled == false,
        )
        assertNull(SessionStore.session.value)
    }

    @Test
    fun `재인증에 실패하면 탈퇴하지 않고 세션도 지우지 않는다`() = runTest(dispatcher) {
        val member = FakeWithdrawRepository(
            reauthResult = Result.failure(
                ApiException.Http(status = 401, code = ApiErrorCode.REAUTH_FAILED, problem = null),
            ),
        )
        val viewModel = viewModel(member)

        viewModel.onWithdrawOpen()
        viewModel.onWithdraw("틀린비번")
        advanceUntilIdle()

        assertEquals(0, member.withdrawCalls)
        // **로그인 상태가 유지돼야 한다.** 여기서 세션을 지우면 계정은 남고 사용자만 쫓겨난다
        assertNotNull(SessionStore.session.value)
        assertFalse(viewModel.uiState.value.signedOut)

        val edit = viewModel.uiState.value.withdraw
        assertEquals("비밀번호가 맞지 않아요", edit?.error)
        // 스낵바로 새지 않는다 — 다이얼로그가 닫히면 되돌릴 수 없는 조작을 처음부터 다시 한다
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `5분이 지나면 처음부터 다시 하라고 말한다`() = runTest(dispatcher) {
        val member = FakeWithdrawRepository(
            withdrawResult = Result.failure(
                ApiException.Http(
                    status = 401,
                    code = ApiErrorCode.INVALID_REAUTH_TOKEN,
                    problem = null,
                ),
            ),
        )
        val viewModel = viewModel(member)

        viewModel.onWithdrawOpen()
        viewModel.onWithdraw("Runner123")
        advanceUntilIdle()

        // "비밀번호가 맞지 않아요" 로 뭉뚱그리면 같은 비밀번호를 계속 다시 넣는다
        assertEquals("시간이 지났어요. 다시 시도해 주세요.", viewModel.uiState.value.withdraw?.error)
        assertNotNull(SessionStore.session.value)
    }

    @Test
    fun `가입 수단이 다르면 다른 수단으로 하라고 말한다`() = runTest(dispatcher) {
        val member = FakeWithdrawRepository(
            reauthResult = Result.failure(
                ApiException.Http(
                    status = 409,
                    code = ApiErrorCode.REAUTH_PROVIDER_MISMATCH,
                    problem = null,
                ),
            ),
        )
        val viewModel = viewModel(member)

        viewModel.onWithdrawOpen()
        viewModel.onWithdraw("Runner123")
        advanceUntilIdle()

        // 재시도가 아니라 **다른 수단**을 써야 한다 — 다시 누르라고 하면 안 된다
        assertEquals("가입할 때 쓴 방법으로 확인해 주세요", viewModel.uiState.value.withdraw?.error)
    }

    @Test
    fun `비밀번호가 비면 서버를 부르지 않는다`() = runTest(dispatcher) {
        val member = FakeWithdrawRepository()
        val viewModel = viewModel(member)

        viewModel.onWithdrawOpen()
        viewModel.onWithdraw("   ")
        advanceUntilIdle()

        assertEquals(0, member.reauthCalls)
        assertEquals("비밀번호를 입력해 주세요", viewModel.uiState.value.withdraw?.error)
    }

    @Test
    fun `보내는 중에는 닫지 못하고 두 번 나가지도 않는다`() = runTest(dispatcher) {
        val member = BlockingWithdrawRepository()
        val viewModel = viewModel(member)

        viewModel.onWithdrawOpen()
        viewModel.onWithdraw("Runner123")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.withdraw?.saving == true)

        // 되돌릴 수 없는 조작이다. 결과를 받을 자리를 없애면 안 된다
        viewModel.onWithdrawDismiss()
        assertNotNull(viewModel.uiState.value.withdraw)

        viewModel.onWithdraw("Runner123")
        advanceUntilIdle()
        assertEquals(1, member.reauthCalls)

        member.gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.signedOut)
    }
}

private class FakeWithdrawRepository(
    private val reauthResult: Result<String> = Result.success(REAUTH_TOKEN),
    private val withdrawResult: Result<Unit> = Result.success(Unit),
) : MemberRepository {
    var reauthCalls = 0
        private set
    var withdrawCalls = 0
        private set
    var sentCredential: ReauthCredential? = null
        private set
    var sentReauthToken: String? = null
        private set

    /** `DELETE /me` 를 부를 때 이미 로그아웃돼 있었나. 순서를 보는 유일한 방법이다. */
    var signedOutWhenWithdrawCalled: Boolean? = null
        private set

    override suspend fun reauth(credential: ReauthCredential): String {
        reauthCalls++
        sentCredential = credential
        return reauthResult.getOrThrow()
    }

    override suspend fun withdraw(reauthToken: String) {
        withdrawCalls++
        sentReauthToken = reauthToken
        signedOutWhenWithdrawCalled = SessionStore.session.value == null
        withdrawResult.getOrThrow()
    }

    override suspend fun updateNickname(nickname: String): SessionProfile = unused()
    override suspend fun updateMarketing(agreed: Boolean): SessionProfile = unused()
    override suspend fun updatePassword(currentPassword: String, newPassword: String): AuthTokens =
        unused()

    private fun <T> unused(): T =
        throw UnsupportedOperationException("이 테스트는 탈퇴만 부른다")

    companion object {
        const val REAUTH_TOKEN = "reauth-5min"
    }
}

/** 응답을 붙들어 둔다. "탈퇴하는 중" 상태를 실제로 만들어 보려면 필요하다. */
private class BlockingWithdrawRepository : MemberRepository {
    val gate = CompletableDeferred<Unit>()
    var reauthCalls = 0
        private set

    override suspend fun reauth(credential: ReauthCredential): String {
        reauthCalls++
        gate.await()
        return "reauth-5min"
    }

    override suspend fun withdraw(reauthToken: String) = Unit

    override suspend fun updateNickname(nickname: String): SessionProfile = unused()
    override suspend fun updateMarketing(agreed: Boolean): SessionProfile = unused()
    override suspend fun updatePassword(currentPassword: String, newPassword: String): AuthTokens =
        unused()

    private fun <T> unused(): T =
        throw UnsupportedOperationException("이 테스트는 탈퇴만 부른다")
}

/** 이 파일은 인증을 건드리지 않는다. 부르면 그 자체가 잘못이라 예외로 알린다. */
private class WithdrawAuthStub : AuthRepository {
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

    private fun <T> unused(): T = throw UnsupportedOperationException("이 테스트는 인증을 쓰지 않는다")
}
