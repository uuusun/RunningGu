package com.runninggu.app.ui.auth

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

/**
 * A2 회원가입 단계. (SPEC §4.2 — 동의 → 정보 입력 → 이메일 인증 → 완료)
 */
enum class SignupStep { AGREE, INFO, VERIFY, DONE }

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

    val canProceedInfo: Boolean
        get() = !isSubmitting && isEmailValid && isPasswordValid && isPasswordConfirmed && isNicknameValid

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

    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, errorMessage = null) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, errorMessage = null) }
    fun onPasswordConfirmChange(value: String) = _uiState.update { it.copy(passwordConfirm = value, errorMessage = null) }
    fun onNicknameChange(value: String) = _uiState.update { it.copy(nickname = value, errorMessage = null) }

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
                onSuccess = { tokens ->
                    // 201 = 자동 로그인 (명세 §1-5). TODO(AP-14): 응답의 user 로 채운다.
                    SessionStore.signIn(
                        SessionProfile(
                            nickname = state.nickname.trim(),
                            email = state.email.trim(),
                            loginProvider = LoginProvider.EMAIL,
                            // 가입 때 고른 값을 그대로 물려준다 — 계정 관리 토글의 초기값이 된다.
                            marketingAgreed = state.marketingAgreed,
                        ),
                        tokens = tokens,
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
