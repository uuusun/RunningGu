package com.runninggu.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
        get() = !isSubmitting && AuthValidation.isCodeValid(code)
}

/** A2 회원가입. (SPEC §4.2 · AP-08) */
class SignupViewModel(
    private val repository: AuthRepository = FakeAuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SignupUiState())
    val uiState: StateFlow<SignupUiState> = _uiState.asStateFlow()

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
                onFailure = {
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = "인증 메일을 보내지 못했어요. 다시 시도해 주세요.")
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
            repository.sendSignupCode(state.email.trim())
            startResendCooldown()
        }
    }

    /** 코드 검증 → 가입 확정. 성공 시 자동 로그인(명세 §1-5). (SPEC §4.2-3·4) */
    fun onVerify() {
        val state = _uiState.value
        if (!state.canVerify) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val verified = repository.verifySignupCode(state.email.trim(), state.code)
            if (verified.isFailure) {
                _uiState.update {
                    it.copy(isSubmitting = false, errorMessage = "인증 코드가 맞지 않아요. 다시 확인해 주세요.")
                }
                return@launch
            }
            repository.signup(
                email = state.email.trim(),
                password = state.password,
                nickname = state.nickname.trim(),
                marketingAgreed = state.marketingAgreed,
            ).fold(
                onSuccess = { _uiState.update { it.copy(isSubmitting = false, step = SignupStep.DONE) } },
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

    private fun startResendCooldown() {
        viewModelScope.launch {
            _uiState.update { it.copy(resendCooldownSec = RESEND_COOLDOWN_SEC) }
            while (_uiState.value.resendCooldownSec > 0) {
                delay(1_000)
                _uiState.update { it.copy(resendCooldownSec = it.resendCooldownSec - 1) }
            }
        }
    }

    private companion object {
        /** SPEC §4.2-3 — 재발송 쿨다운 60초. */
        const val RESEND_COOLDOWN_SEC = 60
    }
}
