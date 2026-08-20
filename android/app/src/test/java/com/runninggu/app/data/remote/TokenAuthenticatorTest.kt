package com.runninggu.app.data.remote

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 액세스 토큰이 만료됐을 때. (API 명세 §1-9 · #74 리뷰)
 *
 * 이게 없으면 30분 뒤 **로그인 상태로 보이는데 아무것도 안 되는** 상태에 갇힌다.
 * 반대로 잘못 만들면 **아무 이유 없이 로그아웃되는** 앱이 된다 — 아래 둘이 그 자리다.
 */
class TokenAuthenticatorTest {

    private fun unauthorized(sentToken: String? = "access-1", prior: Response? = null): Response {
        val request = Request.Builder()
            .url("https://api.test/me/courses")
            .apply { sentToken?.let { header("Authorization", "Bearer $it") } }
            .build()
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
            currentAccessToken = { "access-1" },
            currentRefreshToken = { "refresh-1" },
            refresh = { RefreshOutcome.Renewed(RefreshResponseDto("access-2", "refresh-2")) },
            onRefreshed = { saved = it },
            onGiveUp = { error("여기 오면 안 된다") },
        )

        val retry = authenticator.authenticate(null, unauthorized())

        assertEquals("Bearer access-2", retry?.header("Authorization"))
        // 리프레시도 회전한다 — 둘 다 저장해야 다음 재발급이 된다 (§1-9)
        assertEquals("refresh-2", saved?.refreshToken)
    }

    @Test
    fun `401 이 동시에 둘 나도 재발급은 한 번만 나간다`() {
        // 리프레시는 회전한다. 각 스레드가 같은 리프레시로 재발급하면 먼저 성공한 쪽이
        // 나머지를 무효로 만들어, **재발급에 성공했는데도 로그아웃**된다 (#74 리뷰)
        val calls = AtomicInteger()
        var access = "access-1"
        var signedOut = false
        val authenticator = TokenAuthenticator(
            currentAccessToken = { access },
            currentRefreshToken = { "refresh-1" },
            refresh = {
                calls.incrementAndGet()
                Thread.sleep(30) // 회전 왕복을 흉내낸다
                RefreshOutcome.Renewed(RefreshResponseDto("access-2", "refresh-2"))
            },
            onRefreshed = { access = it.accessToken },
            onGiveUp = { signedOut = true },
        )

        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val results = (1..2).map {
            pool.submit<Request?> {
                start.await()
                authenticator.authenticate(null, unauthorized(sentToken = "access-1"))
            }
        }
        start.countDown()
        val retries = results.map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, calls.get())
        // 둘 다 새 토큰으로 재시도한다 — 하나는 재발급 결과로, 하나는 이미 갱신된 값으로
        assertTrue(retries.all { it?.header("Authorization") == "Bearer access-2" })
        assertFalse(signedOut)
    }

    @Test
    fun `네트워크 실패로는 로그아웃하지 않는다`() {
        // 지하철에서 잠깐 끊긴 것뿐인데 세션을 지우면 찜 캐시까지 날아간다 (§4.13)
        var signedOut = false
        val authenticator = TokenAuthenticator(
            currentAccessToken = { "access-1" },
            currentRefreshToken = { "refresh-1" },
            refresh = { RefreshOutcome.Failed },
            onRefreshed = { error("여기 오면 안 된다") },
            onGiveUp = { signedOut = true },
        )

        val retry = authenticator.authenticate(null, unauthorized())

        assertNull(retry)
        assertFalse(signedOut)
    }

    @Test
    fun `리프레시가 만료됐을 때만 세션을 지운다`() {
        var signedOut = false
        val authenticator = TokenAuthenticator(
            currentAccessToken = { "access-1" },
            currentRefreshToken = { "refresh-1" },
            refresh = { RefreshOutcome.Expired }, // 401 INVALID_REFRESH_TOKEN
            onRefreshed = { error("여기 오면 안 된다") },
            onGiveUp = { signedOut = true },
        )

        val retry = authenticator.authenticate(null, unauthorized())

        assertNull(retry)
        assertTrue(signedOut)
    }

    @Test
    fun `게스트는 재발급을 시도하지 않는다`() {
        var refreshed = false
        val authenticator = TokenAuthenticator(
            currentAccessToken = { null },
            currentRefreshToken = { null },
            refresh = { refreshed = true; RefreshOutcome.Failed },
            onRefreshed = {},
            onGiveUp = { error("게스트 세션을 지울 일은 없다") },
        )

        val retry = authenticator.authenticate(null, unauthorized(sentToken = null))

        assertNull(retry)
        // 공개 API 의 401 은 서버 문제다 — 그대로 화면까지 올린다
        assertFalse(refreshed)
    }

    @Test
    fun `재발급한 토큰으로도 401 이면 한 번만 시도하고 멈춘다`() {
        var calls = 0
        val authenticator = TokenAuthenticator(
            currentAccessToken = { "access-1" },
            currentRefreshToken = { "refresh-1" },
            refresh = { calls++; RefreshOutcome.Renewed(RefreshResponseDto("access-2", "refresh-2")) },
            onRefreshed = {},
            onGiveUp = {},
        )

        val retry = authenticator.authenticate(null, unauthorized(prior = unauthorized()))

        assertNull(retry)
        assertEquals(0, calls)
    }
}

/**
 * 재발급 실패를 어떻게 가르는지. (§1-9 · #74 리뷰)
 *
 * 이 매핑이 무너지면 **네트워크가 한 번 끊길 때마다 로그아웃**된다.
 */
class RefreshFailureTest {

    @Test
    fun `401 만 재로그인 신호다`() {
        val expired = ApiException.Http(401, ApiErrorCode.INVALID_REFRESH_TOKEN, null)

        assertEquals(RefreshOutcome.Expired, expired.asRefreshFailure())
    }

    @Test
    fun `네트워크 실패는 세션을 지키게 한다`() {
        val offline = ApiException.Network(java.io.IOException("끊김"))

        assertEquals(RefreshOutcome.Failed, offline.asRefreshFailure())
    }

    @Test
    fun `서버 오류도 세션을 지키게 한다`() {
        // 5xx 는 서버 사정이다 — 사용자를 로그아웃시킬 이유가 없다
        val serverError = ApiException.Http(503, ApiErrorCode.INTERNAL_SERVER_ERROR, null)

        assertEquals(RefreshOutcome.Failed, serverError.asRefreshFailure())
    }
}
