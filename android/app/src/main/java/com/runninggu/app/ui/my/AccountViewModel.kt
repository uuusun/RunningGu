package com.runninggu.app.ui.my

import com.runninggu.app.ui.OFFLINE
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
import com.runninggu.app.data.repository.MemberRepository
import android.content.Context
import com.runninggu.app.ui.auth.KakaoAuthResult
import com.runninggu.app.ui.auth.requestKakaoToken
import com.runninggu.app.data.repository.ReauthCredential
import com.runninggu.app.ui.auth.AuthValidation
import com.runninggu.app.ui.auth.PasswordIssue
import com.runninggu.app.ui.favorite.FavoriteStore
import com.runninggu.app.ui.runCatchingUnlessCancelled
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 카카오 재인증이 SDK 단계에서 실패했다. 서버까지 못 간 것이라 탈퇴는 그대로다. */
private const val KAKAO_REAUTH_FAILED = "카카오 확인을 시작하지 못했어요. 잠시 후 다시 시도해 주세요."

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
    /** 비밀번호 다이얼로그. `null` 이면 닫혀 있다. */
    val passwordEdit: PasswordEdit? = null,
    /** 탈퇴 다이얼로그. `null` 이면 닫혀 있다. */
    val withdraw: WithdrawEdit? = null,
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
 * 비밀번호 다이얼로그 상태. [NicknameEdit] 과 같은 모양이고 같은 이유다.
 *
 * `400 CURRENT_PASSWORD_MISMATCH` 는 **사용자가 고쳐야 넘어가는** 오류다. 닫고 스낵바로
 * 알리면 다시 열어 두 칸을 처음부터 입력해야 한다.
 */
data class PasswordEdit(
    val saving: Boolean = false,
    val error: String? = null,
)

/**
 * 탈퇴 다이얼로그 상태. [PasswordEdit] 과 같은 모양이고 같은 이유다.
 *
 * `401 REAUTH_FAILED` 는 **사용자가 고쳐야 넘어가는** 오류다. 닫고 스낵바로 알리면
 * 되돌릴 수 없는 조작을 처음부터 다시 시작해야 한다.
 */
data class WithdrawEdit(
    val saving: Boolean = false,
    val error: String? = null,
    /**
     * **서버 탈퇴가 이미 끝났다.** 계정은 지워졌고 기기 정리만 남았다.
     *
     * 이 값이 켜지면 다시 눌러도 재인증·삭제를 되풀이하지 않는다 — 지워진 계정으로
     * `reauth` 를 부르면 `401` 이라, 그대로 두면 **기기에 남은 토큰을 영영 못 지운다**
     * (#212 리뷰).
     */
    val serverDone: Boolean = false,
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
    private var passwordJob: Job? = null
    private var withdrawJob: Job? = null

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
            val result = runCatchingUnlessCancelled { memberRepository.updateNickname(trimmed) }
            result.fold(
                onSuccess = { profile ->
                    // **확인과 적용을 한 임계구역에서 한다**(#170 리뷰). 세대를 밖에서 비교한
                    // 뒤 signIn 하면 그 사이에 TokenAuthenticator 의 signOut 이 끼어 로그아웃한
                    // 세션이 되살아날 수 있다.
                    //
                    // 세대가 달라 못 넣었으면 남의 결과다. **버리되 화면은 되돌린다** —
                    // 그냥 빠져나가면 "저장 중" 이 굳어 다이얼로그가 안 닫힌다.
                    val applied = SessionStore.updateProfile(epoch, profile)
                    _uiState.update {
                        it.copy(
                            nicknameEdit = null,
                            message = if (applied) "닉네임을 바꿨어요" else null,
                        )
                    }
                },
                onFailure = { cause ->
                    // 세션이 바뀐 뒤의 실패는 남의 것이다 — 다이얼로그만 닫는다
                    val stale = epoch != SessionStore.sessionEpoch
                    _uiState.update {
                        if (stale) {
                            it.copy(nicknameEdit = null)
                        } else {
                            it.copy(nicknameEdit = NicknameEdit(error = cause.nicknameMessage()))
                        }
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
            val result = runCatchingUnlessCancelled { memberRepository.updateMarketing(next) }
            result.fold(
                onSuccess = { updated ->
                    // 확인과 적용을 한 임계구역에서 (#170 리뷰). 못 넣었으면 남의 결과다.
                    val applied = SessionStore.updateProfile(epoch, updated)
                    _uiState.update {
                        it.copy(
                            // 세대가 바뀌었어도 **잠금은 반드시 푼다.** 안 그러면 스위치가
                            // 잠긴 채 남는다.
                            savingMarketing = false,
                            // 서버가 답한 값으로 말한다 — 보낸 값이 아니다
                            message = when {
                                !applied -> null
                                updated.marketingAgreed -> "마케팅 수신에 동의했어요"
                                else -> "마케팅 수신 동의를 철회했어요"
                            },
                        )
                    }
                },
                onFailure = {
                    val stale = epoch != SessionStore.sessionEpoch
                    _uiState.update {
                        it.copy(
                            savingMarketing = false,
                            message = if (stale) {
                                null
                            } else {
                                "설정을 바꾸지 못했어요. 잠시 후 다시 시도해 주세요."
                            },
                        )
                    }
                },
            )
        }
    }

    /** 비밀번호 다이얼로그를 연다. */
    fun onPasswordEditOpen() {
        _uiState.update { it.copy(passwordEdit = PasswordEdit()) }
    }

    /** 닫는다. **보내는 중에는 닫지 않는다** — 결과를 받을 자리가 없어진다. */
    fun onPasswordEditDismiss() {
        _uiState.update { if (it.passwordEdit?.saving == true) it else it.copy(passwordEdit = null) }
    }

    /**
     * 비밀번호 변경 (EMAIL만). `PUT /me/password` (§2-1 · D-28)
     *
     * 서버가 한 트랜잭션에서 비밀번호를 바꾸고 **기존 refresh 를 전부 revoke** 한 뒤 현재
     * 기기용 token pair 를 다시 준다. 그래서 성공 처리가 프로필 갱신과 다르다 — 새 토큰을
     * 넣지 않으면 **방금 비밀번호를 바꾼 사용자가 다음 재발급에서 로그아웃된다.**
     *
     * 형식은 서버에 묻기 전에 여기서 거른다. A2 와 같은 규칙이다(§4.2-2 🔒).
     */
    fun onChangePassword(current: String, new: String) {
        if (_uiState.value.passwordEdit?.saving == true) return
        if (current.isBlank()) {
            _uiState.update { it.copy(passwordEdit = PasswordEdit(error = "현재 비밀번호를 입력해 주세요")) }
            return
        }
        // A2 와 같은 규칙이다. 너무 길 때는 할 일이 반대라 문구를 가른다(§4.2-2 🔒).
        when (AuthValidation.passwordIssue(new)) {
            PasswordIssue.FORMAT -> {
                _uiState.update {
                    it.copy(
                        passwordEdit = PasswordEdit(
                            error = "새 비밀번호는 8자 이상, 영문과 숫자를 함께 써 주세요",
                        ),
                    )
                }
                return
            }

            PasswordIssue.TOO_LONG -> {
                _uiState.update {
                    it.copy(
                        passwordEdit = PasswordEdit(
                            error = "새 비밀번호가 너무 길어요. 영문·숫자는 72자, 한글은 24자까지예요",
                        ),
                    )
                }
                return
            }

            null -> Unit
        }
        if (current == new) {
            _uiState.update {
                it.copy(passwordEdit = PasswordEdit(error = "지금 쓰는 비밀번호와 달라야 해요"))
            }
            return
        }
        val epoch = SessionStore.sessionEpoch
        passwordJob?.cancel()
        passwordJob = viewModelScope.launch {
            _uiState.update { it.copy(passwordEdit = PasswordEdit(saving = true)) }
            val result = runCatchingUnlessCancelled {
                memberRepository.updatePassword(current, new)
            }
            result.fold(
                onSuccess = { tokens ->
                    // 닉네임과 같은 이유로 **확인과 적용을 한 임계구역에서** 한다(#170 리뷰).
                    // 세대가 달라 못 넣었으면 남의 결과다 — 버리되 다이얼로그는 닫는다.
                    val applied = SessionStore.updateTokens(epoch, tokens)
                    _uiState.update {
                        it.copy(
                            passwordEdit = null,
                            message = if (applied) PASSWORD_CHANGED_MESSAGE else null,
                        )
                    }
                },
                onFailure = { cause ->
                    val stale = epoch != SessionStore.sessionEpoch
                    _uiState.update {
                        if (stale) {
                            it.copy(passwordEdit = null)
                        } else {
                            it.copy(passwordEdit = PasswordEdit(error = cause.passwordMessage()))
                        }
                    }
                },
            )
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

    /** 탈퇴 다이얼로그를 연다. */
    fun onWithdrawOpen() {
        _uiState.update { it.copy(withdraw = WithdrawEdit()) }
    }

    /**
     * 닫는다. **보내는 중에는 닫지 않는다** — 결과를 받을 자리가 없어진다.
     *
     * 서버 탈퇴가 끝난 뒤에도 닫지 않는다. 닫으면 기기에 남은 토큰을 지울 길이 없어진다
     * (#212 리뷰).
     */
    fun onWithdrawDismiss() {
        _uiState.update {
            val edit = it.withdraw
            if (edit?.saving == true || edit?.serverDone == true) it else it.copy(withdraw = null)
        }
    }

    /**
     * 회원 탈퇴. **재인증하고, 서버가 지운 뒤에, 기기를 정리한다.** (§2-2 · D-23 · SPEC §4.13)
     *
     * 예전에는 `delay(300)` 뒤 곧바로 로그아웃했다. **비밀번호를 받아만 놓고 쓰지 않아서
     * 틀려도 탈퇴됐고**, 실제로는 서버에 계정이 그대로 남았다.
     *
     * ## 순서가 규칙이다
     *
     * ```
     * reauth → withdraw(성공) → 세션 정리 → 찜 캐시 정리
     * ```
     *
     * **먼저 로그아웃하면 탈퇴가 안 된 채 세션만 사라진다** — 사용자는 지웠다고 믿는데
     * 계정이 남는다(#198 KDoc). 로그아웃이 #89 에서 `signOutAndAwait` 를 쓰게 된 것과
     * 같은 자리다: 지워진 것을 **확인하기 전에는** 완료가 아니다.
     *
     * ## 재인증 수단은 가입 경로를 따라간다 (§2-2)
     *
     * EMAIL 은 현재 비밀번호, KAKAO 는 **SDK 가 방금 발급한** 액세스 토큰이다. 카카오는
     * [onWithdrawWithKakao] 로 들어온다 — 앞쪽(SDK)이 `Context` 를 요구해 기기 없이는
     * 못 돌리므로, 뒤쪽만 [startWithdraw] 로 갈라 두었다.
     */
    fun onWithdraw(reauthPassword: String) {
        val current = _uiState.value.withdraw
        if (current?.saving == true) return
        // 서버는 이미 지웠다. 재인증·삭제를 되풀이하지 않고 **기기 정리만** 다시 한다.
        // 지워진 계정으로 reauth 를 부르면 401 이라 여기서 갈라야 복구할 길이 생긴다(#212 리뷰)
        if (current?.serverDone == true) {
            withdrawJob?.cancel()
            withdrawJob = viewModelScope.launch { finishLocally() }
            return
        }
        if (reauthPassword.isBlank()) {
            _uiState.update { it.copy(withdraw = WithdrawEdit(error = "비밀번호를 입력해 주세요")) }
            return
        }
        startWithdraw(ReauthCredential.Password(reauthPassword))
    }

    /**
     * 카카오 가입자의 탈퇴. (§2-2 · #237 리뷰)
     *
     * **SDK 가 방금 발급한 토큰이어야 한다.** 로그인할 때 받은 토큰을 들고 있다가 쓰는
     * 것이 아니라, 이 순간 사용자가 카카오로 본인임을 다시 보이는 절차다 — 비밀번호를
     * 다시 묻는 것과 같은 자리다.
     *
     * 앞쪽(SDK)이 `Context` 와 실제 카카오 앱을 요구해 **기기 없이는 돌릴 수 없다.**
     * 그래서 뒤쪽은 [onWithdrawWithKakaoToken] 으로 갈라 두었다 — 어떤 결말로 가는지는
     * 기기 없이 고정할 수 있어야 한다([LoginViewModel] 과 같은 이유).
     */
    fun onWithdrawWithKakao(context: Context) {
        val current = _uiState.value.withdraw
        if (current?.saving == true) return
        if (current?.serverDone == true) {
            withdrawJob?.cancel()
            withdrawJob = viewModelScope.launch { finishLocally() }
            return
        }
        withdrawJob?.cancel()
        withdrawJob = viewModelScope.launch {
            _uiState.update { it.copy(withdraw = WithdrawEdit(saving = true)) }
            when (val auth = requestKakaoToken(context)) {
                // 되돌릴 수 없는 조작 앞에서 그만둔 것이다. 문구 없이 원래대로 둔다
                is KakaoAuthResult.Cancelled ->
                    _uiState.update { it.copy(withdraw = WithdrawEdit()) }

                is KakaoAuthResult.Failed ->
                    _uiState.update { it.copy(withdraw = WithdrawEdit(error = KAKAO_REAUTH_FAILED)) }

                is KakaoAuthResult.Token -> onWithdrawWithKakaoToken(auth.accessToken)
            }
        }
    }

    /** SDK 와 서버 사이의 경계. 기기 없이 여기부터 고정한다. */
    internal fun onWithdrawWithKakaoToken(accessToken: String) {
        startWithdraw(ReauthCredential.Kakao(accessToken))
    }

    /** 재인증 수단만 다르고 그 뒤는 같다 — 순서도 실패 처리도 한곳에 둔다. */
    private fun startWithdraw(credential: ReauthCredential) {
        val epoch = SessionStore.sessionEpoch
        withdrawJob?.cancel()
        withdrawJob = viewModelScope.launch {
            _uiState.update { it.copy(withdraw = WithdrawEdit(saving = true)) }
            val result = runCatchingUnlessCancelled {
                val token = memberRepository.reauth(credential)
                memberRepository.withdraw(token)
            }
            result.fold(
                onSuccess = {
                    // 세션이 바뀐 뒤라면 남의 결과다. 지운 것은 서버뿐이니 화면만 닫는다
                    if (epoch != SessionStore.sessionEpoch) {
                        _uiState.update { it.copy(withdraw = null) }
                        return@fold
                    }
                    finishLocally()
                },
                onFailure = { cause ->
                    val stale = epoch != SessionStore.sessionEpoch
                    _uiState.update {
                        if (stale) {
                            it.copy(withdraw = null)
                        } else {
                            it.copy(withdraw = WithdrawEdit(error = cause.withdrawMessage()))
                        }
                    }
                },
            )
        }
    }

    /**
     * 서버가 지운 뒤 **기기를 비운다.** 확인하기 전에는 완료가 아니다 (#89 리뷰).
     *
     * 실패하면 다이얼로그를 **열어 둔 채** 알린다. 닫고 다시 누르면 재인증부터 시작하는데,
     * 계정이 이미 없어서 `401` 로 막힌다. 그래서 [WithdrawEdit.serverDone] 을 켜서 다음
     * 시도가 여기로 바로 오게 한다.
     *
     * **다만 여기 가두지는 않는다** (#212 리뷰). [onWithdrawGiveUp] 이 나갈 길이다 —
     * 재시도가 계속 실패하는 것은 보통 저장소 쓰기 실패라, 같은 자리에서 또 눌러도 같은
     * 결과가 나온다. 모달에 붙잡아 두고 얻는 것이 없다.
     */
    private suspend fun finishLocally() {
        _uiState.update { it.copy(withdraw = WithdrawEdit(saving = true, serverDone = true)) }
        if (!SessionStore.signOutAndAwait()) {
            _uiState.update {
                it.copy(withdraw = WithdrawEdit(serverDone = true, error = LOCAL_CLEANUP_FAILED))
            }
            return
        }
        FavoriteStore.clear()
        _uiState.update { it.copy(withdraw = null, signedOut = true) }
    }

    /**
     * 기기 정리를 **나중으로 미룬다.** 서버 탈퇴가 끝난 뒤에만 쓴다 (#212 리뷰).
     *
     * ## 왜 로그아웃과 다르게 메모리를 비우는가
     *
     * [SessionStore.signOutAndAwait] 는 디스크를 못 지우면 **메모리를 그대로 둔다** —
     * 로그아웃은 서버 세션이 아직 살아 있어서, 지운 척하면 다음 실행에 계정이 되살아난다.
     *
     * **탈퇴는 그 자리가 다르다. 서버 계정이 이미 없다.** 디스크에 남는 것은 **죽은
     * 토큰**이라, 다음 실행에 복원돼도 첫 요청이 `401` 을 받고 그때 정리된다
     * (`ServiceLocator` 의 `onGiveUp` → [SessionStore.signOut]). 되살아날 계정이 없다.
     *
     * 그래서 [SessionStore.signOut] 으로 **메모리를 비우고 디스크 삭제는 예약**한다.
     * 계정이 없는데 로그인 화면 뒤에 남겨 두면, 어느 화면을 열어도 `401` 만 본다.
     */
    fun onWithdrawGiveUp() {
        if (_uiState.value.withdraw?.serverDone != true) return
        withdrawJob?.cancel()
        SessionStore.signOut()
        FavoriteStore.clear()
        _uiState.update { it.copy(withdraw = null, signedOut = true) }
    }

    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    /**
     * 탈퇴 실패 문구. **셋이 서로 다른 일을 시킨다** (§2-2).
     *
     * | 코드 | 사용자가 할 일 |
     * |---|---|
     * | `REAUTH_FAILED` | 비밀번호를 다시 입력한다 |
     * | `INVALID_REAUTH_TOKEN` | 5분이 지났다 — **처음부터** 다시 한다 |
     * | `REAUTH_PROVIDER_MISMATCH` | 가입한 수단으로 한다 — 재시도가 아니라 **다른 수단**이다 |
     *
     * 하나로 뭉뚱그리면 5분이 지난 사용자가 같은 비밀번호를 계속 다시 넣는다.
     */
    private fun Throwable.withdrawMessage(): String = when (apiErrorCode()) {
        ApiErrorCode.REAUTH_FAILED -> "비밀번호가 맞지 않아요"
        ApiErrorCode.INVALID_REAUTH_TOKEN -> "시간이 지났어요. 다시 시도해 주세요."
        ApiErrorCode.REAUTH_PROVIDER_MISMATCH -> "가입할 때 쓴 방법으로 확인해 주세요"
        else -> if (this is ApiException.Network) {
            OFFLINE
        } else {
            "탈퇴하지 못했어요. 잠시 후 다시 시도해 주세요."
        }
    }

    /**
     * 두 오류는 **사용자가 할 일이 다르다.** 현재 비밀번호가 틀린 것은 위 칸을 고치는 일이고,
     * 형식 위반은 아래 칸을 고치는 일이다. "다시 시도" 로 뭉뚱그리면 어느 칸인지 모른다(§2-1).
     *
     * `INVALID_PASSWORD` 는 앞의 형식 검사에서 대부분 걸러지지만, 서버 정책이 앞서 갈 수
     * 있으니 그대로 둔다 — 계약이 이긴다(AGENTS 4장).
     */
    private fun Throwable.passwordMessage(): String = when (apiErrorCode()) {
        ApiErrorCode.CURRENT_PASSWORD_MISMATCH -> "현재 비밀번호가 맞지 않아요"
        ApiErrorCode.INVALID_PASSWORD -> "새 비밀번호는 8자 이상, 영문과 숫자를 함께 써 주세요"
        else -> if (this is ApiException.Network) {
            OFFLINE
        } else {
            "비밀번호를 바꾸지 못했어요. 잠시 후 다시 시도해 주세요."
        }
    }

    /**
     * 닉네임 변경 실패 문구. 다이얼로그 안에 그린다.
     *
     * **중복만 따로 가른다** — 사용자가 다른 이름을 고르면 풀리는 유일한 오류라, "잠시 후
     * 다시 시도" 로 뭉뚱그리면 몇 번을 눌러도 같은 결과가 나온다.
     */
    private fun Throwable.nicknameMessage(): String = when {
        apiErrorCode() == ApiErrorCode.NICKNAME_DUPLICATED -> "이미 쓰고 있는 닉네임이에요"
        this is ApiException.Network -> OFFLINE
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

        /**
         * 서버는 지웠는데 기기를 못 비웠다. **계정이 사라진 것은 사실이라** 그렇게 말한다
         * — "탈퇴하지 못했어요" 로 쓰면 사용자가 계정이 남은 줄 안다 (#212 리뷰).
         */
        const val LOCAL_CLEANUP_FAILED =
            "계정은 삭제됐어요. 기기에 남은 정보를 지우지 못했으니 다시 시도해 주세요."

        /** D-28 — 성공하면 다른 기기 세션이 전부 끊긴다. 그 사실을 알린다. */
        const val PASSWORD_CHANGED_MESSAGE = "비밀번호를 바꿨어요. 다른 기기는 로그아웃돼요."
    }
}
