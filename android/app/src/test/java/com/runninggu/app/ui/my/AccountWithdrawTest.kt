package com.runninggu.app.ui.my

import com.runninggu.app.data.local.AuthTokens
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.PersistedSession
import com.runninggu.app.data.local.SessionPersistence
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
        // `bind` 로 넣은 persistence 까지 비운다. SessionStore 는 싱글턴이라 안 지우면
        // 다음 테스트가 이 테스트의 저장소를 물려받는다
        SessionStore.resetForTest()
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(member: MemberRepository): AccountViewModel =
        AccountViewModel(repository = WithdrawAuthStub(), memberRepository = member)
            .also { advanceUntilIdle() }

    // ── 카카오 가입자 (§2-2 · #237 리뷰) ────────────────────────────

    @Test
    fun `카카오도 같은 순서로 탈퇴한다`() = runTest(dispatcher) {
        // 개인정보 문안이 "탈퇴할 수 있습니다" 라고 적는데 카카오 회원만 못 하면
        // 문안이 못 지키는 약속을 하게 된다(#237 리뷰). 수단만 다르고 절차는 같다.
        //
        // **프로필을 KAKAO 로 바꾸지 않는다.** ViewModel 은 `loginProvider` 로 갈라지지
        // 않고 **어떤 자격을 받았는지**로만 움직인다 — 가르는 것은 화면이다. 여기서
        // `signIn` 을 다시 부르면 `setUp` 의 것과 겹쳐 Main dispatcher 가 오염되고
        // 뒤에 도는 다른 테스트가 깨진다
        val member = FakeWithdrawRepository()
        val viewModel = viewModel(member)

        viewModel.onWithdrawOpen()
        viewModel.onWithdrawWithKakaoToken("kakao-fresh-token")
        advanceUntilIdle()

        // **SDK 가 방금 발급한 토큰**을 그대로 넘긴다 (§2-2)
        assertEquals(ReauthCredential.Kakao("kakao-fresh-token"), member.sentCredential)
        assertEquals(FakeWithdrawRepository.REAUTH_TOKEN, member.sentReauthToken)
        assertTrue(
            "세션이 DELETE /me 보다 먼저 지워졌다",
            member.signedOutWhenWithdrawCalled == false,
        )
        assertNull(viewModel.uiState.value.withdraw)
        assertTrue(viewModel.uiState.value.signedOut)
        assertNull(SessionStore.session.value)
    }

    @Test
    fun `카카오 재인증이 실패하면 계정은 그대로 남는다`() = runTest(dispatcher) {
        val member = FakeWithdrawRepository(
            reauthResult = Result.failure(
                ApiException.Http(status = 401, code = ApiErrorCode.REAUTH_FAILED, problem = null),
            ),
        )
        val viewModel = viewModel(member)

        viewModel.onWithdrawOpen()
        viewModel.onWithdrawWithKakaoToken("kakao-stale-token")
        advanceUntilIdle()

        // 되돌릴 수 없는 조작이라 **어중간하게 끝나면 안 된다** — 서버도 기기도 그대로다
        assertEquals(0, member.withdrawCalls)
        assertNotNull("세션이 남아 있어야 한다", SessionStore.session.value)
        assertNotNull(viewModel.uiState.value.withdraw?.error)
    }

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
    fun `기기 정리가 실패하면 재인증을 되풀이하지 않고 정리만 다시 한다`() = runTest(dispatcher) {
        // 여기가 이 파일에서 제일 위험한 자리다. `DELETE /me` 는 이미 204 로 끝났으므로
        // 계정이 없다. 이때 다시 `reauth` 를 부르면 401 이라 **기기에 남은 토큰을 영영
        // 못 지운다** — 다음 실행에 되살아날 수도 있다 (#212 리뷰).
        val persistence = FailingOncePersistence()
        SessionStore.bind(persistence, backgroundScope)
        advanceUntilIdle()
        SessionStore.signIn(원래프로필, AuthTokens(accessToken = "A1", refreshToken = "R1"))

        val member = FakeWithdrawRepository()
        val viewModel = viewModel(member)

        viewModel.onWithdrawOpen()
        viewModel.onWithdraw("Runner123")
        advanceUntilIdle()

        // 첫 정리는 실패했다. 계정은 지워졌으니 그렇게 말하고 다이얼로그를 **열어 둔다**
        val failed = viewModel.uiState.value.withdraw
        assertNotNull(failed)
        assertTrue(failed?.serverDone == true)
        assertEquals(
            "계정은 삭제됐어요. 기기에 남은 정보를 지우지 못했으니 다시 시도해 주세요.",
            failed?.error,
        )
        assertFalse(viewModel.uiState.value.signedOut)

        // 바깥을 눌러도 닫히지 않는다. 나가려면 [나중에] 를 눌러야 한다(아래 테스트)
        viewModel.onWithdrawDismiss()
        assertNotNull(viewModel.uiState.value.withdraw)

        // 다시 누른다. **서버는 건드리지 않는다**
        viewModel.onWithdraw("Runner123")
        advanceUntilIdle()

        assertEquals("reauth 를 다시 불렀다", 1, member.reauthCalls)
        assertEquals("DELETE /me 를 다시 불렀다", 1, member.withdrawCalls)
        assertNull(viewModel.uiState.value.withdraw)
        assertTrue(viewModel.uiState.value.signedOut)
        assertNull(SessionStore.session.value)
        assertEquals("디스크를 못 비웠다", 2, persistence.clearCalls)
    }

    /**
     * [나중에] 를 눌렀을 때. (#212 리뷰 — @M1nnnji)
     *
     * 리뷰 전에는 `serverDone` 이면 **아무 데로도 못 나갔다.** 근거를 "닫으면 기기 정리를
     * 다시 할 길이 없다" 로 적었는데, 실제로는 다음 실행에 `401` → `onGiveUp` →
     * `SessionStore.signOut` 이 정리한다. **가둘 만큼의 근거가 아니었다.**
     *
     * 게다가 정리가 계속 실패하는 것은 보통 저장소 쓰기 실패라, 같은 자리에서 다시 눌러도
     * 같은 결과다. 모달에 붙잡아 두고 얻는 것이 없다.
     *
     * **여기서 중요한 것은 [나중에] 가 "취소" 가 아니라는 점이다.** 서버 계정은 이미 없다.
     * 로그인 상태로 남겨 두면 어느 화면을 열어도 `401` 만 본다 — 그래서 메모리를 비우고
     * 게스트로 내보낸다. 디스크에 남는 것은 **죽은 토큰**이라 되살아날 계정이 없다.
     */
    @Test
    fun `나중에를 누르면 게스트로 나가고 디스크 삭제는 예약으로 남는다`() = runTest(dispatcher) {
        // 계속 실패하는 저장소다. 다시 눌러도 소용없는 상황을 만든다
        val persistence = AlwaysFailingPersistence()
        SessionStore.bind(persistence, backgroundScope)
        advanceUntilIdle()
        SessionStore.signIn(원래프로필, AuthTokens(accessToken = "A1", refreshToken = "R1"))

        val member = FakeWithdrawRepository()
        val viewModel = viewModel(member)

        viewModel.onWithdrawOpen()
        viewModel.onWithdraw("Runner123")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.withdraw?.serverDone == true)

        // 한 번 더 눌러도 같은 자리다 — 여기가 [나중에] 가 필요한 이유다
        viewModel.onWithdraw("Runner123")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.withdraw?.serverDone == true)
        assertFalse(viewModel.uiState.value.signedOut)

        viewModel.onWithdrawGiveUp()
        advanceUntilIdle()

        // 다이얼로그가 닫히고 **게스트로 나간다**
        assertNull(viewModel.uiState.value.withdraw)
        assertTrue(viewModel.uiState.value.signedOut)
        assertNull(SessionStore.session.value)
        assertNull(SessionStore.tokens)
        // 서버는 끝까지 한 번씩만 불렀다
        assertEquals(1, member.reauthCalls)
        assertEquals(1, member.withdrawCalls)
    }

    @Test
    fun `서버 탈퇴 전에는 나중에가 없다`() = runTest(dispatcher) {
        // `serverDone` 이 아닌데 나가 버리면 **계정이 남은 채 로그아웃된다** — 사용자는
        // 지웠다고 믿는다. 이 갈래가 없으면 [취소] 와 구별이 사라진다
        val member = FakeWithdrawRepository()
        val viewModel = viewModel(member)

        viewModel.onWithdrawOpen()
        viewModel.onWithdrawGiveUp()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.withdraw)
        assertFalse(viewModel.uiState.value.signedOut)
        assertNotNull(SessionStore.session.value)
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

/** 첫 `clear()` 만 실패한다. 기기 정리 재시도를 실제로 만들어 보려면 필요하다. */
private class FailingOncePersistence : SessionPersistence {
    var clearCalls = 0
        private set

    override suspend fun load(): PersistedSession? = null
    override suspend fun save(session: PersistedSession) = Unit

    override suspend fun clear() {
        clearCalls++
        if (clearCalls == 1) throw java.io.IOException("디스크를 못 비웠다")
    }
}

/** 몇 번을 눌러도 안 지워진다. [나중에] 가 필요한 상황을 만든다. */
private class AlwaysFailingPersistence : SessionPersistence {
    override suspend fun load(): PersistedSession? = null
    override suspend fun save(session: PersistedSession) = Unit
    override suspend fun clear(): Unit = throw java.io.IOException("디스크를 못 비웠다")
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
        ageOver14: Boolean,
    ): Result<AuthSession> = unused()
    override suspend fun requestPasswordReset(email: String): Result<Unit> = unused()

    private fun <T> unused(): T = throw UnsupportedOperationException("이 테스트는 인증을 쓰지 않는다")
}
