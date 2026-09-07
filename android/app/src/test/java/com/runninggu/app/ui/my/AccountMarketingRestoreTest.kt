package com.runninggu.app.ui.my

import com.runninggu.app.data.local.AuthTokens
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.local.SessionStore
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * 재로그인해도 마케팅 동의가 서버 값으로 보이는가. (이슈 #287 · API 명세 §2 · SPEC §4.13)
 *
 * ## 무엇이 잘못됐었나
 *
 * `POST /auth/login` 응답의 `user` 는 닉네임·이메일·가입수단만 있는 **요약**이라 약관이
 * 없다(§1-5 ~ §1-7). 그런데 `SessionProfile.marketingAgreed` 의 기본값이 `false` 여서,
 * **안 물어본 값이 "동의 안 함" 으로 확정**됐다. ON 으로 저장하고 재로그인하면 화면이
 * OFF 를 보여 주고, 사용자가 켜려고 누르면 그건 **철회 요청**이 된다.
 *
 * 고친 방향은 값을 채우는 것이 아니라 **모른다고 말할 수 있게 한 것**이다 — `null` 이
 * "아직 안 물었다" 이고, `GET /me` 가 돌아와야 값이 선다.
 *
 * ## 망가뜨리면 이것만 실패한다
 *
 * 실제로 돌려 보고 적는다(2026-09-05).
 *
 * ```
 * init 의 refreshProfile() 호출을 뺀다
 *   → 모르는_값은_서버에_물어_채운다        FAILED
 *     서버가_OFF_라고_하면_OFF_로_선다      FAILED   (둘 다 조회로 채워지는 값이다)
 *
 * refreshProfile 의 세대 비교(epoch)를 무력화한다
 *   → 늦게_도착한_응답은_바뀐_계정을_덮지_않는다   FAILED
 *
 * onToggleMarketing 의 `?: return` 을 뺀다
 *   → 모르는_동안에는_토글이_요청을_보내지_않는다   FAILED
 * ```
 *
 * `SessionProfile.marketingAgreed` 를 `Boolean = false` 로 되돌리는 변형은 **컴파일부터
 * 깨져서** 이 형식으로 적을 수 없다 — `재로그인_직후에는_동의값을_모른다` 의 `assertNull`
 * 이 non-null 타입을 받게 된다. 타입이 막아 주는 자리라 테스트가 대신 지킬 필요가 없다.
 */
class AccountMarketingRestoreTest {

    private val dispatcher = StandardTestDispatcher()

    /** 로그인 응답이 만드는 모양. **약관이 없어서 `marketingAgreed` 가 null 이다.** */
    private val 로그인직후 = SessionProfile(
        nickname = "러너",
        email = "runner@test.com",
        loginProvider = LoginProvider.EMAIL,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        SessionStore.signOut()
        Dispatchers.resetMain()
    }

    private fun 로그인(profile: SessionProfile = 로그인직후) {
        SessionStore.signIn(profile, AuthTokens(accessToken = "A1", refreshToken = "R1"))
    }

    private fun TestScope.viewModel(member: MemberRepository): AccountViewModel =
        AccountViewModel(repository = MarketingStubAuthRepository(), memberRepository = member)
            .also { advanceUntilIdle() }

    // ── 모른다는 것을 모른다고 말한다 ────────────────────────────

    @Test
    fun `재로그인_직후에는_동의값을_모른다`() {
        // 매퍼가 채우지 않는 자리다. 여기가 false 로 굳으면 화면이 서버와 다른 말을 한다
        assertNull(로그인직후.marketingAgreed)
    }

    @Test
    fun `조회_전에는_스위치를_잠근다`() = runTest(dispatcher) {
        로그인()
        val member = FakeMeRepository(me = CompletableDeferred())   // 응답을 안 준다
        val viewModel = viewModel(member)

        val state = viewModel.uiState.value
        assertFalse("값을 모르는 동안에는 켜졌다고 말하지 않는다", state.marketingKnown)
        // marketingAgreed 는 false 지만 그건 "꺼짐" 이 아니라 "모름" 이다 — 화면은
        // marketingKnown 을 보고 잠그므로 사용자가 이 false 를 누를 수 없다
        assertFalse(state.marketingAgreed)
    }

    // ── 서버에 물어서 채운다 ──────────────────────────────────────

    @Test
    fun `모르는_값은_서버에_물어_채운다`() = runTest(dispatcher) {
        로그인()
        val member = FakeMeRepository(result = Result.success(로그인직후.copy(marketingAgreed = true)))
        val viewModel = viewModel(member)

        assertEquals("GET /me 를 한 번 부른다", 1, member.meCalls)
        assertTrue("서버가 ON 이면 화면도 ON", viewModel.uiState.value.marketingAgreed)
        assertTrue(viewModel.uiState.value.marketingKnown)
        assertEquals(true, SessionStore.session.value?.marketingAgreed)
    }

    @Test
    fun `서버가_OFF_라고_하면_OFF_로_선다`() = runTest(dispatcher) {
        로그인()
        val member = FakeMeRepository(result = Result.success(로그인직후.copy(marketingAgreed = false)))
        val viewModel = viewModel(member)

        assertFalse(viewModel.uiState.value.marketingAgreed)
        // **모르는 것과 다르다.** 이게 서면 스위치가 열리고 사용자가 켤 수 있다
        assertTrue(viewModel.uiState.value.marketingKnown)
    }

    @Test
    fun `이미_아는_값이면_다시_묻지_않는다`() = runTest(dispatcher) {
        // 시작 시 세션 검증(ApiSessionValidator)이 이미 채워 둔 경우다
        로그인(로그인직후.copy(marketingAgreed = true))
        val member = FakeMeRepository(result = Result.success(로그인직후.copy(marketingAgreed = true)))
        viewModel(member)

        assertEquals("아는 값을 다시 받으려고 왕복하지 않는다", 0, member.meCalls)
    }

    // ── 실패해도 거짓말하지 않는다 ────────────────────────────────

    @Test
    fun `조회에_실패해도_OFF_로_확정하지_않는다`() = runTest(dispatcher) {
        로그인()
        val member = FakeMeRepository(result = Result.failure(ApiException.Network(IOException("끊김"))))
        val viewModel = viewModel(member)

        assertNull("모르는 채로 둔다", SessionStore.session.value?.marketingAgreed)
        assertFalse("스위치는 잠긴 채다", viewModel.uiState.value.marketingKnown)
        // 계정 화면의 나머지는 그대로 그린다 — 이 값 하나 때문에 화면을 오류로 덮지 않는다
        assertEquals("러너", viewModel.uiState.value.profile?.nickname)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `모르는_동안에는_토글이_요청을_보내지_않는다`() = runTest(dispatcher) {
        로그인()
        val member = FakeMeRepository(me = CompletableDeferred())
        val viewModel = viewModel(member)

        viewModel.onToggleMarketing()
        advanceUntilIdle()

        assertNull("보낼 값을 모르는데 보내면 안 된다", member.sentMarketing)
    }

    // ── 같은 세션 안의 순서 역전 (#290 리뷰) ──────────────────────

    @Test
    fun `늦은_조회는_방금_저장한_닉네임을_되돌리지_않는다`() = runTest(dispatcher) {
        // 선경님이 재현하신 그대로다. epoch 는 로그인 세대라 이 사이에 안 바뀐다
        로그인()
        val gate = CompletableDeferred<SessionProfile>()
        val member = FakeMeRepository(
            me = gate,
            patchNickname = 로그인직후.copy(nickname = "새닉네임", marketingAgreed = true),
        )
        val viewModel = viewModel(member)

        // 조회가 도는 동안 닉네임을 바꾼다
        viewModel.onNicknameChange("새닉네임")
        advanceUntilIdle()
        assertEquals("새닉네임", SessionStore.session.value?.nickname)

        // 이제 출발할 때의 프로필이 도착한다
        gate.complete(로그인직후.copy(nickname = "러너", marketingAgreed = true))
        advanceUntilIdle()

        assertEquals("늦은 조회가 저장한 닉네임을 덮으면 안 된다", "새닉네임", SessionStore.session.value?.nickname)
    }

    @Test
    fun `늦은_조회는_방금_철회한_마케팅을_다시_켜지_않는다`() = runTest(dispatcher) {
        // 선경님이 적으신 순서 그대로다.
        //
        //   GET /me 출발 (느림)
        //   닉네임 PATCH 성공 → **전체 프로필**이 오므로 마케팅 값도 채워진다(§2) → 토글 열림
        //   마케팅 철회 PATCH 성공 → 서버 OFF
        //   늦은 GET 도착 → 출발 당시의 ON 이 다시 얹힌다
        //
        // **서버 저장 결과는 OFF 인데 앱만 ON 이 된다** — 이 PR 이 고치려던 버그의 거울상이다.
        로그인()
        val gate = CompletableDeferred<SessionProfile>()
        val member = FakeMeRepository(
            me = gate,
            patchNickname = 로그인직후.copy(nickname = "새닉네임", marketingAgreed = true),
            patchMarketing = 로그인직후.copy(nickname = "새닉네임", marketingAgreed = false),
        )
        val viewModel = viewModel(member)

        // ① 조회는 아직 안 왔는데 닉네임 저장이 값을 채워 준다
        viewModel.onNicknameChange("새닉네임")
        advanceUntilIdle()
        assertTrue("PATCH 응답이 전체 프로필이라 토글이 열린다", viewModel.uiState.value.marketingKnown)

        // ② 사용자가 철회한다
        viewModel.onToggleMarketing()
        advanceUntilIdle()
        assertEquals("보낸 값은 철회다", false, member.sentMarketing)
        assertEquals(false, SessionStore.session.value?.marketingAgreed)

        // ③ 이제 출발할 때의 ON 이 도착한다
        gate.complete(로그인직후.copy(marketingAgreed = true))
        advanceUntilIdle()

        assertEquals(
            "늦은 조회가 철회를 되돌리면 서버는 OFF 인데 앱만 ON 이 된다",
            false,
            SessionStore.session.value?.marketingAgreed,
        )
    }

    @Test
    fun `조회가_먼저_끝나면_그_값이_그대로_선다`() = runTest(dispatcher) {
        // 대조군 — 순서가 정상이면 조회 결과가 반영돼야 한다. 이게 없으면 위 둘이
        // "그냥 조회를 안 쓴다" 로도 통과한다
        로그인()
        val member = FakeMeRepository(result = Result.success(로그인직후.copy(marketingAgreed = true)))
        val viewModel = viewModel(member)

        assertTrue(viewModel.uiState.value.marketingAgreed)
        assertEquals(true, SessionStore.session.value?.marketingAgreed)
    }

    // ── 모른다는 것을 화면에 적는다 (#290 리뷰) ────────────────────

    @Test
    fun `조회_중에는_불러오는_중이라고_적는다`() = runTest(dispatcher) {
        로그인()
        val viewModel = viewModel(FakeMeRepository(me = CompletableDeferred()))

        assertEquals(MarketingNotice.LOADING, viewModel.uiState.value.marketingNotice)
    }

    @Test
    fun `조회에_실패하면_못_불러왔다고_적는다`() = runTest(dispatcher) {
        // 잠그기만 하면 꺼진 스위치로 보인다 — 서버가 ON 인 사용자가 "동의 안 했다" 로 읽는다
        로그인()
        val member = FakeMeRepository(result = Result.failure(ApiException.Network(IOException("끊김"))))
        val viewModel = viewModel(member)

        assertEquals(MarketingNotice.FAILED, viewModel.uiState.value.marketingNotice)
    }

    @Test
    fun `값을_알면_보조_문구를_안_붙인다`() = runTest(dispatcher) {
        로그인()
        val member = FakeMeRepository(result = Result.success(로그인직후.copy(marketingAgreed = true)))
        val viewModel = viewModel(member)

        assertEquals(MarketingNotice.NONE, viewModel.uiState.value.marketingNotice)
    }

    @Test
    fun `실패한_뒤_다시_시도하면_다시_조회한다`() = runTest(dispatcher) {
        로그인()
        val member = FakeMeRepository(result = Result.failure(ApiException.Network(IOException("끊김"))))
        val viewModel = viewModel(member)
        assertEquals(1, member.meCalls)

        viewModel.refreshProfile()
        advanceUntilIdle()

        assertEquals("재조회 경로가 없으면 앱을 껐다 켜는 수밖에 없다", 2, member.meCalls)
    }

    // ── 계정이 바뀌는 경우 ────────────────────────────────────────

    @Test
    fun `늦게_도착한_응답은_바뀐_계정을_덮지_않는다`() = runTest(dispatcher) {
        로그인()
        val gate = CompletableDeferred<SessionProfile>()
        val member = FakeMeRepository(me = gate)
        viewModel(member)

        // 왕복 중에 계정이 바뀐다 (로그아웃 후 다른 계정 로그인 — 세대가 올라간다)
        val 다른계정 = SessionProfile(
            nickname = "건모",
            email = "gunmo@test.com",
            loginProvider = LoginProvider.KAKAO,
            marketingAgreed = false,
        )
        SessionStore.signIn(다른계정, AuthTokens(accessToken = "A2", refreshToken = "R2"))
        advanceUntilIdle()

        // 이제 A 계정의 응답이 도착한다
        gate.complete(로그인직후.copy(marketingAgreed = true))
        advanceUntilIdle()

        assertEquals("남의 프로필이 얹히면 안 된다", "건모", SessionStore.session.value?.nickname)
        assertEquals(false, SessionStore.session.value?.marketingAgreed)
    }
}

// ── 가짜 ────────────────────────────────────────────────────────

/**
 * `GET /me` 만 진짜로 답하는 저장소.
 *
 * [me] 를 주면 그걸 기다린다 — "아직 안 돌아온 상태" 를 실제로 만들어야 잠금과 세대
 * 비교를 볼 수 있다. 안 주면 [result] 로 즉시 답한다.
 */
private class FakeMeRepository(
    private val result: Result<SessionProfile>? = null,
    private val me: CompletableDeferred<SessionProfile>? = null,
    /** `PATCH /me`(닉네임)가 돌려줄 프로필. **전체 프로필이 온다**(§2). */
    private val patchNickname: SessionProfile? = null,
    /** `PATCH /me/agreements` 가 돌려줄 프로필. */
    private val patchMarketing: SessionProfile? = null,
) : MemberRepository {

    var meCalls = 0
        private set
    var sentMarketing: Boolean? = null
        private set

    override suspend fun me(): SessionProfile {
        meCalls++
        me?.let { return it.await() }
        return result!!.getOrThrow()
    }

    override suspend fun updateMarketing(agreed: Boolean): SessionProfile {
        sentMarketing = agreed
        return patchMarketing ?: result?.getOrThrow() ?: error("이 테스트는 여기까지 오면 안 된다")
    }

    override suspend fun updateNickname(nickname: String): SessionProfile =
        patchNickname ?: error("이 테스트는 닉네임을 부르지 않는다")

    override suspend fun updatePassword(currentPassword: String, newPassword: String): AuthTokens =
        error("이 테스트는 비밀번호를 부르지 않는다")

    override suspend fun reauth(credential: ReauthCredential): String =
        error("이 테스트는 탈퇴를 부르지 않는다")

    override suspend fun withdraw(reauthToken: String): Unit =
        error("이 테스트는 탈퇴를 부르지 않는다")
}

private class MarketingStubAuthRepository : AuthRepository {
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
