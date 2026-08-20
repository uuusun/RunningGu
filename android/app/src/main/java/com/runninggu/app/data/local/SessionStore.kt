package com.runninggu.app.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 발급받은 토큰 쌍. (API 명세 §1-5 · §1-6)
 *
 * `ApiClient.TokenProvider` 가 [SessionStore.tokens] 를 읽어 `Authorization` 헤더를 붙인다.
 * **로그에 남기지 않는다**(AGENTS 8장).
 */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
)

/**
 * 로그인한 사용자 프로필. `GET /me` 응답의 화면 요약본이다 (API 명세 §2 · PR #59 개정).
 *
 * @param email 대표 이메일. KAKAO 가입자가 이메일 미제공이면 null — 화면은 이메일 행을
 *  숨기고 placeholder를 두지 않는다 (§4.13 · #59 확정).
 * @param loginProvider 가입 로그인 방식. 단일 수단(결정-22 개정)이라 목록이 아니라 하나다.
 */
data class SessionProfile(
    val nickname: String,
    val email: String?,
    val loginProvider: LoginProvider,
    /**
     * 마케팅 수신 동의. `GET /me` 의 `agreements.marketing` 자리다 (API 명세 §2).
     *
     * 계정 관리 화면의 토글 초기값이라 세션에 함께 들고 있는다 — 기본값 false 로 두면
     * **가입 때 동의한 사용자에게도 꺼진 것으로 보이고**, 토글을 한 번 눌러야 맞춰지는데
     * 그러면 실제로는 철회가 된다.
     */
    val marketingAgreed: Boolean = false,
)

/** 가입 로그인 방식. (API 명세 §2 `loginProvider` · 결정-22 개정) */
enum class LoginProvider(val label: String) {
    EMAIL("이메일 가입"),
    KAKAO("카카오 가입"),
}

/**
 * 세션 보관소. null 이면 게스트다 (SPEC §4.1 게스트 둘러보기 🔒확정).
 *
 * 화면들이 로그인 상태를 공유하는 자리이자, [com.runninggu.app.data.remote.ApiClient] 가
 * 토큰을 읽어 가는 자리다. **아직 프로세스가 죽으면 사라진다.**
 *
 * TODO(AP-14 후속): DataStore 영속을 붙인다. 그래야 앱을 다시 켰을 때 로그인 상태가 남고,
 *  시작 화면이 이 값을 보고 login/home 을 가른다(SPEC §2.2). 새 의존성이라 팀 합의가 필요해
 *  이번에는 넣지 않았다 — 화면은 [session] 만 보므로 이 파일 내부만 바뀐다.
 */
object SessionStore {

    private val _session = MutableStateFlow<SessionProfile?>(null)
    val session: StateFlow<SessionProfile?> = _session.asStateFlow()

    /**
     * 발급받은 토큰. [com.runninggu.app.data.ServiceLocator] 가 이 값을 읽어
     * `Authorization: Bearer` 를 붙인다(§0-2 · #43).
     *
     * 값이 없으면 헤더를 안 붙이므로 공개 API 는 게스트로 정상 동작한다.
     */
    @Volatile
    var tokens: AuthTokens? = null
        private set

    val isLoggedIn: Boolean get() = _session.value != null

    /**
     * 로그인·가입 성공, 또는 프로필 변경(닉네임 등)을 반영한다.
     *
     * [tokens] 를 넘기지 않으면 기존 토큰을 유지한다 — 프로필만 갱신하는 호출에서
     * 세션이 끊기면 안 된다.
     */
    fun signIn(profile: SessionProfile, tokens: AuthTokens? = null) {
        if (tokens != null) this.tokens = tokens
        _session.value = profile
    }

    /**
     * 재발급받은 토큰 쌍으로 갈아끼운다. 프로필은 건드리지 않는다. (§1-9)
     *
     * **리프레시가 회전하므로 두 값을 함께** 넣는다 — 액세스만 바꾸면 다음 재발급이 실패한다.
     */
    fun updateTokens(tokens: AuthTokens) {
        this.tokens = tokens
    }

    /** 로그아웃·탈퇴 공용. 서버 revoke 는 AP-14 에서 붙는다. */
    fun signOut() {
        tokens = null
        _session.value = null
    }
}
