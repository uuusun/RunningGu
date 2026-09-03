package com.runninggu.app.ui.auth

import com.runninggu.app.ui.OFFLINE
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.apiErrorCode
import com.runninggu.app.data.repository.AuthRepository
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
    /**
     * 서버 저장소. `POST /auth/password/reset-request` 를 부른다. (§1-11 · AP-14)
     *
     * #174 · #182 로 서버가 서서 A1·A2 와 같은 저장소를 본다. 그전에는
     * `FakeAuthRepository` 였다 — 엔드포인트가 없어 붙이면 404 로 떨어졌다.
     */
    private val repository: AuthRepository = ServiceLocator.authRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResetUiState())
    val uiState: StateFlow<ResetUiState> = _uiState.asStateFlow()

    /**
     * 발송 실패 문구. (§1-11 · §4.3)
     *
     * **쿨다운을 일반 실패로 덮지 않는다.** 서버는 60초 쿨다운을 `429 SEND_COOLDOWN` 으로
     * 주는데(§1-11), 이때는 **직전 요청이 이미 나갔다는 뜻**이다. "보내지 못했어요" 로
     * 뭉치면 사용자는 실패한 줄 알고 버튼을 계속 누르고, 그 요청이 또 쿨다운에 걸려
     * 상황이 안 풀린다 — A1 의 `RATE_LIMITED` 를 가른 것과 같은 이유다(결정-55).
     *
     * 쿨다운은 **가입 여부와 무관하게** 걸린다(`PasswordResetService.request` 가 계정을
     * 찾기 전에 `cooldown.acquire` 한다). 그래서 이 문구가 계정 존재를 노출하지 않는다 —
     * §4.3-1 의 비노출은 여기서도 지켜진다.
     */
    private fun Throwable.resetFailureMessage(): String = when {
        isNetworkFailure() -> OFFLINE
        apiErrorCode() == ApiErrorCode.SEND_COOLDOWN ->
            "조금 전에 보냈어요. 메일함을 확인하고, 없으면 1분 뒤에 다시 시도해 주세요."
        else -> "메일을 보내지 못했어요. 잠시 후 다시 시도해 주세요."
    }

    /**
     * **보내는 중에는 무시한다.** (#187 리뷰)
     *
     * 발송 중에 이메일이 바뀌면 **요청한 주소와 화면에 보이는 주소가 엇갈린다** — 사용자는
     * 새 주소로 메일이 갔다고 읽는다. 버튼도 잠겨 있어(`canSubmit`) 다시 보낼 수도 없다.
     */
    fun onEmailChange(value: String) {
        _uiState.update {
            if (it.isSubmitting) it else it.copy(email = value, errorMessage = null)
        }
    }

    fun onSubmit() {
        val state = _uiState.value
        if (!state.canSubmit) return
        // **잠금은 여기서, 코루틴 밖에서 건다.** `launch` 안에서 걸면 코루틴이 실제로 돌기
        // 전에 두 번째 탭이 [ResetUiState.canSubmit] 를 그대로 통과한다.
        //
        // 스텁일 때는 티가 안 났지만 서버는 **60초 쿨다운**이다(§1-11). 연타하면 두 번째
        // 요청이 `429 SEND_COOLDOWN` 을 받고, **첫 요청이 성공해 메일이 갔는데도** 화면은
        // 쿨다운 문구를 띄운다.
        _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            // **호출을 `update` 밖에서 한 번만 한다.** (#187 리뷰)
            //
            // `MutableStateFlow.update` 는 CAS 에 실패하면 **람다를 다시 평가한다.** 안에
            // 네트워크 호출을 두면 그때 요청이 한 번 더 나간다 — 첫 요청이 성공해 메일이
            // 갔는데도 두 번째가 `429 SEND_COOLDOWN` 을 받아 화면은 쿨다운 문구가 된다.
            val outcome = repository.requestPasswordReset(state.email.trim())
            _uiState.update {
                outcome.fold(
                    // 가입 여부는 서버가 항상 202 로 감춘다 — 앱이 응답을 더 가릴 것은 없다(§1-11).
                    onSuccess = { _ -> it.copy(isSubmitting = false, sent = true) },
                    onFailure = { cause ->
                        it.copy(
                            isSubmitting = false,
                            errorMessage = cause.resetFailureMessage(),
                        )
                    },
                )
            }
        }
    }
}
