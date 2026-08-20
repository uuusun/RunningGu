package com.runninggu.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
) {
    val canSubmit: Boolean
        get() = !isSubmitting && AuthValidation.isEmailValid(email) && password.isNotEmpty()
}

/** A1 로그인. (SPEC §4.1 · AP-08) */
class LoginViewModel(
    private val repository: AuthRepository = FakeAuthRepository,
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
                    onSuccess = { _ -> it.copy(isSubmitting = false, loggedIn = true) },
                    onFailure = { cause ->
                        it.copy(
                            isSubmitting = false,
                            // 인증 실패는 사유를 감추지만(§4.1), 통신 실패까지 같은 문구로
                            // 덮으면 사용자가 비밀번호를 계속 고쳐 입력하게 된다.
                            errorMessage = if (cause.isNetworkFailure()) {
                                "네트워크에 연결되지 않았어요. 연결을 확인해 주세요."
                            } else {
                                "이메일 또는 비밀번호를 확인해 주세요"
                            },
                        )
                    },
                )
            }
        }
    }
}
