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
 * 계정 화면이 **서버가 답한 값만** 화면에 세우는가. (이슈 #164 · API 명세 §2 · AP-14)
 *
 * 예전에는 마케팅 토글이 서버를 부르지 않고 `SessionStore` 값을 스스로 뒤집었다. 낙관적
 * 갱신조차 아니라 **앱을 지웠다 깔면 되돌아갔다.** 이 파일이 지키는 것은 두 가지다.
 *
 * 1. 보내기 전에 값이 움직이지 않는다 — 되돌릴 것이 없어야 되돌리다 틀릴 일도 없다
 * 2. `409 NICKNAME_DUPLICATED` 는 **다이얼로그를 닫지 않는다** — 고쳐야 넘어가는 오류다
 */
class AccountProfileUpdateTest {

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

    /**
     * **만들고 나서 한 번 돌린다.** `init` 의 세션 구독이 `profile` 을 채워야 토글이
     * 동작하는데, 테스트 디스패처에서는 돌려 주기 전까지 그 코루틴이 시작하지 않는다.
     * 실제 화면은 `profile` 이 null 이면 아무것도 그리지 않으므로 이 창이 없다.
     */
    private fun TestScope.viewModel(member: MemberRepository): AccountViewModel =
        AccountViewModel(repository = StubAuthRepository(), memberRepository = member)
            .also { advanceUntilIdle() }

    // ── 마케팅 동의 ──────────────────────────────────────────────

    @Test
    fun `마케팅 토글은 서버가 답한 값으로 세션을 갈아끼운다`() = runTest(dispatcher) {
        val member = FakeMemberRepository(result = Result.success(원래프로필.copy(marketingAgreed = true)))
        val viewModel = viewModel(member)

        viewModel.onToggleMarketing()
        advanceUntilIdle()

        assertEquals(true, member.sentMarketing)
        assertTrue(SessionStore.session.value?.marketingAgreed == true)
        assertEquals("마케팅 수신에 동의했어요", viewModel.uiState.value.message)
    }

    @Test
    fun `실패하면 값이 그대로다`() = runTest(dispatcher) {
        // 미리 뒤집지 않으므로 되돌릴 것이 없다 — 실패해도 세션은 처음 값 그대로다
        val member = FakeMemberRepository(result = Result.failure(ApiException.Network(java.io.IOException())))
        val viewModel = viewModel(member)

        viewModel.onToggleMarketing()
        advanceUntilIdle()

        assertFalse(SessionStore.session.value?.marketingAgreed == true)
        assertEquals(
            "설정을 바꾸지 못했어요. 잠시 후 다시 시도해 주세요.",
            viewModel.uiState.value.message,
        )
        assertFalse(viewModel.uiState.value.savingMarketing)
    }

    @Test
    fun `보내는 중에는 다시 못 누른다`() = runTest(dispatcher) {
        val member = BlockingMemberRepository(원래프로필.copy(marketingAgreed = true))
        val viewModel = viewModel(member)

        viewModel.onToggleMarketing()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.savingMarketing)

        viewModel.onToggleMarketing() // 연타
        advanceUntilIdle()

        member.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, member.calls)
    }

    @Test
    fun `세션이 바뀌면 결과를 버리되 잠금은 푼다`() = runTest(dispatcher) {
        // 왕복 중에 토큰이 만료돼 TokenAuthenticator 가 signOut 하면 세대가 오른다.
        // 결과를 그대로 쓰면 로그아웃한 세션이 되살아나고, 그냥 빠져나가면 스위치가
        // 잠긴 채 남는다 — 둘 다 피해야 한다.
        val member = BlockingMemberRepository(원래프로필.copy(marketingAgreed = true))
        val viewModel = viewModel(member)

        viewModel.onToggleMarketing()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.savingMarketing)

        SessionStore.signOut()
        member.gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.savingMarketing)
        assertNull(SessionStore.session.value) // 되살아나지 않았다
    }

    // ── 닉네임 ──────────────────────────────────────────────────

    @Test
    fun `닉네임을 바꾸면 다이얼로그가 닫힌다`() = runTest(dispatcher) {
        val member = FakeMemberRepository(result = Result.success(원래프로필.copy(nickname = "새이름")))
        val viewModel = viewModel(member)

        viewModel.onNicknameEditOpen()
        viewModel.onNicknameChange("새이름")
        advanceUntilIdle()

        assertEquals("새이름", member.sentNickname)
        assertNull(viewModel.uiState.value.nicknameEdit)
        assertEquals("새이름", SessionStore.session.value?.nickname)
    }

    @Test
    fun `중복 닉네임은 다이얼로그를 닫지 않고 안에 알린다`() = runTest(dispatcher) {
        val member = FakeMemberRepository(
            result = Result.failure(
                ApiException.Http(status = 409, code = ApiErrorCode.NICKNAME_DUPLICATED, problem = null),
            ),
        )
        val viewModel = viewModel(member)

        viewModel.onNicknameEditOpen()
        viewModel.onNicknameChange("겹치는이름")
        advanceUntilIdle()

        val edit = viewModel.uiState.value.nicknameEdit
        assertEquals("이미 쓰고 있는 닉네임이에요", edit?.error)
        assertFalse(edit?.saving == true)
        // 스낵바로 새지 않는다 — 다이얼로그 뒤에 뜨면 읽을 수 없다
        assertNull(viewModel.uiState.value.message)
        assertEquals("러너", SessionStore.session.value?.nickname)
    }

    @Test
    fun `길이 규칙은 서버를 부르기 전에 거른다`() = runTest(dispatcher) {
        val member = FakeMemberRepository(result = Result.success(원래프로필))
        val viewModel = viewModel(member)

        viewModel.onNicknameEditOpen()
        viewModel.onNicknameChange("가")
        advanceUntilIdle()

        assertEquals(0, member.calls)
        assertEquals("닉네임은 2~12자로 지어 주세요", viewModel.uiState.value.nicknameEdit?.error)
    }

    @Test
    fun `보내는 중에는 다이얼로그가 닫히지 않는다`() = runTest(dispatcher) {
        val member = BlockingMemberRepository(원래프로필.copy(nickname = "새이름"))
        val viewModel = viewModel(member)

        viewModel.onNicknameEditOpen()
        viewModel.onNicknameChange("새이름")
        advanceUntilIdle()

        viewModel.onNicknameEditDismiss() // 취소를 눌러도
        assertTrue(viewModel.uiState.value.nicknameEdit?.saving == true)

        member.gate.complete(Unit)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.nicknameEdit)
    }
}

// ── 가짜 ────────────────────────────────────────────────────────

private class FakeMemberRepository(private val result: Result<SessionProfile>) : MemberRepository {
    var calls = 0
        private set
    var sentNickname: String? = null
        private set
    var sentMarketing: Boolean? = null
        private set

    override suspend fun updateNickname(nickname: String): SessionProfile {
        calls++
        sentNickname = nickname
        return result.getOrThrow()
    }

    override suspend fun updateMarketing(agreed: Boolean): SessionProfile {
        calls++
        sentMarketing = agreed
        return result.getOrThrow()
    }
}

/** 응답을 붙들어 둔다. "보내는 중" 상태를 실제로 만들어 보려면 필요하다. */
private class BlockingMemberRepository(private val profile: SessionProfile) : MemberRepository {
    val gate = CompletableDeferred<Unit>()
    var calls = 0
        private set

    override suspend fun updateNickname(nickname: String): SessionProfile {
        calls++
        gate.await()
        return profile
    }

    override suspend fun updateMarketing(agreed: Boolean): SessionProfile {
        calls++
        gate.await()
        return profile
    }
}

/** 이 파일은 인증을 건드리지 않는다. 부르면 그 자체가 잘못이라 예외로 알린다. */
private class StubAuthRepository : AuthRepository {
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
