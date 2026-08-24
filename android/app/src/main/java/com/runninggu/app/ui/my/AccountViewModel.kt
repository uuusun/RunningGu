package com.runninggu.app.ui.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.ui.auth.AuthValidation
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.repository.apiErrorCode
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.repository.AuthRepository
import com.runninggu.app.data.repository.MemberRepository
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.ui.favorite.FavoriteStore
import kotlinx.coroutines.Job
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
    /** 마케팅 토글이 서버에 다녀오는 중. 스위치를 잠가 연타로 요청이 겹치지 않게 한다. */
    val savingMarketing: Boolean = false,
    /** 닉네임 다이얼로그. `null` 이면 닫혀 있다. */
    val nicknameEdit: NicknameEdit? = null,
) {
    /** 비밀번호 변경 메뉴는 EMAIL 가입자에게만 보인다 (#59 · 결정-38). */
    val showsPasswordMenu: Boolean get() = profile?.loginProvider == LoginProvider.EMAIL

    /**
     * 마케팅 수신 동의. **세션 프로필에서 읽는다** — 화면이 자체 기본값을 들면
     * 가입 때 동의한 사용자에게도 꺼진 것으로 보인다.
     *
     * 세션은 `GET /me` · `PATCH /me/agreements` 응답으로만 채워지므로(명세 §2)
     * **여기 보이는 값은 언제나 서버가 말한 값**이다.
     */
    val marketingAgreed: Boolean get() = profile?.marketingAgreed == true
}

/**
 * 닉네임 다이얼로그의 상태. (SPEC §4.13 · API 명세 §2)
 *
 * **화면이 아니라 여기서 여닫는다.** 성공해야 닫히고 실패하면 열린 채 남아야 하는데,
 * 그 판단이 서버 응답에 달려 있기 때문이다. 화면이 `remember` 로 들고 있으면 확인을
 * 누른 순간 닫혀서 `409 NICKNAME_DUPLICATED` 를 **고칠 자리가 사라진다** (이슈 #164).
 *
 * @param error 다이얼로그 안에 그대로 그린다. 스낵바로 보내면 닫힌 뒤에 뜬다
 */
data class NicknameEdit(
    val saving: Boolean = false,
    val error: String? = null,
)

/**
 * 계정 관리. (SPEC §4.13 · AP-13 · AP-14)
 *
 * **닉네임·마케팅 동의·로그아웃이 서버를 본다.** 남은 것은 아직 Fake 다 —
 * TODO(AP-14): `PUT /me/password`(EMAIL만, 토큰 쌍 재발급 D-28) ·
 * `POST /me/reauth` + `DELETE /me`(탈퇴 재인증 D-23) 로 교체한다.
 *
 * ## 값을 스스로 뒤집지 않는다
 *
 * 세 엔드포인트가 모두 **프로필 전체**를 돌려주므로(명세 §2), 화면은 무엇이 바뀌었는지
 * 따지지 않고 세션을 통째로 갈아끼운다. 그래서 낙관적 갱신도, 롤백도 없다 — **서버가
 * 답하기 전에는 화면이 움직이지 않는다.** 되돌릴 것이 없으니 되돌리다 틀릴 일도 없다.
 */
class AccountViewModel(
    private val repository: AuthRepository = ServiceLocator.authRepository,
    private val memberRepository: MemberRepository = ServiceLocator.memberRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    /** 마케팅 토글 연타. 스위치도 잠그지만 화면이 다시 만들어지는 경우까지 여기서 끊는다. */
    private var marketingJob: Job? = null
    private var nicknameJob: Job? = null

    init {
        viewModelScope.launch {
            SessionStore.session.collect { profile ->
                _uiState.update { it.copy(profile = profile) }
            }
        }
    }

    /** 닉네임 다이얼로그를 연다. */
    fun onNicknameEditOpen() {
        _uiState.update { it.copy(nicknameEdit = NicknameEdit()) }
    }

    /** 닫는다. **보내는 중에는 닫지 않는다** — 결과를 받을 자리가 없어진다. */
    fun onNicknameEditDismiss() {
        _uiState.update { if (it.nicknameEdit?.saving == true) it else it.copy(nicknameEdit = null) }
    }

    /**
     * 닉네임 변경. (`PATCH /me` · 명세 §2) 규칙은 가입과 같다(2~12자).
     *
     * **성공해야 다이얼로그가 닫힌다.** `409 NICKNAME_DUPLICATED` 는 사용자가 **고쳐야
     * 넘어가는** 오류라, 닫고 스낵바로 알리면 다시 열어 처음부터 입력해야 한다. 그래서
     * 안내를 다이얼로그 안에 둔다(이슈 #164).
     *
     * 길이 규칙은 서버에 묻기 전에 여기서 거른다 — 왕복할 이유가 없다.
     */
    fun onNicknameChange(nickname: String) {
        val trimmed = nickname.trim()
        if (_uiState.value.nicknameEdit?.saving == true) return
        if (!AuthValidation.isNicknameValid(trimmed)) {
            _uiState.update { it.copy(nicknameEdit = NicknameEdit(error = "닉네임은 2~12자로 지어 주세요")) }
            return
        }
        val epoch = SessionStore.sessionEpoch
        nicknameJob?.cancel()
        nicknameJob = viewModelScope.launch {
            _uiState.update { it.copy(nicknameEdit = NicknameEdit(saving = true)) }
            val result = runCatching { memberRepository.updateNickname(trimmed) }
            // 기다리는 사이 로그아웃·계정 전환이 있었으면 남의 결과다. **버리되 화면은
            // 되돌린다** — 그냥 빠져나가면 "저장 중" 이 굳어 다이얼로그가 안 닫힌다
            if (epoch != SessionStore.sessionEpoch) {
                _uiState.update { it.copy(nicknameEdit = null) }
                return@launch
            }
            result.fold(
                onSuccess = { profile ->
                    SessionStore.signIn(profile)
                    _uiState.update { it.copy(nicknameEdit = null, message = "닉네임을 바꿨어요") }
                },
                onFailure = { cause ->
                    _uiState.update {
                        it.copy(nicknameEdit = NicknameEdit(error = cause.nicknameMessage()))
                    }
                },
            )
        }
    }

    /**
     * 마케팅 수신 동의 토글. (`PATCH /me/agreements {marketing}` · 명세 §2)
     *
     * **미리 뒤집지 않는다.** 스위치는 세션 프로필을 그리므로, 서버가 답해야 움직인다.
     * 예전에는 여기서 `SessionStore.signIn(profile.copy(...))` 로 로컬 값만 뒤집어서
     * **앱을 지웠다 깔면 되돌아갔다**(이슈 #164).
     *
     * 보내는 중에는 [AccountUiState.savingMarketing] 로 스위치를 잠근다. 서버가 멱등이라
     * 겹쳐도 이력이 중복되지는 않지만(§2), 응답이 엇갈려 도착하면 화면이 튄다.
     */
    fun onToggleMarketing() {
        val profile = _uiState.value.profile ?: return
        if (_uiState.value.savingMarketing) return
        val next = !profile.marketingAgreed
        val epoch = SessionStore.sessionEpoch
        marketingJob?.cancel()
        marketingJob = viewModelScope.launch {
            _uiState.update { it.copy(savingMarketing = true) }
            val result = runCatching { memberRepository.updateMarketing(next) }
            // 세대가 바뀌었어도 **잠금은 반드시 푼다.** 안 그러면 스위치가 잠긴 채 남는다
            if (epoch != SessionStore.sessionEpoch) {
                _uiState.update { it.copy(savingMarketing = false) }
                return@launch
            }
            result.fold(
                onSuccess = { updated ->
                    SessionStore.signIn(updated)
                    _uiState.update {
                        it.copy(
                            savingMarketing = false,
                            // 서버가 답한 값으로 말한다 — 보낸 값이 아니다
                            message = if (updated.marketingAgreed) {
                                "마케팅 수신에 동의했어요"
                            } else {
                                "마케팅 수신 동의를 철회했어요"
                            },
                        )
                    }
                },
                onFailure = {
                    _uiState.update {
                        it.copy(
                            savingMarketing = false,
                            message = "설정을 바꾸지 못했어요. 잠시 후 다시 시도해 주세요.",
                        )
                    }
                },
            )
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

    /**
     * 로그아웃. **서버에서 revoke 한 뒤 기기에서 지운다.** (§1-10 · 이슈 #113)
     *
     * 순서가 중요하다. 로컬을 먼저 지우면 리프레시 토큰이 사라져 **revoke 할 자격을
     * 잃는다** — 서버에는 쓸 수 있는 세션이 남고 사용자는 로그아웃했다고 믿는다.
     *
     * **서버 실패에는 로그인 상태를 유지한다.** 네트워크·5xx 는 "지워졌는지 모르는"
     * 상태라, 그때 기기만 비우면 위와 같은 결과가 된다. 다시 시도하게 한다.
     *
     * 그 뒤에도 **디스크에서 토큰이 지워진 것을 확인하고** 화면을 넘긴다(#89 리뷰) —
     * 예약만 하고 넘기면 직후 프로세스가 죽었을 때 다음 실행에 이전 세션이 되살아난다.
     */
    fun onLogout() {
        viewModelScope.launch {
            val refreshToken = SessionStore.tokens?.refreshToken
            // 지울 토큰이 없으면 서버에 물을 것도 없다. 게스트이거나 이미 정리된 상태다.
            if (!refreshToken.isNullOrBlank()) {
                val revoked = repository.logout(refreshToken)
                if (revoked.isFailure) {
                    _uiState.update { it.copy(message = LOGOUT_REVOKE_FAILED_MESSAGE) }
                    return@launch
                }
            }
            // **지워진 것을 확인하기 전에는 완료로 넘기지 않는다** (#89 리뷰).
            // 못 지웠는데 로그인 화면으로 보내면, 다음 실행에 이전 계정이 되살아난다
            if (!SessionStore.signOutAndAwait()) {
                _uiState.update { it.copy(message = LOGOUT_FAILED_MESSAGE) }
                return@launch
            }
            // 다음 사용자에게 이전 찜이 보이면 안 된다 (AP-21).
            FavoriteStore.clear()
            _uiState.update { it.copy(signedOut = true) }
        }
    }

    /** 회원 탈퇴 — 재인증(D-23) 후 삭제. Fake 는 입력만 받고 통과시킨다. */
    fun onWithdraw(reauthPassword: String) {
        viewModelScope.launch {
            delay(FAKE_DELAY_MS)
            // 탈퇴도 같다 — 지워진 것을 확인하기 전에는 완료가 아니다 (#89 리뷰)
            if (!SessionStore.signOutAndAwait()) {
                _uiState.update { it.copy(message = LOGOUT_FAILED_MESSAGE) }
                return@launch
            }
            FavoriteStore.clear()
            _uiState.update { it.copy(signedOut = true) }
        }
    }

    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    /**
     * 닉네임 변경 실패 문구. 다이얼로그 안에 그린다.
     *
     * **중복만 따로 가른다** — 사용자가 다른 이름을 고르면 풀리는 유일한 오류라, "잠시 후
     * 다시 시도" 로 뭉뚱그리면 몇 번을 눌러도 같은 결과가 나온다.
     */
    private fun Throwable.nicknameMessage(): String = when {
        apiErrorCode() == ApiErrorCode.NICKNAME_DUPLICATED -> "이미 쓰고 있는 닉네임이에요"
        this is ApiException.Network -> "네트워크에 연결할 수 없어요"
        else -> "닉네임을 바꾸지 못했어요. 잠시 후 다시 시도해 주세요."
    }

    private companion object {
        /** 기기에서 못 지웠다. 로그인 상태를 유지한 채 다시 시도하게 한다 (#89 리뷰). */
        const val LOGOUT_FAILED_MESSAGE = "기기에서 로그아웃 정보를 지우지 못했어요. 다시 시도해 주세요."

        /**
         * 서버에 못 닿았다. **기기 토큰을 남긴 채** 다시 시도하게 한다 (이슈 #113).
         *
         * 여기서 기기만 비우면 서버 세션이 살아남는다 — 사용자는 로그아웃했다고 믿는다.
         */
        const val LOGOUT_REVOKE_FAILED_MESSAGE = "로그아웃하지 못했어요. 연결을 확인하고 다시 시도해 주세요."

        const val FAKE_DELAY_MS = 300L
    }
}
