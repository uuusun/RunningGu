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

    val isLoggedIn: Boolean get() = _session.value != null

    fun signIn(profile: SessionProfile) {
        _session.value = profile
    }

    /** 로그아웃·탈퇴 공용. 세션만 지운다 — 서버 revoke 는 AP-14 에서 붙는다. */
    fun signOut() {
        _session.value = null
    }
}
