package com.runninggu.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A3 비밀번호 찾기의 UI 계약. (SPEC §4.3 — 재설정 링크 방식 🔒확정)
 *
 * 새 비밀번호 설정은 앱 화면이 아니다 — 링크가 여는 **백엔드 웹 페이지**가 담당한다
 * (명세 §1-11·1-12, MVP 🔧정책). 앱은 메일 발송 요청까지만 한다.
 */
data class ResetUiState(
    val email: String = "",
    val isSubmitting: Boolean = false,
    /** 발송 완료. 가입 여부와 무관하게 같은 안내를 띄운다 — 계정 존재 비노출(§4.3). */
    val sent: Boolean = false,
) {
    val canSubmit: Boolean
        get() = !isSubmitting && !sent && AuthValidation.isEmailValid(email)
}

/** A3 비밀번호 찾기. (SPEC §4.3 · AP-08) */
class ResetViewModel(
    private val repository: AuthRepository = FakeAuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResetUiState())
    val uiState: StateFlow<ResetUiState> = _uiState.asStateFlow()

    fun onEmailChange(value: String) {
        _uiState.update { it.copy(email = value) }
    }

    fun onSubmit() {
        val state = _uiState.value
        if (!state.canSubmit) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true) }
            // 실패해도 같은 안내를 띄운다 — 응답 차이로 계정 존재가 새면 안 된다(§4.3 · 명세 §1-11).
            repository.requestPasswordReset(state.email.trim())
            _uiState.update { it.copy(isSubmitting = false, sent = true) }
        }
    }
}
