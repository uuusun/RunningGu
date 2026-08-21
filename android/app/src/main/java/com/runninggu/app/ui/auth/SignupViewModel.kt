package com.runninggu.app.ui.auth

import com.runninggu.app.data.repository.AuthRepository
import com.runninggu.app.data.repository.FakeAuthRepository
import com.runninggu.app.data.repository.apiErrorCode
import com.runninggu.app.data.repository.isNetworkFailure
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.data.remote.ApiErrorCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.local.SessionStore

/**
 * A2 회원가입 단계. (SPEC §4.2 — 동의 → 정보 입력 → 이메일 인증 → 완료)
 */
enum class SignupStep { AGREE, INFO, VERIFY, DONE }

/**
 * 이메일·닉네임 중복 확인 상태. (화면-API 매핑표 D-30 · SPEC 결정-49 · 이슈 #97)
 *
 * **[Error] 는 진행을 막지 않는다.** 조회가 안 된다고 가입 자체를 못 하게 하면 사용자가
 * 할 수 있는 게 없다 — `send-code` 의 `EMAIL_DUPLICATED` 와 `signup` 의
 * `NICKNAME_DUPLICATED` 가 최종 방어이고, 그건 동시 가입 대비로 어차피 필요하다.
 * 확정으로 막는 것은 [Duplicate] 뿐이다.
 */
sealed interface DuplicateCheck {
    /** 아직 확인하지 않았다. 포커스가 안 빠졌거나 입력이 바뀌어 무효가 된 상태다. */
    data object Unchecked : DuplicateCheck

    data object Checking : DuplicateCheck
    data object Available : DuplicateCheck
    data object Duplicate : DuplicateCheck

    /** 조회 실패. 안내만 하고 막지 않는다. */
    data object Error : DuplicateCheck
}

/**
 * 이 상태에서 [인증 메일 발송]을 눌러도 되는가. (D-30)
 *
 * 조회 중이면 결과를 기다리게 하고, 확정 중복이면 막는다. 나머지는 통과다.
 */
val DuplicateCheck.allowsSubmit: Boolean
    get() = this != DuplicateCheck.Checking && this != DuplicateCheck.Duplicate

/**
 * A2 회원가입의 UI 계약. (SPEC §4.2)
 *
 * 단계가 넷이라 한 ViewModel 이 상태 기계를 들고 있는다 — 화면은 [step] 만 보고 그린다.
 * 뒤로가기는 단계 역행이다(완료 제외).
 */
data class SignupUiState(
    val step: SignupStep = SignupStep.AGREE,

    // 1단계 — 약관·개인정보 동의 (필수 2종 + 선택 마케팅)
    val tosAgreed: Boolean = false,
    val privacyAgreed: Boolean = false,
    val marketingAgreed: Boolean = false,

    // 2단계 — 정보 입력
    val email: String = "",
    val password: String = "",
    val passwordConfirm: String = "",
    val nickname: String = "",
    /** 이메일 중복 확인. 포커스가 빠질 때 한 번 부른다. (D-30) */
    val emailCheck: DuplicateCheck = DuplicateCheck.Unchecked,
    /** 닉네임 중복 확인. 같은 규칙이다. */
    val nicknameCheck: DuplicateCheck = DuplicateCheck.Unchecked,

    // 3단계 — 이메일 인증
    val code: String = "",
    /** 재발송까지 남은 초. 0이면 재발송 가능. (SPEC §4.2-3 쿨다운 60초) */
    val resendCooldownSec: Int = 0,
    /**
     * 코드가 만료됐거나 5회 실패해서 **재발송해야만** 진행할 수 있는 상태.
     * (§1-4 `CODE_EXPIRED`·`TOO_MANY_ATTEMPTS` · NFR-10 🔒)
     *
     * 이 상태에서 [확인]을 열어 두면 사용자가 같은 코드로 계속 시도하다 빠져나오지 못한다.
     */
    val mustResend: Boolean = false,

    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    /** 가입 완료 + 자동 로그인(명세 §1-5). 화면이 이걸 보고 `home` 으로 나간다. */
    val completed: Boolean = false,
) {
    val allAgreed: Boolean get() = tosAgreed && privacyAgreed && marketingAgreed

    /** 필수 2종 동의. 미동의면 진행 불가(SPEC §4.2-1). */
    val canProceedAgree: Boolean get() = tosAgreed && privacyAgreed

    val isEmailValid: Boolean get() = AuthValidation.isEmailValid(email)
    val isPasswordValid: Boolean get() = AuthValidation.isPasswordValid(password)
    val isPasswordConfirmed: Boolean get() = passwordConfirm.isNotEmpty() && password == passwordConfirm
    val isNicknameValid: Boolean get() = AuthValidation.isNicknameValid(nickname)

    /**
     * [인증 메일 발송] 활성 조건. (D-30)
     *
     * 중복 확인은 **`Checking` 과 `Duplicate` 만 막는다.** `Unchecked`(아직 포커스가 안 빠짐)
     * 와 `Error`(조회 실패)는 서버 유니크 방어를 믿고 통과시킨다 — 여기서 막으면 확인 API 가
     * 죽었을 때 아무도 가입할 수 없다.
     */
    val canProceedInfo: Boolean
        get() = !isSubmitting && isEmailValid && isPasswordValid && isPasswordConfirmed &&
            isNicknameValid && emailCheck.allowsSubmit && nicknameCheck.allowsSubmit

    val canVerify: Boolean
        get() = !isSubmitting && !mustResend && AuthValidation.isCodeValid(code)
}

/** A2 회원가입. (SPEC §4.2 · AP-08) */
class SignupViewModel(
    private val repository: AuthRepository = FakeAuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SignupUiState())
    val uiState: StateFlow<SignupUiState> = _uiState.asStateFlow()

    /** 진행 중인 쿨다운 카운트다운. 새로 시작할 때 이전 것을 끊는다. */
    private var cooldownJob: Job? = null

    /** 진행 중인 중복 확인. 입력이 바뀌거나 다시 부를 때 끊는다. */
    private var emailCheckJob: Job? = null
    private var nicknameCheckJob: Job? = null

    // ── 1단계 동의 ──────────────────────────────────────────────

    fun onToggleAll() {
        _uiState.update {
            val next = !it.allAgreed
            it.copy(tosAgreed = next, privacyAgreed = next, marketingAgreed = next)
        }
    }

    fun onToggleTos() = _uiState.update { it.copy(tosAgreed = !it.tosAgreed) }
    fun onTogglePrivacy() = _uiState.update { it.copy(privacyAgreed = !it.privacyAgreed) }
    fun onToggleMarketing() = _uiState.update { it.copy(marketingAgreed = !it.marketingAgreed) }

    fun onAgreeNext() {
        if (_uiState.value.canProceedAgree) {
            _uiState.update { it.copy(step = SignupStep.INFO, errorMessage = null) }
        }
    }

    // ── 2단계 정보 입력 ─────────────────────────────────────────

    /**
     * 입력이 바뀌면 **확인 결과를 즉시 지운다.** (D-30)
     *
     * `Available` 을 띄운 채 다른 주소를 치고 있으면 화면이 거짓말을 한다. 진행 중인
     * 조회도 끊는다 — 늦게 온 응답이 새 입력의 결과인 것처럼 앉으면 안 된다.
     */
    fun onEmailChange(value: String) {
        emailCheckJob?.cancel()
        _uiState.update {
            it.copy(email = value, emailCheck = DuplicateCheck.Unchecked, errorMessage = null)
        }
    }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, errorMessage = null) }
    fun onPasswordConfirmChange(value: String) = _uiState.update { it.copy(passwordConfirm = value, errorMessage = null) }
    fun onNicknameChange(value: String) {
        nicknameCheckJob?.cancel()
        _uiState.update {
            it.copy(nickname = value, nicknameCheck = DuplicateCheck.Unchecked, errorMessage = null)
        }
    }

    /**
     * 이메일 칸에서 포커스가 빠졌다 — 중복을 확인한다. (D-30 · 이슈 #97)
     *
     * **버튼이 아니라 포커스 이탈로 부른다.** 버튼은 탭이 늘고 안 누르고 넘어가는 사람이
     * 생겨 게이트가 또 필요해진다. 입력 중 debounce 는 타이핑마다 요청이 나가는데,
     * 서버 제한이 **값별 5회/분**이라 실제로 걸린다(§1-1).
     *
     * 형식이 틀렸으면 부르지 않는다 — 어차피 `400` 이고 인라인 안내가 이미 떠 있다.
     */
    fun onEmailFocusLost() {
        val email = _uiState.value.email.trim()
        if (!AuthValidation.isEmailValid(email)) return
        emailCheckJob?.cancel()
        emailCheckJob = viewModelScope.launch {
            _uiState.update { it.copy(emailCheck = DuplicateCheck.Checking) }
            val next = repository.emailExists(email).fold(
                onSuccess = { if (it) DuplicateCheck.Duplicate else DuplicateCheck.Available },
                onFailure = { DuplicateCheck.Error },
            )
            // 기다리는 사이 입력이 바뀌었으면 버린다 — 이 결과는 이미 남의 것이다.
            if (_uiState.value.email.trim() != email) return@launch
            _uiState.update { it.copy(emailCheck = next) }
        }
    }

    /** 닉네임 칸 포커스 이탈. 이메일과 같은 규칙이다. (D-30) */
    fun onNicknameFocusLost() {
        val nickname = _uiState.value.nickname.trim()
        if (!AuthValidation.isNicknameValid(nickname)) return
        nicknameCheckJob?.cancel()
        nicknameCheckJob = viewModelScope.launch {
            _uiState.update { it.copy(nicknameCheck = DuplicateCheck.Checking) }
            val next = repository.nicknameExists(nickname).fold(
                onSuccess = { if (it) DuplicateCheck.Duplicate else DuplicateCheck.Available },
                onFailure = { DuplicateCheck.Error },
            )
            if (_uiState.value.nickname.trim() != nickname) return@launch
            _uiState.update { it.copy(nicknameCheck = next) }
        }
    }

    /** 정보 입력 완료 → 인증 코드 발송 후 3단계로. (SPEC §4.2-3) */
    fun onInfoNext() {
        val state = _uiState.value
        if (!state.canProceedInfo) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            repository.sendSignupCode(state.email.trim()).fold(
                onSuccess = {
                    _uiState.update { it.copy(isSubmitting = false, step = SignupStep.VERIFY, code = "") }
                    startResendCooldown()
                },
                onFailure = { cause ->
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = sendFailureMessage(cause))
                    }
                },
            )
        }
    }

    // ── 3단계 이메일 인증 ────────────────────────────────────────

    fun onCodeChange(value: String) {
        val digits = value.filter { it.isDigit() }.take(AuthValidation.CODE_LENGTH)
        _uiState.update { it.copy(code = digits, errorMessage = null) }
    }

    fun onResendCode() {
        val state = _uiState.value
        if (state.resendCooldownSec > 0 || state.isSubmitting) return
        viewModelScope.launch {
            repository.sendSignupCode(state.email.trim()).fold(
                onSuccess = {
                    // 재발송하면 시도 횟수가 초기화되므로 잠금도 풀린다 (§1-4).
                    _uiState.update { it.copy(mustResend = false, code = "", errorMessage = null) }
                    startResendCooldown()
                },
                onFailure = { cause ->
                    _uiState.update { it.copy(errorMessage = sendFailureMessage(cause)) }
                },
            )
        }
    }

    /** 코드 검증 → 가입 확정. 성공 시 자동 로그인(명세 §1-5). (SPEC §4.2-3·4) */
    fun onVerify() {
        val state = _uiState.value
        if (!state.canVerify) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val verified = repository.verifySignupCode(state.email.trim(), state.code)
            verified.exceptionOrNull()?.let { cause ->
                // 사유마다 사용자가 할 일이 다르다 — 만료·초과는 재발송해야 빠져나온다 (§1-4).
                val needsResend = cause.apiErrorCode() in RESEND_REQUIRED_CODES
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        mustResend = needsResend,
                        errorMessage = verifyFailureMessage(cause),
                    )
                }
                return@launch
            }
            repository.signup(
                email = state.email.trim(),
                password = state.password,
                nickname = state.nickname.trim(),
                marketingAgreed = state.marketingAgreed,
            ).fold(
                onSuccess = { session ->
                    // 201 = 자동 로그인 (명세 §1-5). 응답의 user 를 그대로 쓴다.
                    // 마케팅 동의는 가입 때 고른 값이다 — 계정 관리 토글의 초기값이 된다
                    SessionStore.signIn(
                        session.profile.copy(marketingAgreed = state.marketingAgreed),
                        tokens = session.tokens,
                    )
                    _uiState.update { it.copy(isSubmitting = false, step = SignupStep.DONE) }
                },
                onFailure = {
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = "가입에 실패했어요. 다시 시도해 주세요.")
                    }
                },
            )
        }
    }

    /** 완료 화면의 [시작하기]. */
    fun onStart() {
        _uiState.update { it.copy(completed = true) }
    }

    /**
     * 단계 역행. 처리했으면 true, 첫 단계(또는 완료)라 화면이 pop 해야 하면 false.
     *
     * 완료 단계에서는 뒤로가 아니라 시작으로만 나간다 — 가입이 이미 확정됐기 때문이다.
     */
    fun onStepBack(): Boolean = when (_uiState.value.step) {
        SignupStep.AGREE, SignupStep.DONE -> false
        SignupStep.INFO -> {
            _uiState.update { it.copy(step = SignupStep.AGREE, errorMessage = null) }
            true
        }
        SignupStep.VERIFY -> {
            _uiState.update { it.copy(step = SignupStep.INFO, errorMessage = null, code = "") }
            true
        }
    }

    /**
     * 재발송 쿨다운 카운트다운. **이전 타이머는 반드시 끊는다.**
     *
     * 3단계에서 뒤로 갔다 다시 들어오면 타이머가 겹쳐 1초에 2씩 줄어든다 — 60초 쿨다운이
     * 30초가 되어 NFR-10 을 어긴다.
     */
    private fun startResendCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            _uiState.update { it.copy(resendCooldownSec = RESEND_COOLDOWN_SEC) }
            while (_uiState.value.resendCooldownSec > 0) {
                delay(1_000)
                _uiState.update { it.copy(resendCooldownSec = it.resendCooldownSec - 1) }
            }
        }
    }

    /** 코드 검증 실패 문구. 사유별로 사용자가 할 일이 다르다. (§1-4 · `ApiErrorCode`) */
    private fun verifyFailureMessage(cause: Throwable): String = when {
        cause.isNetworkFailure() -> "네트워크에 연결되지 않았어요. 연결을 확인해 주세요."
        cause.apiErrorCode() == ApiErrorCode.CODE_EXPIRED ->
            "인증 코드가 만료됐어요. 메일을 다시 받아 주세요."
        cause.apiErrorCode() == ApiErrorCode.TOO_MANY_ATTEMPTS ->
            "여러 번 틀렸어요. 메일을 다시 받아 주세요."
        cause.apiErrorCode() == ApiErrorCode.INVALID_CODE ->
            "인증 코드가 맞지 않아요. 다시 확인해 주세요."
        else -> "인증에 실패했어요. 잠시 후 다시 시도해 주세요."
    }

    /** 인증 메일 발송 실패 문구. */
    private fun sendFailureMessage(cause: Throwable): String = when {
        cause.isNetworkFailure() -> "네트워크에 연결되지 않았어요. 연결을 확인해 주세요."
        cause.apiErrorCode() == ApiErrorCode.EMAIL_DUPLICATED -> "이미 가입된 이메일이에요."
        cause.apiErrorCode() == ApiErrorCode.SEND_COOLDOWN -> "잠시 후 다시 시도해 주세요."
        else -> "인증 메일을 보내지 못했어요. 다시 시도해 주세요."
    }

    private companion object {
        /** SPEC §4.2-3 — 재발송 쿨다운 60초. */
        const val RESEND_COOLDOWN_SEC = 60

        /** 이 사유들은 같은 코드로 재시도해도 소용없다 — 재발송해야 한다. (§1-4 · NFR-10 🔒) */
        val RESEND_REQUIRED_CODES = setOf(
            ApiErrorCode.CODE_EXPIRED,
            ApiErrorCode.TOO_MANY_ATTEMPTS,
        )
    }
}
