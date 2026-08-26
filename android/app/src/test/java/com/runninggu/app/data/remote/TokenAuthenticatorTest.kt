package com.runninggu.app.data.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

    /**
     * `401` 응답. [code] 는 problem+json 본문의 오류 코드다. (§0-3)
     *
     * 인증자가 **본문까지 봐야** 만료와 업무 오류를 가를 수 있다 — 기본값 `null` 은
     * 본문 없는 401(프록시가 낸 것)이라 지금까지의 동작이 그대로 걸린다.
     */
    private fun unauthorized(
        sentToken: String? = "access-1",
        prior: Response? = null,
        epoch: Int? = 1,
        code: String? = null,
        rawBody: String? = null,
    ): Response {
        val request = Request.Builder()
            .url("https://api.test/me/courses")
            .apply {
                sentToken?.let { header("Authorization", "Bearer $it") }
                epoch?.let { tag(ApiClient.SessionTag::class.java, ApiClient.SessionTag(it)) }
            }
            .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .apply {
                // 본문은 준 경우에만 붙인다. OkHttp 는 **본문 있는 응답을 priorResponse 로 못 받는다**
                val problem = rawBody ?: code?.let { """{"status":401,"code":"$it"}""" }
                problem?.let { body(it.toResponseBody("application/problem+json".toMediaType())) }
                prior?.let(::priorResponse)
            }
            .build()
    }

    @Test
    fun `401 이면 재발급하고 새 토큰으로 다시 보낸다`() {
        var saved: RefreshResponseDto? = null
        val authenticator = TokenAuthenticator(
            sessionEpoch = { 1 },
            currentAccessToken = { "access-1" },
            currentRefreshToken = { "refresh-1" },
            refresh = { RefreshOutcome.Renewed(RefreshResponseDto("access-2", "refresh-2")) },
            onRefreshed = { _, renewed -> saved = renewed; true },
            onGiveUp = { _ -> error("여기 오면 안 된다") },
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
            sessionEpoch = { 1 },
            currentAccessToken = { access },
            currentRefreshToken = { "refresh-1" },
            refresh = {
                calls.incrementAndGet()
                Thread.sleep(30) // 회전 왕복을 흉내낸다
                RefreshOutcome.Renewed(RefreshResponseDto("access-2", "refresh-2"))
            },
            onRefreshed = { _, renewed -> access = renewed.accessToken; true },
            onGiveUp = { _ -> signedOut = true },
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
            sessionEpoch = { 1 },
            currentAccessToken = { "access-1" },
            currentRefreshToken = { "refresh-1" },
            refresh = { RefreshOutcome.Failed },
            onRefreshed = { _, _ -> error("여기 오면 안 된다") },
            onGiveUp = { _ -> signedOut = true },
        )

        val retry = authenticator.authenticate(null, unauthorized())

        assertNull(retry)
        assertFalse(signedOut)
    }

    @Test
    fun `리프레시가 만료됐을 때만 세션을 지운다`() {
        var signedOut = false
        val authenticator = TokenAuthenticator(
            sessionEpoch = { 1 },
            currentAccessToken = { "access-1" },
            currentRefreshToken = { "refresh-1" },
            refresh = { RefreshOutcome.Expired }, // 401 INVALID_REFRESH_TOKEN
            onRefreshed = { _, _ -> error("여기 오면 안 된다") },
            onGiveUp = { _ -> signedOut = true },
        )

        val retry = authenticator.authenticate(null, unauthorized())

        assertNull(retry)
        assertTrue(signedOut)
    }

    @Test
    fun `게스트는 재발급을 시도하지 않는다`() {
        var refreshed = false
        val authenticator = TokenAuthenticator(
            sessionEpoch = { 1 },
            currentAccessToken = { null },
            currentRefreshToken = { null },
            refresh = { refreshed = true; RefreshOutcome.Failed },
            onRefreshed = { _, _ -> true },
            onGiveUp = { _ -> error("게스트 세션을 지울 일은 없다") },
        )

        val retry = authenticator.authenticate(null, unauthorized(sentToken = null))

        assertNull(retry)
        // 공개 API 의 401 은 서버 문제다 — 그대로 화면까지 올린다
        assertFalse(refreshed)
    }

    @Test
    fun `재발급 중 로그아웃하면 토큰을 되살리지 않는다`() {
        // 왕복이 끝나기 전에 로그아웃하면 그 토큰은 이미 남의 것이다 (#74 리뷰)
        var applied = false
        val authenticator = TokenAuthenticator(
            sessionEpoch = { 2 }, // 로그아웃으로 세대가 올라간 뒤
            currentAccessToken = { null },
            currentRefreshToken = { "refresh-1" },
            refresh = { RefreshOutcome.Renewed(RefreshResponseDto("access-2", "refresh-2")) },
            onRefreshed = { epoch, _ ->
                // SessionStore 가 세대를 보고 거절한다
                applied = epoch == 2
                false
            },
            onGiveUp = { _ -> error("리프레시가 죽은 게 아니다") },
        )

        // 요청은 세대 1(로그인 상태)에서 만들어졌다
        val retry = authenticator.authenticate(null, unauthorized(epoch = 1))

        assertNull(retry)
        assertFalse(applied)
    }

    @Test
    fun `재발급 중 계정이 바뀌면 원 요청을 재시도하지 않는다`() {
        // A 요청을 B 토큰으로 다시 보내면 계정 간 데이터 오염이다 (#74 리뷰)
        var refreshed = false
        val authenticator = TokenAuthenticator(
            sessionEpoch = { 2 }, // B 로 로그인해 세대가 올라갔다
            currentAccessToken = { "B-access" },
            currentRefreshToken = { "B-refresh" },
            refresh = { refreshed = true; RefreshOutcome.Failed },
            onRefreshed = { _, _ -> error("여기 오면 안 된다") },
            onGiveUp = { _ -> error("여기 오면 안 된다") },
        )

        // A 세대(1)에서 만들어진 요청이 지금 401 로 돌아왔다
        val retry = authenticator.authenticate(null, unauthorized(sentToken = "A-access", epoch = 1))

        assertNull(retry)
        // B 의 리프레시를 A 요청 때문에 쓰지도 않는다
        assertFalse(refreshed)
    }

    @Test
    fun `만료로 끝나도 그사이 계정이 바뀌었으면 그 세대로 알린다`() {
        // A 리프레시가 401 로 죽었는데 그사이 B 로 갈아탔다면, B 를 로그아웃시킬 이유가 없다.
        // 저장 쪽(SessionStore.signOut(expectedEpoch))이 거절할 수 있게 **세대를 넘긴다** (#74 리뷰)
        var reportedEpoch: Int? = null
        val authenticator = TokenAuthenticator(
            sessionEpoch = { 1 },
            currentAccessToken = { "A-access" },
            currentRefreshToken = { "A-refresh" },
            refresh = { RefreshOutcome.Expired },
            onRefreshed = { _, _ -> error("여기 오면 안 된다") },
            onGiveUp = { epoch -> reportedEpoch = epoch },
        )

        authenticator.authenticate(null, unauthorized(sentToken = "A-access", epoch = 1))

        assertEquals(1, reportedEpoch)
    }

    @Test
    fun `재발급한 토큰으로도 401 이면 한 번만 시도하고 멈춘다`() {
        var calls = 0
        val authenticator = TokenAuthenticator(
            sessionEpoch = { 1 },
            currentAccessToken = { "access-1" },
            currentRefreshToken = { "refresh-1" },
            refresh = { calls++; RefreshOutcome.Renewed(RefreshResponseDto("access-2", "refresh-2")) },
            onRefreshed = { _, _ -> true },
            onGiveUp = { _ -> },
        )

        val retry = authenticator.authenticate(null, unauthorized(prior = unauthorized()))

        assertNull(retry)
        assertEquals(0, calls)
    }

    /**
     * 업무 401 을 만료로 처리하지 않는다. (#198 리뷰 · 명세 §2-2 · 부록 D)
     *
     * 탈퇴 재인증은 `MeApi` 를 통해 **공통 클라이언트**로 나가고, 여기에 이 인증자가 붙어
     * 있다. 그런데 재인증은 비밀번호가 틀리면 `401 REAUTH_FAILED`, 5분 토큰이 지나면
     * `401 INVALID_REAUTH_TOKEN` 을 **정상 업무 오류로** 준다.
     *
     * 상태 코드만 보고 재발급하면 두 가지가 한꺼번에 무너진다. 하나는 **비밀번호를 틀릴
     * 때마다 리프레시가 회전**하는 것이고, 다른 하나는 그 재발급이 한 번 실패했을 때
     * **아직 멀쩡한 세션이 로그아웃**되는 것이다.
     */
    @Test
    fun `재인증 실패 401 은 재발급하지 않고 그대로 올린다`() {
        var refreshCalls = 0
        var signedOut = false
        val authenticator = TokenAuthenticator(
            sessionEpoch = { 1 },
            currentAccessToken = { "access-1" },
            currentRefreshToken = { "refresh-1" },
            refresh = { refreshCalls++; RefreshOutcome.Expired },
            onRefreshed = { _, _ -> true },
            onGiveUp = { _ -> signedOut = true },
        )

        val retry = authenticator.authenticate(null, unauthorized(code = "REAUTH_FAILED"))

        // null 을 돌려주면 OkHttp 가 원 401 을 그대로 호출부까지 올린다
        assertNull(retry)
        assertEquals(0, refreshCalls)
        assertFalse("현재 비밀번호를 틀렸다고 로그아웃시키면 안 된다", signedOut)
    }

    @Test
    fun `만료된 탈퇴 토큰 401 도 재발급하지 않는다`() {
        var refreshCalls = 0
        var signedOut = false
        val authenticator = TokenAuthenticator(
            sessionEpoch = { 1 },
            currentAccessToken = { "access-1" },
            currentRefreshToken = { "refresh-1" },
            refresh = { refreshCalls++; RefreshOutcome.Expired },
            onRefreshed = { _, _ -> true },
            onGiveUp = { _ -> signedOut = true },
        )

        val retry = authenticator.authenticate(null, unauthorized(code = "INVALID_REAUTH_TOKEN"))

        assertNull(retry)
        assertEquals(0, refreshCalls)
        // 5분이 지났으면 재인증부터 다시 할 일이지, 로그인부터 다시 할 일이 아니다
        assertFalse(signedOut)
    }

    @Test
    fun `UNAUTHORIZED 는 지금까지처럼 재발급한다`() {
        val authenticator = TokenAuthenticator(
            sessionEpoch = { 1 },
            currentAccessToken = { "access-1" },
            currentRefreshToken = { "refresh-1" },
            refresh = { RefreshOutcome.Renewed(RefreshResponseDto("access-2", "refresh-2")) },
            onRefreshed = { _, _ -> true },
            onGiveUp = { _ -> error("여기 오면 안 된다") },
        )

        val retry = authenticator.authenticate(null, unauthorized(code = "UNAUTHORIZED"))

        assertEquals("Bearer access-2", retry?.header("Authorization"))
    }

    @Test
    fun `코드를 못 읽는 401 은 재발급한다`() {
        // 프록시·게이트웨이가 HTML 오류 페이지를 돌려주는 경우다. 업무 오류일 수 없으므로
        // 여기서 막으면 **로그인 상태로 보이는데 아무것도 안 되는** 상태에 갇힌다
        val authenticator = TokenAuthenticator(
            sessionEpoch = { 1 },
            currentAccessToken = { "access-1" },
            currentRefreshToken = { "refresh-1" },
            refresh = { RefreshOutcome.Renewed(RefreshResponseDto("access-2", "refresh-2")) },
            onRefreshed = { _, _ -> true },
            onGiveUp = { _ -> error("여기 오면 안 된다") },
        )

        val retry = authenticator.authenticate(null, unauthorized(rawBody = "<html>Gateway Timeout</html>"))

        assertEquals("Bearer access-2", retry?.header("Authorization"))
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
