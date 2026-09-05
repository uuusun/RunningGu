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
 * 비밀번호 변경이 **서버를 보고, 새 토큰을 넣고, 틀린 입력을 고칠 자리를 남기는가.**
 * (`PUT /me/password` · API 명세 §2-1 · D-28 · AP-13)
 *
 * 예전에는 `delay(300)` 후 무조건 성공 문구를 띄웠다. **현재 비밀번호가 틀려도 "바꿨어요"
 * 가 떴다.**
 *
 * 이 파일이 지키는 것은 셋이다.
 *
 * 1. **새 token pair 를 넣는다.** 서버가 기존 refresh 를 전부 revoke 하므로, 안 넣으면
 *    방금 비밀번호를 바꾼 사용자가 다음 재발급에서 로그아웃된다
 * 2. `400 CURRENT_PASSWORD_MISMATCH` 는 **다이얼로그를 닫지 않는다** — 위 칸을 고쳐야
 *    넘어가는 오류다. 닫고 스낵바로 알리면 두 칸을 처음부터 입력해야 한다(이슈 #164)
 * 3. 형식 위반은 **서버를 부르기 전에** 거른다 — 왕복할 이유가 없다
 */
class AccountPasswordChangeTest {

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
        AccountViewModel(repository = PasswordChangeAuthStub(), memberRepository = member)
            .also { advanceUntilIdle() }

    @Test
    fun `성공하면 새 토큰 두 개를 갈아끼우고 다이얼로그를 닫는다`() = runTest(dispatcher) {
        val member = FakePasswordRepository(
            result = Result.success(AuthTokens(accessToken = "A2", refreshToken = "R2")),
        )
        val viewModel = viewModel(member)

        viewModel.onPasswordEditOpen()
        viewModel.onChangePassword("Old12345", "New12345")
        advanceUntilIdle()

        assertEquals("Old12345" to "New12345", member.sent)
        assertNull(viewModel.uiState.value.passwordEdit)
        // 하나만 넣으면 다음 재발급이 실패한다. **둘 다** 봐야 한다 (§2-1)
        assertEquals("A2", SessionStore.tokens?.accessToken)
        assertEquals("R2", SessionStore.tokens?.refreshToken)
    }

    @Test
    fun `현재 비밀번호가 틀리면 닫지 않고 안에 알린다`() = runTest(dispatcher) {
        val member = FakePasswordRepository(
            result = Result.failure(
                ApiException.Http(
                    status = 400,
                    code = ApiErrorCode.CURRENT_PASSWORD_MISMATCH,
                    problem = null,
                ),
            ),
        )
        val viewModel = viewModel(member)

        viewModel.onPasswordEditOpen()
        viewModel.onChangePassword("Wrong123", "New12345")
        advanceUntilIdle()

        val edit = viewModel.uiState.value.passwordEdit
        assertEquals("현재 비밀번호가 맞지 않아요", edit?.error)
        assertFalse(edit?.saving == true)
        // 스낵바로 새지 않는다 — 다이얼로그 뒤에 뜨면 읽을 수 없다
        assertNull(viewModel.uiState.value.message)
        // 실패에 토큰이 움직이면 안 된다
        assertEquals("A1", SessionStore.tokens?.accessToken)
    }

    @Test
    fun `형식 위반과 현재 비밀번호 오류는 문구가 다르다`() = runTest(dispatcher) {
        val member = FakePasswordRepository(
            result = Result.failure(
                ApiException.Http(status = 400, code = ApiErrorCode.INVALID_PASSWORD, problem = null),
            ),
        )
        val viewModel = viewModel(member)

        viewModel.onPasswordEditOpen()
        viewModel.onChangePassword("Old12345", "New12345")
        advanceUntilIdle()

        // 사용자가 할 일이 다르다 — 위 칸이 아니라 아래 칸을 고쳐야 한다
        assertEquals(
            "새 비밀번호는 8자 이상, 영문과 숫자를 함께 써 주세요",
            viewModel.uiState.value.passwordEdit?.error,
        )
    }

    @Test
    fun `형식이 틀리면 서버를 부르지 않는다`() = runTest(dispatcher) {
        val member = FakePasswordRepository(result = Result.success(AuthTokens("A2", "R2")))
        val viewModel = viewModel(member)

        viewModel.onPasswordEditOpen()
        viewModel.onChangePassword("Old12345", "짧음")
        advanceUntilIdle()

        assertEquals(0, member.calls)
        assertNotNull(viewModel.uiState.value.passwordEdit?.error)
    }

    @Test
    fun `같은 비밀번호로는 바꾸지 않는다`() = runTest(dispatcher) {
        val member = FakePasswordRepository(result = Result.success(AuthTokens("A2", "R2")))
        val viewModel = viewModel(member)

        viewModel.onPasswordEditOpen()
        viewModel.onChangePassword("Same12345", "Same12345")
        advanceUntilIdle()

        assertEquals(0, member.calls)
        assertEquals("지금 쓰는 비밀번호와 달라야 해요", viewModel.uiState.value.passwordEdit?.error)
    }

    @Test
    fun `보내는 중에는 닫지 못한다`() = runTest(dispatcher) {
        val member = BlockingPasswordRepository()
        val viewModel = viewModel(member)

        viewModel.onPasswordEditOpen()
        viewModel.onChangePassword("Old12345", "New12345")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.passwordEdit?.saving == true)

        // 여기서 닫히면 결과를 받을 자리가 없어진다
        viewModel.onPasswordEditDismiss()
        assertNotNull(viewModel.uiState.value.passwordEdit)

        member.gate.complete(Unit)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.passwordEdit)
    }

    @Test
    fun `보내는 중에 또 눌러도 한 번만 나간다`() = runTest(dispatcher) {
        val member = BlockingPasswordRepository()
        val viewModel = viewModel(member)

        viewModel.onPasswordEditOpen()
        viewModel.onChangePassword("Old12345", "New12345")
        advanceUntilIdle()
        viewModel.onChangePassword("Old12345", "New12345")
        advanceUntilIdle()

        assertEquals(1, member.calls)
        member.gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `세션이 바뀐 뒤 도착한 응답은 토큰을 건드리지 않는다`() = runTest(dispatcher) {
        val member = BlockingPasswordRepository()
        val viewModel = viewModel(member)

        viewModel.onPasswordEditOpen()
        viewModel.onChangePassword("Old12345", "New12345")
        advanceUntilIdle()

        // 보내는 사이에 로그아웃 → 다른 계정으로 로그인했다
        SessionStore.signOut()
        SessionStore.signIn(원래프로필, AuthTokens(accessToken = "B1", refreshToken = "S1"))

        member.gate.complete(Unit)
        advanceUntilIdle()

        // 남의 결과다. 새 세션의 토큰을 덮으면 방금 로그인한 사용자가 튕긴다
        assertEquals("B1", SessionStore.tokens?.accessToken)
        assertEquals("S1", SessionStore.tokens?.refreshToken)
        assertNull(viewModel.uiState.value.passwordEdit)
        assertNull(viewModel.uiState.value.message)
    }
}

private class FakePasswordRepository(private val result: Result<AuthTokens>) : MemberRepository {
    override suspend fun me(): SessionProfile = error("안 쓴다")

    var calls = 0
        private set
    var sent: Pair<String, String>? = null
        private set

    override suspend fun updatePassword(currentPassword: String, newPassword: String): AuthTokens {
        calls++
        sent = currentPassword to newPassword
        return result.getOrThrow()
    }

    override suspend fun updateNickname(nickname: String): SessionProfile = unused()
    override suspend fun updateMarketing(agreed: Boolean): SessionProfile = unused()
    // #198 이 MemberRepository 에 더한 것. 이 파일은 탈퇴를 부르지 않는다
    override suspend fun reauth(credential: ReauthCredential): String = unused()
    override suspend fun withdraw(reauthToken: String) = unused<Unit>()

    private fun <T> unused(): T =
        throw UnsupportedOperationException("이 테스트는 비밀번호 변경만 부른다")
}

/** 응답을 붙들어 둔다. "바꾸는 중" 상태를 실제로 만들어 보려면 필요하다. */
private class BlockingPasswordRepository : MemberRepository {
    override suspend fun me(): SessionProfile = error("안 쓴다")

    val gate = CompletableDeferred<Unit>()
    var calls = 0
        private set

    override suspend fun updatePassword(currentPassword: String, newPassword: String): AuthTokens {
        calls++
        gate.await()
        return AuthTokens(accessToken = "A2", refreshToken = "R2")
    }

    override suspend fun updateNickname(nickname: String): SessionProfile = unused()
    override suspend fun updateMarketing(agreed: Boolean): SessionProfile = unused()
    // #198 이 MemberRepository 에 더한 것. 이 파일은 탈퇴를 부르지 않는다
    override suspend fun reauth(credential: ReauthCredential): String = unused()
    override suspend fun withdraw(reauthToken: String) = unused<Unit>()

    private fun <T> unused(): T =
        throw UnsupportedOperationException("이 테스트는 비밀번호 변경만 부른다")
}

/** 이 파일은 인증을 건드리지 않는다. 부르면 그 자체가 잘못이라 예외로 알린다. */
private class PasswordChangeAuthStub : AuthRepository {
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

    private fun <T> unused(): T = throw UnsupportedOperationException("이 테스트는 인증을 쓰지 않는다")
}
