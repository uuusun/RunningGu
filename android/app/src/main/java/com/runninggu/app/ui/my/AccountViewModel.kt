package com.runninggu.app.ui.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.ui.auth.AuthValidation
import com.runninggu.app.ui.auth.LoginProvider
import com.runninggu.app.ui.auth.SessionProfile
import com.runninggu.app.ui.auth.SessionStore
import com.runninggu.app.ui.favorite.FavoriteStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 계정 관리의 UI 계약. (SPEC §4.13 정보 수정 · D-22 · #59 단일 로그인 수단)
 *
 * @param signedOut 로그아웃·탈퇴 완료. 화면이 이걸 보고 auth 그래프로 나간다.
 */
data class AccountUiState(
    val profile: SessionProfile? = null,
    /** 마케팅 수신 동의. TODO(AP-14): `PATCH /me/agreements` 왕복으로 교체 (명세 §2). */
    val marketingAgreed: Boolean = false,
    val message: String? = null,
    val signedOut: Boolean = false,
) {
    /** 비밀번호 변경 메뉴는 EMAIL 가입자에게만 보인다 (#59 · 결정-38). */
    val showsPasswordMenu: Boolean get() = profile?.loginProvider == LoginProvider.EMAIL
}

/**
 * 계정 관리. (SPEC §4.13 · AP-13)
 *
 * 전부 Fake 처리다 — TODO(AP-14): `PATCH /me`(닉네임) · `PATCH /me/agreements` ·
 * `PUT /me/password`(EMAIL만, 토큰 쌍 재발급 D-28) · `POST /auth/logout` ·
 * `POST /me/reauth` + `DELETE /me`(탈퇴 재인증 D-23) 로 교체한다.
 */
class AccountViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            SessionStore.session.collect { profile ->
                _uiState.update { it.copy(profile = profile) }
            }
        }
    }

    /** 닉네임 변경. 규칙은 가입과 같다(2~12자). 성공 시 세션 프로필도 갱신한다. */
    fun onNicknameChange(nickname: String) {
        val trimmed = nickname.trim()
        if (!AuthValidation.isNicknameValid(trimmed)) {
            _uiState.update { it.copy(message = "닉네임은 2~12자로 지어 주세요") }
            return
        }
        val profile = _uiState.value.profile ?: return
        SessionStore.signIn(profile.copy(nickname = trimmed))
        _uiState.update { it.copy(message = "닉네임을 바꿨어요") }
    }

    fun onToggleMarketing() {
        _uiState.update {
            it.copy(
                marketingAgreed = !it.marketingAgreed,
                message = if (!it.marketingAgreed) "마케팅 수신에 동의했어요" else "마케팅 수신 동의를 철회했어요",
            )
        }
    }

    /** 비밀번호 변경 (EMAIL만). 성공 시 서버가 전 기기 로그아웃 + 현재 기기 토큰 재발급(D-28). */
    fun onChangePassword(current: String, new: String) {
        if (!AuthValidation.isPasswordValid(new)) {
            _uiState.update { it.copy(message = "새 비밀번호는 8자 이상, 영문과 숫자를 함께 써 주세요") }
            return
        }
        viewModelScope.launch {
            delay(FAKE_DELAY_MS)
            _uiState.update { it.copy(message = "비밀번호를 바꿨어요. 다른 기기는 로그아웃돼요.") }
        }
    }

    fun onLogout() {
        SessionStore.signOut()
        // 다음 사용자에게 이전 찜이 보이면 안 된다 (AP-21).
        FavoriteStore.clear()
        _uiState.update { it.copy(signedOut = true) }
    }

    /** 회원 탈퇴 — 재인증(D-23) 후 삭제. Fake 는 입력만 받고 통과시킨다. */
    fun onWithdraw(reauthPassword: String) {
        viewModelScope.launch {
            delay(FAKE_DELAY_MS)
            SessionStore.signOut()
            FavoriteStore.clear()
            _uiState.update { it.copy(signedOut = true) }
        }
    }

    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    private companion object {
        const val FAKE_DELAY_MS = 300L
    }
}
