package com.runninggu.app.data.local

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 세션 세대. (#74 리뷰)
 *
 * 토큰 재발급은 왕복이 길어서 그 사이에 로그아웃·계정 전환이 끼어들 수 있다. 세대가
 * 없으면 **끝난 재발급이 남의 세션에 토큰을 덮어쓴다.**
 */
class SessionEpochTest {

    @After
    fun tearDown() {
        SessionStore.signOut()
    }

    private fun profile(nickname: String) =
        SessionProfile(nickname, "$nickname@test.com", LoginProvider.EMAIL)

    @Test
    fun `로그인과 로그아웃마다 세대가 올라간다`() {
        val start = SessionStore.sessionEpoch

        SessionStore.signIn(profile("A"), AuthTokens("A1", "AR1"))
        val afterLogin = SessionStore.sessionEpoch
        SessionStore.signOut()
        val afterLogout = SessionStore.sessionEpoch

        assertTrue(afterLogin > start)
        assertTrue(afterLogout > afterLogin)
    }

    @Test
    fun `프로필만 바꾸면 세대는 그대로다`() {
        // 닉네임 변경으로 세대가 올라가면 진행 중이던 재발급이 헛되이 버려진다
        SessionStore.signIn(profile("A"), AuthTokens("A1", "AR1"))
        val epoch = SessionStore.sessionEpoch

        SessionStore.signIn(profile("A").copy(nickname = "새이름"))

        assertEquals(epoch, SessionStore.sessionEpoch)
    }

    @Test
    fun `로그아웃한 뒤 도착한 재발급 결과는 버린다`() {
        SessionStore.signIn(profile("A"), AuthTokens("A1", "AR1"))
        val epoch = SessionStore.sessionEpoch

        SessionStore.signOut() // 재발급 왕복 중에 로그아웃
        val applied = SessionStore.updateTokens(epoch, AuthTokens("A2", "AR2"))

        assertFalse(applied)
        // 게스트로 남는다 — 토큰이 되살아나면 로그아웃이 안 된 셈이다
        assertNull(SessionStore.tokens)
        assertFalse(SessionStore.isLoggedIn)
    }

    @Test
    fun `계정이 바뀐 뒤 도착한 재발급 결과는 B 를 덮지 않는다`() {
        SessionStore.signIn(profile("A"), AuthTokens("A1", "AR1"))
        val aEpoch = SessionStore.sessionEpoch

        SessionStore.signIn(profile("B"), AuthTokens("B1", "BR1")) // A → B 전환
        val applied = SessionStore.updateTokens(aEpoch, AuthTokens("A2", "AR2"))

        assertFalse(applied)
        assertEquals("B1", SessionStore.tokens?.accessToken)
    }

    @Test
    fun `세대가 같으면 회전된 토큰 쌍을 갈아끼운다`() {
        SessionStore.signIn(profile("A"), AuthTokens("A1", "AR1"))

        val applied = SessionStore.updateTokens(SessionStore.sessionEpoch, AuthTokens("A2", "AR2"))

        assertTrue(applied)
        assertEquals("A2", SessionStore.tokens?.accessToken)
        // 리프레시도 회전한다 (§1-9)
        assertEquals("AR2", SessionStore.tokens?.refreshToken)
    }
}
