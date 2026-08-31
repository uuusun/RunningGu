package com.runninggu.app.ui.auth

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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 이메일 가입 확정이 실패했을 때 **사용자가 나갈 길을 아는가.** (API 명세 §1-5 · #227 리뷰)
 *
 * 단계 순서가 `AGREE → INFO → VERIFY → DONE` 이라, 가입 확정은 **VERIFY 화면에서** 난다.
 * 그 화면에는 닉네임 칸도 이메일 칸도 없고 [인증 확인] 버튼 하나뿐이다. 그래서 **다시
 * 눌러서 풀리지 않는 오류를 "다시 시도해 주세요" 로 뭉뚱그리면 사용자가 갇힌다.**
 *
 * 특히 `NICKNAME_DUPLICATED` 는 이 저장소가 **최종 방어로 삼은 것**이다 —
 * [DuplicateCheck] KDoc 이 *"중복확인 [Error] 는 진행을 막지 않는다. `signup` 의
 * `NICKNAME_DUPLICATED` 가 최종 방어이고, 그건 동시 가입 대비로 어차피 필요하다"* 고
 * 적어 두었다. 그 마지막 방어가 원인 불명으로 도착하면 설계가 기대한 값을 못 낸다.
 *
 * 이 파일이 고정하는 것은 문구 자체가 아니라 **사유마다 다르다는 것**이다.
 */
class SignupFailureMessageTest {

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

    /**
     * VERIFY 에서 [인증 확인] 을 눌러 가입까지 간 상태를 만든다.
     *
     * 인증은 성공시키고 **가입만 실패**시킨다 — `onVerify()` 가 둘을 이어 부르므로,
     * 인증에서 막히면 이 파일이 보려는 자리에 닿지 않는다.
     */
    private fun submit(signupFailure: Throwable): SignupViewModel {
        val viewModel = SignupViewModel(repository = FakeSignupRepository(signupFailure))
        viewModel.onToggleTos()
        viewModel.onTogglePrivacy()
        viewModel.onAgreeNext()
        viewModel.onEmailChange("runner@test.com")
        viewModel.onPasswordChange("run4life1")
        viewModel.onPasswordConfirmChange("run4life1")
        viewModel.onNicknameChange("김러너")
        viewModel.onInfoNext()
        return viewModel
    }

    @Test
    fun `닉네임이 겹치면 뒤로 가서 바꾸라고 말한다`() = runTest(dispatcher) {
        val viewModel = submit(
            ApiException.Http(status = 409, code = ApiErrorCode.NICKNAME_DUPLICATED, problem = null),
        )
        advanceUntilIdle()
        viewModel.onCodeChange("123456")
        viewModel.onVerify()
        advanceUntilIdle()

        // VERIFY 에는 닉네임 칸이 없다. "다시 시도" 로 적으면 같은 409 만 반복된다
        assertEquals(
            "이미 쓰는 닉네임이에요. [뒤로] 를 눌러 다른 닉네임으로 바꿔 주세요.",
            viewModel.uiState.value.errorMessage,
        )
        // 뒤로 갔을 때 그 칸이 이미 결과를 들고 있어야 한다
        assertEquals(DuplicateCheck.Duplicate, viewModel.uiState.value.nicknameCheck)
        assertNull(SessionStore.session.value)
    }

    @Test
    fun `이메일이 겹치면 로그인하라고 말한다`() = runTest(dispatcher) {
        val viewModel = submit(
            ApiException.Http(status = 409, code = ApiErrorCode.EMAIL_DUPLICATED, problem = null),
        )
        advanceUntilIdle()
        viewModel.onCodeChange("123456")
        viewModel.onVerify()
        advanceUntilIdle()

        assertEquals(
            "이미 가입된 이메일이에요. 로그인 화면에서 로그인해 주세요.",
            viewModel.uiState.value.errorMessage,
        )
        assertEquals(DuplicateCheck.Duplicate, viewModel.uiState.value.emailCheck)
    }

    @Test
    fun `인증이 풀렸으면 재발송 길을 연다`() = runTest(dispatcher) {
        val viewModel = submit(
            ApiException.Http(status = 403, code = ApiErrorCode.EMAIL_NOT_VERIFIED, problem = null),
        )
        advanceUntilIdle()
        viewModel.onCodeChange("123456")
        viewModel.onVerify()
        advanceUntilIdle()

        assertEquals(
            "인증이 만료됐어요. 아래 [재발송] 으로 메일을 다시 받아 주세요.",
            viewModel.uiState.value.errorMessage,
        )
        // 문구만 고치면 "다시 하라" 는데 할 방법이 없다. 재발송 버튼이 열려야 말이 완성된다
        assertTrue("재발송 길이 안 열렸다", viewModel.uiState.value.mustResend)
    }

    @Test
    fun `인증 후 30분이 지나도 같은 길을 연다`() = runTest(dispatcher) {
        // §1-5 는 인증이 풀린 것을 둘로 가른다 — 아직 인증 전이면 403, 인증 후 30분이
        // 지났거나 행이 없으면 400 CODE_EXPIRED. **앱에서 실제로 닿는 것은 뒤쪽이다**
        // (닉네임을 고치러 뒤로 갔다 오는 사이 창이 닫힌다). 사용자가 할 일은 같다
        val viewModel = SignupViewModel(
            repository = FakeSignupRepository(
                ApiException.Http(status = 400, code = ApiErrorCode.CODE_EXPIRED, problem = null),
            ),
        )
        viewModel.onToggleTos()
        viewModel.onTogglePrivacy()
        viewModel.onAgreeNext()
        viewModel.onEmailChange("runner@test.com")
        viewModel.onPasswordChange("run4life1")
        viewModel.onPasswordConfirmChange("run4life1")
        viewModel.onNicknameChange("김러너")
        viewModel.onInfoNext()
        runCurrent()
        viewModel.onCodeChange("123456")
        viewModel.onVerify()
        runCurrent()

        assertEquals(
            "인증이 만료됐어요. 아래 [재발송] 으로 메일을 다시 받아 주세요.",
            viewModel.uiState.value.errorMessage,
        )
        assertTrue("재발송이 열려야 한다", viewModel.uiState.value.mustResend)
        assertEquals("쿨다운도 함께 풀려야 한다", 0, viewModel.uiState.value.resendCooldownSec)
    }

    @Test
    fun `비밀번호와 약관은 각각 다른 곳을 가리킨다`() = runTest(dispatcher) {
        val password = submit(
            ApiException.Http(status = 400, code = ApiErrorCode.INVALID_PASSWORD, problem = null),
        ).also {
            advanceUntilIdle()
            it.onCodeChange("123456")
            it.onVerify()
        }
        advanceUntilIdle()
        assertEquals(
            "비밀번호가 조건에 맞지 않아요. [뒤로] 를 눌러 다시 정해 주세요.",
            password.uiState.value.errorMessage,
        )

        val agreement = submit(
            ApiException.Http(status = 400, code = ApiErrorCode.AGREEMENT_REQUIRED, problem = null),
        ).also {
            advanceUntilIdle()
            it.onCodeChange("123456")
            it.onVerify()
        }
        advanceUntilIdle()
        assertEquals(
            "필수 약관에 동의해야 가입할 수 있어요.",
            agreement.uiState.value.errorMessage,
        )
    }

    @Test
    fun `네트워크 실패는 다시 눌러 볼 수 있다고 말한다`() = runTest(dispatcher) {
        val viewModel = submit(ApiException.Network(IOException("끊김")))
        advanceUntilIdle()
        viewModel.onCodeChange("123456")
        viewModel.onVerify()
        advanceUntilIdle()

        // 위 넷과 달리 **여기서는 다시 누르는 게 맞다.** 문구가 같으면 그 차이가 사라진다
        assertEquals(
            "네트워크에 연결되지 않았어요. 연결을 확인해 주세요.",
            viewModel.uiState.value.errorMessage,
        )
        assertTrue("네트워크 실패에 재발송을 걸면 안 된다", !viewModel.uiState.value.mustResend)
    }

    @Test
    fun `그 밖의 실패는 일반 문구로 둔다`() = runTest(dispatcher) {
        val viewModel = submit(
            ApiException.Http(status = 500, code = ApiErrorCode.UNKNOWN, problem = null),
        )
        advanceUntilIdle()
        viewModel.onCodeChange("123456")
        viewModel.onVerify()
        advanceUntilIdle()

        assertEquals("가입에 실패했어요. 다시 시도해 주세요.", viewModel.uiState.value.errorMessage)
    }

    // ── #235 리뷰: 안내대로 실제로 할 수 있는가 ──────────────────────

    @Test
    fun `인증이 풀리면 재발송 쿨다운을 면제한다`() = runTest(dispatcher) {
        // **`advanceUntilIdle()` 을 쓰면 안 된다** — 60초 카운트다운을 가상 시간으로
        // 통째로 흘려 버려서 쿨다운이 0 이 된 채 검사하게 된다. 그러면 이 테스트가
        // 헛돈다. `runCurrent()` 는 지금 시각의 일만 처리하고 시간을 안 넘긴다
        val viewModel = SignupViewModel(
            repository = FakeSignupRepository(
                ApiException.Http(status = 403, code = ApiErrorCode.EMAIL_NOT_VERIFIED, problem = null),
            ),
        )
        viewModel.onToggleTos()
        viewModel.onTogglePrivacy()
        viewModel.onAgreeNext()
        viewModel.onEmailChange("runner@test.com")
        viewModel.onPasswordChange("run4life1")
        viewModel.onPasswordConfirmChange("run4life1")
        viewModel.onNicknameChange("김러너")
        viewModel.onInfoNext()
        runCurrent()
        // 발송 직후라 쿨다운이 돌고 있다
        assertTrue("전제가 깨졌다 — 발송 후 쿨다운이 시작돼야 한다", viewModel.uiState.value.resendCooldownSec > 0)

        viewModel.onCodeChange("123456")
        viewModel.onVerify()
        runCurrent()

        // "메일을 다시 받아 주세요" 를 띄워 놓고 버튼이 잠겨 있으면 할 수 있는 게 없다
        assertTrue("재발송이 열려야 한다", viewModel.uiState.value.mustResend)
        assertEquals(0, viewModel.uiState.value.resendCooldownSec)
    }

    @Test
    fun `닉네임을 고쳐 돌아와도 인증을 다시 받지 않는다`() = runTest(dispatcher) {
        // 닉네임이 겹쳐 실패 → 안내대로 [뒤로] → 닉네임 변경 → [다음]
        val repository = FakeSignupRepository(
            ApiException.Http(status = 409, code = ApiErrorCode.NICKNAME_DUPLICATED, problem = null),
        )
        val viewModel = SignupViewModel(repository = repository)
        viewModel.onToggleTos()
        viewModel.onTogglePrivacy()
        viewModel.onAgreeNext()
        viewModel.onEmailChange("runner@test.com")
        viewModel.onPasswordChange("run4life1")
        viewModel.onPasswordConfirmChange("run4life1")
        viewModel.onNicknameChange("김러너")
        viewModel.onInfoNext()
        advanceUntilIdle()
        viewModel.onCodeChange("123456")
        viewModel.onVerify()
        advanceUntilIdle()
        assertEquals(1, repository.sendCount)

        viewModel.onStepBack()
        viewModel.onNicknameChange("김러너2")
        viewModel.onInfoNext()
        advanceUntilIdle()

        // **여기가 요점이다.** 재발송하면 §1-3 상 30분 남은 인증이 무효가 되고,
        // 60초 안이면 429 SEND_COOLDOWN 으로 아예 막힌다
        assertEquals("재발송하면 안 된다", 1, repository.sendCount)
        assertEquals(SignupStep.VERIFY, viewModel.uiState.value.step)
        // 들고 온 코드로 그대로 [인증 확인] 을 누를 수 있어야 한다 (§1-4 멱등 200)
        assertEquals("123456", viewModel.uiState.value.code)
    }

    @Test
    fun `이메일을 바꾸면 인증을 다시 받는다`() = runTest(dispatcher) {
        val repository = FakeSignupRepository(
            ApiException.Http(status = 409, code = ApiErrorCode.NICKNAME_DUPLICATED, problem = null),
        )
        val viewModel = SignupViewModel(repository = repository)
        viewModel.onToggleTos()
        viewModel.onTogglePrivacy()
        viewModel.onAgreeNext()
        viewModel.onEmailChange("runner@test.com")
        viewModel.onPasswordChange("run4life1")
        viewModel.onPasswordConfirmChange("run4life1")
        viewModel.onNicknameChange("김러너")
        viewModel.onInfoNext()
        advanceUntilIdle()
        viewModel.onCodeChange("123456")
        viewModel.onVerify()
        advanceUntilIdle()

        viewModel.onStepBack()
        // 닉네임도 함께 고친다 — 409 로 `nicknameCheck` 가 Duplicate 라 그대로면
        // `canProceedInfo` 가 막는다(그 자체는 의도한 동작이다)
        viewModel.onNicknameChange("김러너2")
        viewModel.onEmailChange("other@test.com")
        viewModel.onInfoNext()
        advanceUntilIdle()

        // 인증은 이메일에 붙은 것이라 바뀌면 다시 받아야 한다 — 건너뛰기가 과하면 안 된다
        assertEquals("이메일이 바뀌면 보내야 한다", 2, repository.sendCount)
        assertEquals("", viewModel.uiState.value.code)
    }

    @Test
    fun `여섯 문구가 서로 다르다`() = runTest(dispatcher) {
        // 하나씩 보면 통과하는데 둘이 같아지는 사고를 막는다 — 가르는 것이 이 파일의 목적이다
        val causes = listOf(
            ApiException.Http(status = 409, code = ApiErrorCode.NICKNAME_DUPLICATED, problem = null),
            ApiException.Http(status = 409, code = ApiErrorCode.EMAIL_DUPLICATED, problem = null),
            ApiException.Http(status = 403, code = ApiErrorCode.EMAIL_NOT_VERIFIED, problem = null),
            ApiException.Http(status = 400, code = ApiErrorCode.INVALID_PASSWORD, problem = null),
            ApiException.Http(status = 400, code = ApiErrorCode.AGREEMENT_REQUIRED, problem = null),
            ApiException.Http(status = 500, code = ApiErrorCode.UNKNOWN, problem = null),
        )
        val messages = causes.map { cause ->
            val viewModel = submit(cause)
            advanceUntilIdle()
            viewModel.onCodeChange("123456")
            viewModel.onVerify()
            advanceUntilIdle()
            viewModel.uiState.value.errorMessage
        }

        assertTrue("문구가 겹친다: $messages", messages.toSet().size == causes.size)
        assertTrue("문구가 비었다: $messages", messages.all { !it.isNullOrBlank() })
    }
}

/** 인증은 통과시키고 **가입만** 실패시킨다. 발송 횟수를 센다. */
private class FakeSignupRepository(private val signupFailure: Throwable) : AuthRepository {
    var sendCount = 0
        private set

    override suspend fun signup(
        email: String,
        password: String,
        nickname: String,
        marketingAgreed: Boolean,
    ): Result<AuthSession> = Result.failure(signupFailure)

    override suspend fun sendSignupCode(email: String): Result<Unit> {
        sendCount++
        return Result.success(Unit)
    }
    override suspend fun verifySignupCode(email: String, code: String): Result<Unit> = Result.success(Unit)

    // 중복확인은 이 파일이 보려는 자리가 아니다 — 둘 다 "없음" 으로 두어 진행만 시킨다
    override suspend fun emailExists(email: String): Result<Boolean> = Result.success(false)
    override suspend fun nicknameExists(nickname: String): Result<Boolean> = Result.success(false)

    override suspend fun kakaoSignup(
        kakaoAccessToken: String,
        nickname: String,
        marketingAgreed: Boolean,
    ): Result<AuthSession> = unused()

    override suspend fun kakaoLogin(kakaoAccessToken: String): Result<KakaoLoginOutcome> = unused()
    override suspend fun logout(refreshToken: String): Result<Unit> = unused()
    override suspend fun login(email: String, password: String): Result<AuthSession> = unused()
    override suspend fun requestPasswordReset(email: String): Result<Unit> = unused()

    private fun <T> unused(): T =
        throw UnsupportedOperationException("이 테스트는 이메일 가입만 부른다")
}
