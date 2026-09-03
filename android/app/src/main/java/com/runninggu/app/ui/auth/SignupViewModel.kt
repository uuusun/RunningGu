package com.runninggu.app.ui.auth

import com.runninggu.app.ui.OFFLINE
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.repository.AuthRepository
import com.runninggu.app.data.remote.apiErrorCode
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
 * 이메일·닉네임 중복 확인 상태. (화면-API 매핑표 D-30 · SPEC 결정-50 · 이슈 #97)
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

    /**
     * 카카오 가입이면 SDK 토큰이 들어 있다. `null` 이면 이메일 가입이다. (§1-7 → §1-8)
     *
     * 카카오는 **이메일·비밀번호를 받지 않고 인증 단계도 없다** — 카카오가 이미 확인한
     * 계정이라 `VERIFY` 를 건너뛴다(§1-8).
     */
    val kakaoAccessToken: String? = null,

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
    /**
     * **인증을 마친 이메일.** (API 명세 §1-4)
     *
     * 닉네임이 겹쳐 뒤로 갔다 오는 길에서 쓴다 — 이 값이 지금 이메일과 같으면 인증이
     * 아직 살아 있으므로 **재발송하지 않는다.** §1-3 이 *"재발송은 이전 코드·검증 상태를
     * 무효화한다"* 고 정해 두어서, 새로 보내면 30분 남은 인증을 스스로 버리게 된다(#235 리뷰).
     */
    val verifiedEmail: String? = null,

    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    /** 가입 완료 + 자동 로그인(명세 §1-5). 화면이 이걸 보고 `home` 으로 나간다. */
    val completed: Boolean = false,
) {
    /** 카카오 가입인가. 화면이 이메일·비밀번호 칸과 인증 단계를 이걸로 가린다. */
    val isKakao: Boolean get() = kakaoAccessToken != null

    val allAgreed: Boolean get() = tosAgreed && privacyAgreed && marketingAgreed

    /** 필수 2종 동의. 미동의면 진행 불가(SPEC §4.2-1). */
    val canProceedAgree: Boolean get() = tosAgreed && privacyAgreed

    val isEmailValid: Boolean get() = AuthValidation.isEmailValid(email)
    val isPasswordValid: Boolean get() = passwordIssue == null

    /** 어긋난 사유. 화면이 인라인 문구를 고르는 데 쓴다(§4.2-2 🔒). */
    val passwordIssue: PasswordIssue? get() = AuthValidation.passwordIssue(password)
    val isPasswordConfirmed: Boolean get() = passwordConfirm.isNotEmpty() && password == passwordConfirm
    val isNicknameValid: Boolean get() = AuthValidation.isNicknameValid(nickname)

    /**
     * [인증 메일 발송] 활성 조건. (D-30)
     *
     * 중복 확인은 **`Checking` 과 `Duplicate` 만 막는다.** `Unchecked`(아직 포커스가 안 빠짐)
     * 와 `Error`(조회 실패)는 서버 유니크 방어를 믿고 통과시킨다 — 여기서 막으면 확인 API 가
     * 죽었을 때 아무도 가입할 수 없다.
     */
    /**
     * [다음] 활성 조건.
     *
     * **카카오는 닉네임만 본다.** 이메일·비밀번호를 받지 않으므로(§1-8) 그 조건을 그대로
     * 두면 영영 false 다 — 사용자가 아무리 채워도 버튼이 안 열린다.
     */
    val canProceedInfo: Boolean
        get() = when {
            isSubmitting -> false
            isKakao -> isNicknameValid && nicknameCheck.allowsSubmit
            else -> isEmailValid && isPasswordValid && isPasswordConfirmed &&
                isNicknameValid && emailCheck.allowsSubmit && nicknameCheck.allowsSubmit
        }

    val canVerify: Boolean
        get() = !isSubmitting && !mustResend && AuthValidation.isCodeValid(code)
}

/** A2 회원가입. (SPEC §4.2 · AP-08) */
class SignupViewModel(
    /**
     * 기본은 **서버 저장소**다. (AP-14 · AGENTS 2장-2)
     *
     * 테스트·미리보기에서는 생성자로 가짜 저장소를 바꿔 끼운다 — 화면은 안 건드린다.
     * [AuthRepository] 인터페이스만 보기 때문이다(AGENTS 4장).
     */
    private val repository: AuthRepository = ServiceLocator.authRepository,
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
        // 값이 그대로면 다시 묻지 않는다. A2 는 네 칸이 한 화면에 있어 오가는 게 자연스러운데,
        // 그때마다 부르면 **값별 5회/분 제한**에 걸려 `Available` 이던 자리가 `Error` 로
        // 퇴행한다(#132 리뷰). `onEmailChange` 가 값이 바뀔 때 `Unchecked` 로 되돌리므로
        // 이 조건은 "바뀐 뒤 첫 이탈" 만 통과시킨다. `Error` 는 재시도할 여지를 남긴다.
        if (_uiState.value.emailCheck !in RECHECKABLE) return
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
        if (_uiState.value.nicknameCheck !in RECHECKABLE) return
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

    /**
     * 정보 입력 완료 → 인증 코드 발송 후 3단계로. (SPEC §4.2-3)
     *
     * **카카오는 여기서 끝난다.** 이메일 인증이 필요 없어(카카오가 이미 확인한 계정) 곧바로
     * 가입을 부르고 `DONE` 으로 간다(§1-8).
     */
    fun onInfoNext() {
        val state = _uiState.value
        if (!state.canProceedInfo) return
        state.kakaoAccessToken?.let { token ->
            submitKakaoSignup(token, state)
            return
        }
        // **인증이 아직 살아 있으면 새로 보내지 않는다.** 닉네임이 겹쳐 뒤로 갔다 오는
        // 길이 여기다. §1-3 상 재발송은 이전 검증 상태를 무효화하므로, 새로 보내면
        // 30분 남은 인증을 스스로 버리고 코드를 다시 받아 입력해야 한다(#235 리뷰).
        // 같은 코드 재확인은 §1-4 가 30분 동안 멱등 `200` 으로 보장한다.
        if (state.email.trim() == state.verifiedEmail) {
            _uiState.update { it.copy(step = SignupStep.VERIFY, errorMessage = null) }
            return
        }

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
                    // **이전 검증 상태도 무효가 된다**(§1-3) — 들고 있던 것을 버린다
                    _uiState.update {
                        it.copy(mustResend = false, code = "", errorMessage = null, verifiedEmail = null)
                    }
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
            // 인증이 살아 있다는 것을 남긴다 — 뒤로 갔다 와도 재발송 없이 재제출한다
            _uiState.update { it.copy(verifiedEmail = state.email.trim()) }

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
                onFailure = { cause ->
                    // **인증이 풀렸으면 타이머부터 멈춘다.** 값만 0 으로 바꾸면 자고 있던
                    // 타이머가 깨어나 1 을 빼서 -1 이 되고, 화면은 `== 0` 일 때만 [재발송]
                    // 을 열어서 **버튼이 영영 잠긴다**(#235 리뷰)
                    if (cause.apiErrorCode() in VERIFICATION_LOST) clearResendCooldown()

                    // **사유마다 나가는 길이 다르다.** 여기서 뭉뚱그리면 사용자는 같은
                    // 버튼만 계속 누르게 된다 — VERIFY 화면에는 닉네임도 이메일도 없다
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            // 인증이 풀린 것은 재발송해야 빠져나온다 (§1-4 와 같은 처리).
                            // **여기 오는 시점의 `mustResend` 는 항상 false 다** —
                            // `canVerify` 가 `!mustResend` 로 막고 있어서다(#235 리뷰).
                            mustResend = cause.apiErrorCode() in VERIFICATION_LOST,
                            // 뒤로 돌아갔을 때 그 칸이 이미 빨갛게 보이도록 결과를 남긴다
                            nicknameCheck = if (cause.apiErrorCode() == ApiErrorCode.NICKNAME_DUPLICATED) {
                                DuplicateCheck.Duplicate
                            } else {
                                it.nicknameCheck
                            },
                            emailCheck = if (cause.apiErrorCode() == ApiErrorCode.EMAIL_DUPLICATED) {
                                DuplicateCheck.Duplicate
                            } else {
                                it.emailCheck
                            },
                            errorMessage = signupFailureMessage(cause),
                        )
                    }
                },
            )
        }
    }

    /**
     * 가입 확정 실패 문구. (API 명세 §1-5)
     *
     * **[뒤로] 로 앞 단계에 가야 풀리는 것들이 있다.** VERIFY 화면에는 [인증 확인] 버튼
     * 하나뿐이라, 닉네임이 겹쳤다는 말을 안 하면 사용자는 같은 버튼만 누르며 영원히 같은
     * 409 를 받는다(#227 리뷰).
     *
     * 특히 [NICKNAME_DUPLICATED] 는 **설계가 최종 방어로 삼은 것**이다 — 중복확인은
     * 진행을 막지 않기로 했고([DuplicateCheck] KDoc), 확인과 제출 사이에 남이 먼저
     * 가입하는 경우를 여기서 잡는다. 그 마지막 방어가 원인 불명으로 도착하면 안 된다.
     */
    private fun signupFailureMessage(cause: Throwable): String = when {
        cause.isNetworkFailure() -> OFFLINE

        cause.apiErrorCode() == ApiErrorCode.NICKNAME_DUPLICATED ->
            "이미 쓰는 닉네임이에요. [뒤로] 를 눌러 다른 닉네임으로 바꿔 주세요."

        cause.apiErrorCode() == ApiErrorCode.EMAIL_DUPLICATED ->
            "이미 가입된 이메일이에요. 로그인 화면에서 로그인해 주세요."

        // **인증이 풀린 것이 둘이다** (§1-5 · #237 로 계약이 갈렸다).
        //
        //   403 EMAIL_NOT_VERIFIED  행은 있는데 아직 인증 전
        //   400 CODE_EXPIRED        인증은 했는데 30분이 지났거나 행이 없다
        //
        // 앱에서 실제로 닿는 것은 **뒤쪽**이다 — 닉네임이 겹쳐 뒤로 갔다 오는 사이
        // 30분이 지나면 여기로 온다. 앞쪽은 인증 직후 곧바로 가입해서 닿기 어렵지만
        // 구버전 앱이나 검증을 건너뛴 요청에서 올 수 있어 함께 매핑한다.
        //
        // 사용자가 할 일은 둘 다 같다 — 메일을 다시 받는 것이다.
        cause.apiErrorCode() == ApiErrorCode.EMAIL_NOT_VERIFIED ||
            cause.apiErrorCode() == ApiErrorCode.CODE_EXPIRED ->
            "인증이 만료됐어요. 아래 [재발송] 으로 메일을 다시 받아 주세요."

        cause.apiErrorCode() == ApiErrorCode.INVALID_PASSWORD ->
            "비밀번호가 조건에 맞지 않아요. [뒤로] 를 눌러 다시 정해 주세요."

        cause.apiErrorCode() == ApiErrorCode.AGREEMENT_REQUIRED ->
            "필수 약관에 동의해야 가입할 수 있어요."

        else -> "가입에 실패했어요. 다시 시도해 주세요."
    }

    /**
     * 카카오 가입. (`POST /auth/kakao/signup` · §1-8)
     *
     * 이메일 가입과 다른 점은 둘이다 — **인증 단계가 없고**(카카오가 확인한 계정),
     * **비밀번호가 없다.** 응답은 §1-5 와 같아서 가입이 곧 로그인이다.
     *
     * 닉네임은 카카오가 준 것을 그대로 쓰지 않고 **사용자가 A2 에서 확정한 값**을 보낸다 —
     * 없을 수도 있고(동의 항목) 중복일 수도 있어서 화면이 한 번 받는다(#211 계약).
     */
    private fun submitKakaoSignup(token: String, state: SignupUiState) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            repository.kakaoSignup(
                kakaoAccessToken = token,
                nickname = state.nickname.trim(),
                marketingAgreed = state.marketingAgreed,
            ).fold(
                onSuccess = { session ->
                    SessionStore.signIn(
                        session.profile.copy(marketingAgreed = state.marketingAgreed),
                        tokens = session.tokens,
                    )
                    _uiState.update { it.copy(isSubmitting = false, step = SignupStep.DONE) }
                },
                onFailure = { cause ->
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = cause.kakaoSignupMessage())
                    }
                },
            )
        }
    }

    /**
     * 카카오 가입 실패 문구. **다시 눌러서 풀리는 것과 아닌 것을 가른다** (§1-8 · #216 리뷰).
     *
     * | 코드 | 사용자가 할 일 |
     * |---|---|
     * | `409 KAKAO_ACCOUNT_DUPLICATED` | **이미 가입돼 있다.** A1 로 돌아가 로그인한다 |
     * | `401 INVALID_KAKAO_TOKEN` | 닉네임 고르는 사이 토큰이 만료됐다. **A1 부터 다시** 한다 |
     *
     * 둘 다 **같은 버튼으로는 영영 안 풀린다.** 서버가 중복 시 자동 로그인을 시켜 주지
     * 않기로 했으므로(§1-8), 뭉뚱그리면 사용자가 A2 에 갇힌 채 같은 버튼만 누른다.
     *
     * 이메일 가입(`submit`)도 아직 한 줄이지만 이 PR 이 만든 자리가 아니라 두었다.
     */
    private fun Throwable.kakaoSignupMessage(): String = when (apiErrorCode()) {
        ApiErrorCode.KAKAO_ACCOUNT_DUPLICATED ->
            "이미 가입된 카카오 계정이에요. 로그인 화면에서 [카카오로 시작하기]를 눌러 주세요."
        ApiErrorCode.INVALID_KAKAO_TOKEN ->
            "카카오 인증이 만료됐어요. 로그인 화면에서 다시 시작해 주세요."
        else -> if (isNetworkFailure()) {
            OFFLINE
        } else {
            "가입에 실패했어요. 다시 시도해 주세요."
        }
    }

    /**
     * 카카오에서 넘어온 값으로 시작한다. (§1-7 → §1-8)
     *
     * **한 번만 채운다.** 화면 회전 등으로 다시 불리면 사용자가 고쳐 둔 닉네임을 카카오가
     * 준 값으로 되돌리게 된다.
     */
    fun startKakaoSignup(kakaoAccessToken: String, nickname: String?, email: String?) {
        if (_uiState.value.kakaoAccessToken != null) return
        _uiState.update {
            it.copy(
                kakaoAccessToken = kakaoAccessToken,
                // 카카오가 안 줬으면(동의 항목) 빈 칸으로 둔다 — 사용자가 직접 넣는다
                nickname = nickname.orEmpty(),
                email = email.orEmpty(),
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
            // 인증이 살아 있으면 코드를 들고 간다 — 돌아와서 그대로 [인증 확인] 을 누르면
            // §1-4 의 멱등 `200` 으로 재발송 없이 가입을 다시 시도할 수 있다(#235 리뷰)
            _uiState.update {
                val keepCode = it.email.trim() == it.verifiedEmail
                it.copy(
                    step = SignupStep.INFO,
                    errorMessage = null,
                    code = if (keepCode) it.code else "",
                )
            }
            true
        }
    }

    /**
     * 재발송 쿨다운 카운트다운. **이전 타이머는 반드시 끊는다.**
     *
     * 3단계에서 뒤로 갔다 다시 들어오면 타이머가 겹쳐 1초에 2씩 줄어든다 — 60초 쿨다운이
     * 30초가 되어 NFR-10 을 어긴다.
     */
    /**
     * 쿨다운을 **지금 끝낸다.** (#235 리뷰)
     *
     * "메일을 다시 받아 주세요" 를 띄우면서 [재발송] 이 최대 60초 잠겨 있으면, 시키는
     * 일을 할 수단이 없는 화면이 된다. 그래서 인증이 풀렸을 때 쿨다운을 면제한다 —
     * 인증이 실제로 풀렸다면 마지막 발송에서 30분이 지났으므로(§1-4) 서버 쿨다운도
     * 끝나 있다.
     *
     * **값만 0 으로 바꾸면 안 된다.** 타이머가 `delay(1_000)` 에서 자고 있다가 깨어나
     * 조건을 다시 보지 않고 1 을 뺀다. 그러면 `-1` 이 되고, 화면은 `== 0` 일 때만
     * [재발송] 을 열어서 **버튼이 영영 잠긴다.**
     */
    private fun clearResendCooldown() {
        cooldownJob?.cancel()
        cooldownJob = null
        _uiState.update { it.copy(resendCooldownSec = 0) }
    }

    private fun startResendCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            _uiState.update { it.copy(resendCooldownSec = RESEND_COOLDOWN_SEC) }
            while (_uiState.value.resendCooldownSec > 0) {
                delay(1_000)
                // 0 아래로 내려가지 않게 막는다. [clearResendCooldown] 과 이중으로 지킨다 —
            // 취소가 한 박자 늦어도 화면이 잠기지 않는다
            _uiState.update { it.copy(resendCooldownSec = (it.resendCooldownSec - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** 코드 검증 실패 문구. 사유별로 사용자가 할 일이 다르다. (§1-4 · `ApiErrorCode`) */
    private fun verifyFailureMessage(cause: Throwable): String = when {
        cause.isNetworkFailure() -> OFFLINE
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
        cause.isNetworkFailure() -> OFFLINE
        cause.apiErrorCode() == ApiErrorCode.EMAIL_DUPLICATED -> "이미 가입된 이메일이에요."
        cause.apiErrorCode() == ApiErrorCode.SEND_COOLDOWN -> "잠시 후 다시 시도해 주세요."
        else -> "인증 메일을 보내지 못했어요. 다시 시도해 주세요."
    }

    private companion object {
        /**
         * 포커스가 빠질 때 **다시 물어도 되는** 상태. (#132 리뷰)
         *
         * `Unchecked` 는 아직 안 물었거나 값이 바뀐 것이고, `Error` 는 못 물어본 것이라
         * 재시도할 값어치가 있다. `Available`·`Duplicate` 는 이미 답을 아는 값이고,
         * `Checking` 은 묻는 중이다.
         */
        val RECHECKABLE = setOf(DuplicateCheck.Unchecked, DuplicateCheck.Error)

        /** SPEC §4.2-3 — 재발송 쿨다운 60초. */
        const val RESEND_COOLDOWN_SEC = 60

        /**
         * 가입 확정에서 **인증이 풀렸다**는 두 코드. (§1-5)
         *
         * `403` 은 아직 인증 전, `400 CODE_EXPIRED` 는 인증 후 30분이 지났거나 행이
         * 없는 것이다. 사용자가 할 일은 둘 다 재발송이라 같이 다룬다(#237 계약).
         */
        val VERIFICATION_LOST = setOf(
            ApiErrorCode.EMAIL_NOT_VERIFIED,
            ApiErrorCode.CODE_EXPIRED,
        )

        /** 이 사유들은 같은 코드로 재시도해도 소용없다 — 재발송해야 한다. (§1-4 · NFR-10 🔒) */
        val RESEND_REQUIRED_CODES = setOf(
            ApiErrorCode.CODE_EXPIRED,
            ApiErrorCode.TOO_MANY_ATTEMPTS,
        )
    }
}
