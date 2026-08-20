package com.runninggu.app.data.remote

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 액세스 토큰이 만료됐을 때. (API 명세 §1-9 · #74 리뷰)
 *
 * 이게 없으면 30분 뒤 **로그인 상태로 보이는데 아무것도 안 되는** 상태에 갇힌다 —
 * 화면은 세션이 있다고 믿고, 인증 호출은 전부 401 이다.
 */
class TokenAuthenticatorTest {

    private fun unauthorized(prior: Response? = null): Response {
        val request = Request.Builder().url("https://api.test/me/courses").build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .apply { prior?.let(::priorResponse) }
            .build()
    }

    @Test
    fun `401 이면 재발급하고 새 토큰으로 다시 보낸다`() {
        var saved: RefreshResponseDto? = null
        val authenticator = TokenAuthenticator(
            currentRefreshToken = { "refresh-1" },
            refresh = { RefreshResponseDto("access-2", "refresh-2") },
            onRefreshed = { saved = it },
            onGiveUp = { error("여기 오면 안 된다") },
        )

        val retry = authenticator.authenticate(null, unauthorized())

        assertEquals("Bearer access-2", retry?.header("Authorization"))
        // 리프레시도 회전한다 — 둘 다 저장해야 다음 재발급이 된다 (§1-9)
        assertEquals("refresh-2", saved?.refreshToken)
    }

    @Test
    fun `재발급이 실패하면 세션을 지우고 포기한다`() {
        var gaveUp = false
        val authenticator = TokenAuthenticator(
            currentRefreshToken = { "refresh-1" },
            refresh = { null }, // 401 INVALID_REFRESH_TOKEN
            onRefreshed = { error("여기 오면 안 된다") },
            onGiveUp = { gaveUp = true },
        )

        val retry = authenticator.authenticate(null, unauthorized())

        assertNull(retry)
        assertTrue(gaveUp)
    }

    @Test
    fun `게스트는 재발급을 시도하지 않는다`() {
        var refreshed = false
        val authenticator = TokenAuthenticator(
            currentRefreshToken = { null },
            refresh = { refreshed = true; null },
            onRefreshed = {},
            onGiveUp = { error("게스트 세션을 지울 일은 없다") },
        )

        val retry = authenticator.authenticate(null, unauthorized())

        assertNull(retry)
        // 공개 API 의 401 은 서버 문제다 — 그대로 화면까지 올린다
        assertTrue(!refreshed)
    }

    @Test
    fun `재발급한 토큰으로도 401 이면 한 번만 시도하고 멈춘다`() {
        var calls = 0
        val authenticator = TokenAuthenticator(
            currentRefreshToken = { "refresh-1" },
            refresh = { calls++; RefreshResponseDto("access-2", "refresh-2") },
            onRefreshed = {},
            onGiveUp = {},
        )

        // 이미 한 번 재시도한 응답이다
        val retry = authenticator.authenticate(null, unauthorized(prior = unauthorized()))

        assertNull(retry)
        assertEquals(0, calls)
    }
}
