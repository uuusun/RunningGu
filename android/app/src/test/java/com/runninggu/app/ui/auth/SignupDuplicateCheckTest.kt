package com.runninggu.app.ui.auth

import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.AuthRepository
import com.runninggu.app.data.repository.AuthSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A2 이메일·닉네임 중복 확인. (화면-API 매핑표 D-30 · SPEC 결정-50 · 이슈 #97)
 *
 * 세 가지가 어긋나기 쉬운 자리다.
 *
 * 1. **`Error` 로 진행을 막으면 안 된다** — 확인 API 가 죽었을 때 아무도 가입할 수 없다
 * 2. **입력이 바뀌면 결과가 즉시 무효**여야 한다 — `Available` 을 띄운 채 다른 값을 치면
 *    화면이 거짓말을 한다
 * 3. **늦게 온 응답을 버려야 한다** — 앞 입력의 결과가 새 입력의 결과인 것처럼 앉는다
 */
class SignupDuplicateCheckTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** 중복 확인 두 경로만 실제 동작시키고, 이 테스트와 무관한 로그아웃은 성공으로 고정한다. */
    private open class StubAuthRepository(
        private val emailTaken: Result<Boolean> = Result.success(false),
        private val nicknameTaken: Result<Boolean> = Result.success(false),
    ) : AuthRepository {
        var emailCalls = 0
            private set
        var nicknameCalls = 0
            private set

        override suspend fun emailExists(email: String): Result<Boolean> {
            emailCalls++
            return emailTaken
        }

        override suspend fun nicknameExists(nickname: String): Result<Boolean> {
            nicknameCalls++
            return nicknameTaken
        }

        override suspend fun login(email: String, password: String): Result<AuthSession> = TODO()
        override suspend fun sendSignupCode(email: String): Result<Unit> = TODO()
        override suspend fun verifySignupCode(email: String, code: String): Result<Unit> = TODO()
        override suspend fun signup(
            email: String,
            password: String,
            nickname: String,
            marketingAgreed: Boolean,
        ): Result<AuthSession> = TODO()
        override suspend fun requestPasswordReset(email: String): Result<Unit> = TODO()
        override suspend fun logout(refreshToken: String): Result<Unit> = Result.success(Unit)
    }

    private fun httpFailure() = Result.failure<Boolean>(
        ApiException.Http(status = 429, code = ApiErrorCode.RATE_LIMITED, problem = null),
    )

    /** 정보 입력 단계까지 채운 ViewModel. 중복 확인만 남는다. */
    private fun viewModelAtInfoStep(repository: AuthRepository): SignupViewModel =
        SignupViewModel(repository).apply {
            onToggleTos()
            onTogglePrivacy()
            onAgreeNext()
            onEmailChange("runner@test.com")
            onPasswordChange("runner1234")
            onPasswordConfirmChange("runner1234")
            onNicknameChange("러너")
        }

    @Test
    fun `포커스가 빠지면 확인하고 쓸 수 있으면 Available 이다`() = runTest(dispatcher) {
        val repository = StubAuthRepository(emailTaken = Result.success(false))
        val viewModel = viewModelAtInfoStep(repository)

        viewModel.onEmailFocusLost()
        advanceUntilIdle()

        assertEquals(1, repository.emailCalls)
        assertEquals(DuplicateCheck.Available, viewModel.uiState.value.emailCheck)
    }

    @Test
    fun `이미 있는 값이면 Duplicate 이고 발송을 막는다`() = runTest(dispatcher) {
        val repository = StubAuthRepository(nicknameTaken = Result.success(true))
        val viewModel = viewModelAtInfoStep(repository)

        viewModel.onNicknameFocusLost()
        advanceUntilIdle()

        assertEquals(DuplicateCheck.Duplicate, viewModel.uiState.value.nicknameCheck)
        // 확정 중복만 막는다 (D-30)
        assertFalse(viewModel.uiState.value.canProceedInfo)
    }

    @Test
    fun `조회에 실패해도 가입을 막지 않는다`() = runTest(dispatcher) {
        // 확인 API 가 죽었다고 가입 자체를 못 하게 하면 사용자가 할 수 있는 게 없다.
        // signup 의 서버 유니크 방어가 최종 방어선이다 (D-30).
        val repository = object : StubAuthRepository() {
            override suspend fun emailExists(email: String) = httpFailure()
            override suspend fun nicknameExists(nickname: String) = httpFailure()
        }
        val viewModel = viewModelAtInfoStep(repository)

        viewModel.onEmailFocusLost()
        viewModel.onNicknameFocusLost()
        advanceUntilIdle()

        assertEquals(DuplicateCheck.Error, viewModel.uiState.value.emailCheck)
        assertEquals(DuplicateCheck.Error, viewModel.uiState.value.nicknameCheck)
        assertTrue(viewModel.uiState.value.canProceedInfo)
    }

    @Test
    fun `확인 전에도 발송할 수 있다`() = runTest(dispatcher) {
        // Unchecked 는 "아직 포커스가 안 빠진 상태" 다. 여기서 막으면 게이트 안내가 또 필요하다.
        val viewModel = viewModelAtInfoStep(StubAuthRepository())
        advanceUntilIdle()

        assertEquals(DuplicateCheck.Unchecked, viewModel.uiState.value.emailCheck)
        assertTrue(viewModel.uiState.value.canProceedInfo)
    }

    @Test
    fun `조회 중에는 발송을 막는다`() = runTest(dispatcher) {
        // 결과가 코앞인데 보내면 헛 왕복이 된다. 잠깐만 기다리게 한다 (D-30).
        val gate = CompletableDeferred<Result<Boolean>>()
        val repository = object : StubAuthRepository() {
            override suspend fun emailExists(email: String) = gate.await()
        }
        val viewModel = viewModelAtInfoStep(repository)

        viewModel.onEmailFocusLost()
        advanceUntilIdle()

        assertEquals(DuplicateCheck.Checking, viewModel.uiState.value.emailCheck)
        assertFalse(viewModel.uiState.value.canProceedInfo)

        gate.complete(Result.success(false))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canProceedInfo)
    }

    @Test
    fun `입력이 바뀌면 확인 결과를 즉시 지운다`() = runTest(dispatcher) {
        val viewModel = viewModelAtInfoStep(StubAuthRepository())

        viewModel.onEmailFocusLost()
        advanceUntilIdle()
        assertEquals(DuplicateCheck.Available, viewModel.uiState.value.emailCheck)

        // "사용할 수 있어요" 를 띄운 채 다른 주소를 치고 있으면 거짓말이 된다.
        viewModel.onEmailChange("runner@test.co")
        assertEquals(DuplicateCheck.Unchecked, viewModel.uiState.value.emailCheck)
    }

    @Test
    fun `늦게 온 응답은 버린다`() = runTest(dispatcher) {
        // 앞 입력의 결과가 새 입력의 결과인 것처럼 앉으면, 쓸 수 없는 닉네임이
        // "사용할 수 있어요" 로 보인다.
        val gate = CompletableDeferred<Result<Boolean>>()
        val repository = object : StubAuthRepository() {
            override suspend fun nicknameExists(nickname: String) =
                if (nickname == "러너") gate.await() else Result.success(false)
        }
        val viewModel = viewModelAtInfoStep(repository)

        viewModel.onNicknameFocusLost()
        advanceUntilIdle()
        assertEquals(DuplicateCheck.Checking, viewModel.uiState.value.nicknameCheck)

        // 응답이 오기 전에 값을 바꾼다
        viewModel.onNicknameChange("달리기")
        gate.complete(Result.success(true))
        advanceUntilIdle()

        // 앞 값의 "중복" 이 새 값에 앉으면 안 된다
        assertEquals(DuplicateCheck.Unchecked, viewModel.uiState.value.nicknameCheck)
    }

    @Test
    fun `값이 그대로면 포커스가 다시 빠져도 안 부른다`() = runTest(dispatcher) {
        // A2 는 네 칸이 한 화면에 있어 오가는 게 자연스럽다. 그때마다 부르면 값별 5회/분
        // 제한에 걸려 `Available` 이던 자리가 `Error` 로 퇴행한다 (#132 리뷰).
        val repository = StubAuthRepository()
        val viewModel = viewModelAtInfoStep(repository)

        viewModel.onEmailFocusLost()
        advanceUntilIdle()
        assertEquals(1, repository.emailCalls)

        // 값을 안 바꾸고 포커스만 다시 오갔다
        viewModel.onEmailFocusLost()
        viewModel.onEmailFocusLost()
        advanceUntilIdle()

        assertEquals(1, repository.emailCalls)
        assertEquals(DuplicateCheck.Available, viewModel.uiState.value.emailCheck)
    }

    @Test
    fun `값이 바뀌면 다시 부른다`() = runTest(dispatcher) {
        val repository = StubAuthRepository()
        val viewModel = viewModelAtInfoStep(repository)

        viewModel.onEmailFocusLost()
        advanceUntilIdle()
        assertEquals(1, repository.emailCalls)

        viewModel.onEmailChange("other@test.com")
        viewModel.onEmailFocusLost()
        advanceUntilIdle()

        assertEquals(2, repository.emailCalls)
    }

    @Test
    fun `못 물어봤으면 다시 시도한다`() = runTest(dispatcher) {
        // Error 는 답을 모르는 상태다. 값이 그대로여도 재시도할 값어치가 있다.
        var fail = true
        val repository = object : StubAuthRepository() {
            override suspend fun nicknameExists(nickname: String): Result<Boolean> {
                super.nicknameExists(nickname)
                return if (fail) httpFailure() else Result.success(false)
            }
        }
        val viewModel = viewModelAtInfoStep(repository)

        viewModel.onNicknameFocusLost()
        advanceUntilIdle()
        assertEquals(DuplicateCheck.Error, viewModel.uiState.value.nicknameCheck)

        fail = false
        viewModel.onNicknameFocusLost()
        advanceUntilIdle()

        assertEquals(2, repository.nicknameCalls)
        assertEquals(DuplicateCheck.Available, viewModel.uiState.value.nicknameCheck)
    }

    @Test
    fun `형식이 틀리면 부르지 않는다`() = runTest(dispatcher) {
        // 어차피 400 이고 인라인 안내가 이미 떠 있다. 서버 제한(값별 5회 분)도 아낀다.
        val repository = StubAuthRepository()
        val viewModel = viewModelAtInfoStep(repository)

        viewModel.onEmailChange("runner@")
        viewModel.onNicknameChange("가")
        viewModel.onEmailFocusLost()
        viewModel.onNicknameFocusLost()
        advanceUntilIdle()

        assertEquals(0, repository.emailCalls)
        assertEquals(0, repository.nicknameCalls)
    }
}
