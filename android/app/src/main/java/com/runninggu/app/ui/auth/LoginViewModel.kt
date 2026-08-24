package com.runninggu.app.ui.auth

import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.repository.AuthRepository
import com.runninggu.app.data.repository.isNetworkFailure
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.apiErrorCode
import com.runninggu.app.data.local.SessionStore

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
) {
    val canSubmit: Boolean
        get() = !isSubmitting && AuthValidation.isEmailValid(email) && password.isNotEmpty()
}

/** A1 로그인. (SPEC §4.1 · AP-08) */
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
