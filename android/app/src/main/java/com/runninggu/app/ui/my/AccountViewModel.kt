package com.runninggu.app.ui.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.ui.auth.AuthValidation
import com.runninggu.app.ui.auth.LoginProvider
import com.runninggu.app.ui.auth.apiErrorCode
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
    val message: String? = null,
    val signedOut: Boolean = false,
) {
    /** 비밀번호 변경 메뉴는 EMAIL 가입자에게만 보인다 (#59 · 결정-38). */
    val showsPasswordMenu: Boolean get() = profile?.loginProvider == LoginProvider.EMAIL

    /**
     * 마케팅 수신 동의. **세션 프로필에서 읽는다** — 화면이 자체 기본값을 들면
     * 가입 때 동의한 사용자에게도 꺼진 것으로 보인다.
     *
     * TODO(AP-14): `GET /me` 의 `agreements.marketing` 이 세션을 채우고,
     *  토글은 `PATCH /me/agreements` 왕복으로 바뀐다 (명세 §2).
     */
    val marketingAgreed: Boolean get() = profile?.marketingAgreed == true
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

    /**
     * 닉네임 변경. 규칙은 가입과 같다(2~12자).
     *
     * **낙관적 갱신 + 롤백**이다 — 서버에 `409 NICKNAME_DUPLICATED` 가 있어서(§1-2 · §2)
     * 성공만 가정하면 중복 닉네임이 화면에 남는다.
     *
     * TODO(AP-14): `PATCH /me` 로 교체한다. 아래 `runCatching` 자리에 호출만 끼우면 된다.
     */
    fun onNicknameChange(nickname: String) {
        val trimmed = nickname.trim()
        if (!AuthValidation.isNicknameValid(trimmed)) {
            _uiState.update { it.copy(message = "닉네임은 2~12자로 지어 주세요") }
            return
        }
        val previous = _uiState.value.profile ?: return
        viewModelScope.launch {
            SessionStore.signIn(previous.copy(nickname = trimmed))
            val outcome = runCatching { delay(FAKE_DELAY_MS) } // TODO(AP-14): PATCH /me
            _uiState.update {
                outcome.fold(
                    onSuccess = { _ -> it.copy(message = "닉네임을 바꿨어요") },
                    onFailure = { cause ->
                        SessionStore.signIn(previous) // 롤백
                        it.copy(
                            message = if (cause.apiErrorCode() == ApiErrorCode.NICKNAME_DUPLICATED) {
                                "이미 쓰고 있는 닉네임이에요"
                            } else {
                                "닉네임을 바꾸지 못했어요. 잠시 후 다시 시도해 주세요."
                            },
                        )
                    },
                )
            }
        }
    }

    /** TODO(AP-14): `PATCH /me/agreements {marketing}` 왕복 + 실패 시 롤백 (명세 §2). */
    fun onToggleMarketing() {
        val profile = _uiState.value.profile ?: return
        val next = !profile.marketingAgreed
        SessionStore.signIn(profile.copy(marketingAgreed = next))
        _uiState.update {
            it.copy(message = if (next) "마케팅 수신에 동의했어요" else "마케팅 수신 동의를 철회했어요")
        }
    }

    /**
     * 비밀번호 변경 (EMAIL만). 성공 시 서버가 전 기기 로그아웃 + 현재 기기 토큰 재발급(D-28).
     *
     * TODO(AP-14): `PUT /me/password {currentPassword, newPassword}` 로 교체한다(§2-1).
     *  응답이 **새 token pair** 라 `SessionStore` 토큰을 원자적으로 갈아끼워야 하고,
     *  `400 CURRENT_PASSWORD_MISMATCH` 를 아래 분기에 연결해야 한다.
     */
    fun onChangePassword(current: String, new: String) {
        if (current.isBlank()) {
            _uiState.update { it.copy(message = "현재 비밀번호를 입력해 주세요") }
            return
        }
        if (!AuthValidation.isPasswordValid(new)) {
            _uiState.update { it.copy(message = "새 비밀번호는 8자 이상, 영문과 숫자를 함께 써 주세요") }
            return
        }
        if (current == new) {
            _uiState.update { it.copy(message = "지금 쓰는 비밀번호와 달라야 해요") }
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
