package com.runninggu.app.ui.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * 찜의 [com.runninggu.app.ui.favorite.FavoriteStore] 처럼, 서버가 붙기 전까지 로그인
 * 상태를 화면들이 공유하는 자리다. 프로세스가 죽으면 사라진다.
 *
 * TODO(AP-14): DataStore 세션 영속 + 토큰 보관으로 교체한다. 앱 시작 스플래시가
 *  이 값을 보고 login/home 을 가른다 (SPEC §2.2). 화면은 [session]만 보므로
 *  이 파일 내부만 바뀐다.
 */
object SessionStore {

    private val _session = MutableStateFlow<SessionProfile?>(null)
    val session: StateFlow<SessionProfile?> = _session.asStateFlow()

    /**
     * 발급받은 토큰. `ApiClient.TokenProvider` 가 읽어 `Authorization` 헤더를 붙일 자리다(#43).
     *
     * TODO(AP-14): DataStore 영속으로 옮기고 `TokenProvider` 에 연결한다. **연결 전까지는
     *  보관만 하므로 모든 API 호출이 게스트로 나간다.**
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

    /** 로그아웃·탈퇴 공용. 서버 revoke 는 AP-14 에서 붙는다. */
    fun signOut() {
        tokens = null
        _session.value = null
    }
}
