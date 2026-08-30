package com.runninggu.app.ui.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.apiErrorCode
import com.runninggu.app.data.repository.AuthRepository
import com.runninggu.app.data.repository.KakaoLoginOutcome
import com.runninggu.app.data.repository.isNetworkFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A1 로그인의 UI 계약. (SPEC §4.1)
 *
 * @param loggedIn 로그인 성공. 화면이 이 플래그를 보고 `home` 으로 나간다(백스택 클리어).
 */
data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    /** 인라인 오류. 실패 사유를 구분하지 않는다 — 계정 존재 비노출(§4.1). */
    val errorMessage: String? = null,
    val loggedIn: Boolean = false,
    /**
     * 카카오 미가입자다. A2 로 보내며 이 값을 들고 간다. (§1-7 → §1-8)
     *
     * `null` 이면 갈 곳이 없다. 화면이 이 값을 보고 한 번만 이동한다.
     */
    val kakaoSignup: KakaoSignupHandoff? = null,
) {
    val canSubmit: Boolean
        get() = !isSubmitting && AuthValidation.isEmailValid(email) && password.isNotEmpty()
}

/** A1 로그인. (SPEC §4.1 · AP-08) */
/**
 * A1 → A2 로 넘기는 카카오 가입 정보. (§1-7 · §1-8)
 *
 * [kakaoAccessToken] 은 A2 의 `kakaoSignup` 이 다시 요구한다. 화면이 따로 보관하지 않고
 * 결과에 실려 온 것을 그대로 들고 간다(#211 계약).
 *
 * [nickname] · [email] 은 **둘 다 없을 수 있다** — 카카오 동의 항목에 달렸다(§1-7).
 * 가입 화면의 초기값으로만 쓰고, 없으면 사용자가 직접 넣는다.
 */
data class KakaoSignupHandoff(
    val kakaoAccessToken: String,
    val nickname: String?,
    val email: String?,
)

class LoginViewModel(
    /**
     * 기본은 **서버 저장소**다. (AP-14 · AGENTS 2장-2)
     *
     * 테스트·미리보기에서는 생성자로 가짜 저장소를 바꿔 끼운다 — 화면은 안 건드린다.
     * [AuthRepository] 인터페이스만 보기 때문이다(AGENTS 4장).
     */
    private val repository: AuthRepository = ServiceLocator.authRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onEmailChange(value: String) {
        _uiState.update { it.copy(email = value, errorMessage = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null) }
    }

    fun onSubmit() {
        val state = _uiState.value
        if (!state.canSubmit) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val outcome = repository.login(state.email.trim(), state.password)
            _uiState.update {
                outcome.fold(
                    onSuccess = { session ->
                        // 응답의 user 를 그대로 쓴다 (명세 §1-6). 이메일에서 닉네임을
                        // 파생하면 서버가 아는 진짜 이름과 달라진다
                        SessionStore.signIn(session.profile, tokens = session.tokens)
                        // 찜 캐시는 FavoriteStore 가 세션을 구독해 스스로 채운다 —
                        // 여기서 부르면 화면 전환으로 이 ViewModel 이 죽을 때 취소된다.
                        it.copy(isSubmitting = false, loggedIn = true)
                    },
                    onFailure = { cause ->
                        it.copy(
                            isSubmitting = false,
                            // 인증 실패는 사유를 감추지만(§4.1), 통신 실패까지 같은 문구로
                            // 덮으면 사용자가 비밀번호를 계속 고쳐 입력하게 된다.
                            errorMessage = cause.loginMessage(),
                        )
                    },
                )
            }
        }
    }

    /**
     * 카카오로 시작하기. (SPEC §4.1 · API 명세 §1-7 · AP-08)
     *
     * 두 걸음이다. **SDK 에서 토큰을 받고**, 그 토큰으로 **우리 서버에 묻는다.** 앱이 카카오에
     * 프로필을 직접 묻지 않는 것은 AGENTS 2장-3 이다 — 외부 API 는 서버를 거친다.
     *
     * 서버가 한 `200` 으로 두 가지를 준다. 기존 가입자면 세션이 오고, 미가입이면 프로필이
     * 온다([KakaoLoginOutcome]). 후자는 A2 로 보낸다.
     *
     * **사용자가 그만둔 것은 실패가 아니다.** 취소에 문구를 띄우면, 잘못 눌러 취소한 사람에게
     * "로그인 실패" 를 보여 주게 된다.
     */
    fun onKakaoLogin(context: Context) {
        if (_uiState.value.isSubmitting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            when (val auth = requestKakaoToken(context)) {
                is KakaoAuthResult.Cancelled ->
                    // 아무 말도 하지 않는다. 사용자가 스스로 그만둔 것이다
                    _uiState.update { it.copy(isSubmitting = false) }

                is KakaoAuthResult.Failed ->
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = KAKAO_SDK_FAILED_MESSAGE)
                    }

                is KakaoAuthResult.Token -> exchangeKakaoToken(auth.accessToken)
            }
        }
    }

    /**
     * **SDK 와 서버 사이의 경계.** SDK 가 준 토큰으로 여기서부터 우리 서버를 본다.
     *
     * [onKakaoLogin] 이 앞쪽(SDK)을 맡고 이 함수가 뒤쪽을 맡는다. 나뉘어 있는 이유는
     * 앞쪽이 `Context` 와 실제 카카오 앱을 요구해 **기기 없이는 돌릴 수 없기** 때문이다 —
     * 두 결말을 어디로 보내는지는 기기 없이 고정할 수 있어야 한다.
     */
    internal fun onKakaoToken(accessToken: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            exchangeKakaoToken(accessToken)
        }
    }

    /** SDK 토큰 → 우리 서버 세션. 미가입이면 A2 로 넘길 것을 담는다. (§1-7) */
    private suspend fun exchangeKakaoToken(accessToken: String) {
        val outcome = repository.kakaoLogin(accessToken)
        _uiState.update {
            outcome.fold(
                onSuccess = { result ->
                    when (result) {
                        is KakaoLoginOutcome.Session -> {
                            SessionStore.signIn(result.session.profile, tokens = result.session.tokens)
                            it.copy(isSubmitting = false, loggedIn = true)
                        }

                        is KakaoLoginOutcome.NewUser -> it.copy(
                            isSubmitting = false,
                            kakaoSignup = KakaoSignupHandoff(
                                kakaoAccessToken = result.kakaoAccessToken,
                                nickname = result.nickname,
                                email = result.email,
                            ),
                        )
                    }
                },
                onFailure = { cause ->
                    it.copy(isSubmitting = false, errorMessage = cause.kakaoMessage())
                },
            )
        }
    }

    /** A2 로 한 번 보내고 나면 비운다 — 돌아왔을 때 또 넘어가면 안 된다. */
    fun onKakaoSignupHandled() {
        _uiState.update { it.copy(kakaoSignup = null) }
    }
}

/** SDK 가 토큰을 못 준 경우. 서버 문제가 아니라 앱·기기 쪽이다. */
private const val KAKAO_SDK_FAILED_MESSAGE = "카카오 로그인을 시작하지 못했어요. 잠시 후 다시 시도해 주세요."

/**
 * 카카오 로그인 실패 문구. (§1-7)
 *
 * `INVALID_KAKAO_TOKEN` 은 **사용자가 할 일이 없다** — 앱이 보낸 토큰을 서버가 거절한
 * 것이라 다시 눌러 보는 것 말고는 방법이 없다. 그래도 "카카오" 를 문구에 남기는 이유는,
 * 이메일 로그인은 멀쩡하다는 것을 알려야 하기 때문이다.
 */
private fun Throwable.kakaoMessage(): String = when {
    this is ApiException.Network -> "네트워크에 연결할 수 없어요"
    else -> "카카오 로그인에 실패했어요. 잠시 후 다시 시도해 주세요."
}

/**
 * 로그인 실패 문구. (SPEC §4.1 · 결정-55 · API 명세 §1-6)
 *
 * **세 갈래다.** 셋을 하나로 묶으면 사용자가 할 일을 못 찾는다.
 *
 * | 무엇 | 왜 갈라야 하나 |
 * |---|---|
 * | 통신 실패 | 비밀번호를 계속 고쳐 입력하게 된다 |
 * | `429 RATE_LIMITED` | **고쳐도 안 풀린다.** 기다려야 한다 |
 * | 그 외(`401 LOGIN_FAILED`) | 사유는 감춘다 — 계정 존재 여부 비노출 |
 *
 * 시도 제한을 일반 실패로 덮으면 사용자는 비밀번호가 틀린 줄 알고 **다시 누른다.**
 * 그 요청이 또 IP 창에 쌓여 상황이 나빠지고, 결정-55 가 "성공 시 이메일 창만
 * 초기화하고 IP 창은 유지" 라 다른 계정으로 로그인해도 안 풀린다.
 */
private fun Throwable.loginMessage(): String = when {
    isNetworkFailure() -> "네트워크에 연결되지 않았어요. 연결을 확인해 주세요."
    apiErrorCode() == ApiErrorCode.RATE_LIMITED ->
        "로그인 시도가 많아요. 잠시 후 다시 시도해 주세요."
    else -> "이메일 또는 비밀번호를 확인해 주세요"
}
