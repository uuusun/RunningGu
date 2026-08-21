package com.runninggu.app.ui.auth

import com.runninggu.app.data.repository.AuthRepository
import com.runninggu.app.data.repository.FakeAuthRepository
import com.runninggu.app.data.repository.isNetworkFailure
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
    /**
     * 통신 자체가 실패했다.
     *
     * §4.3 의 계정 존재 비노출은 **서버가 가입 여부와 무관하게 202 를 준다**는 뜻이지,
     * 통신 실패까지 성공으로 보이라는 게 아니다. 비행기 모드에서 "보냈어요" 를 띄우면
     * 사용자가 오지 않을 메일을 기다린다.
     */
    val errorMessage: String? = null,
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
        _uiState.update { it.copy(email = value, errorMessage = null) }
    }

    fun onSubmit() {
        val state = _uiState.value
        if (!state.canSubmit) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            _uiState.update {
                repository.requestPasswordReset(state.email.trim()).fold(
                    // 가입 여부는 서버가 항상 202 로 감춘다 — 앱이 응답을 더 가릴 것은 없다(§1-11).
                    onSuccess = { _ -> it.copy(isSubmitting = false, sent = true) },
                    onFailure = { cause ->
                        it.copy(
                            isSubmitting = false,
                            errorMessage = if (cause.isNetworkFailure()) {
                                "네트워크에 연결되지 않았어요. 연결을 확인해 주세요."
                            } else {
                                "메일을 보내지 못했어요. 잠시 후 다시 시도해 주세요."
                            },
                        )
                    },
                )
            }
        }
    }
}
