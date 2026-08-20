package com.runninggu.app.data.remote

import com.runninggu.app.data.local.AuthTokens
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.local.SessionStore
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.create
import kotlinx.coroutines.runBlocking

/**
 * 로그인한 뒤 요청에 토큰이 실리는지. (API 명세 §0-2 · AP-14)
 *
 * **이게 안 되면 로그인해도 모든 API 가 게스트로 나간다.** 실제로 그 상태였고
 * ([ServiceLocator] 가 없어 `ApiClient.create()` 를 부르는 곳조차 없었다),
 * 배선이 풀리는 것을 여기서 잡는다.
 */
class TokenProviderTest {

    @After
    fun tearDown() {
        SessionStore.signOut()
    }

    /** 요청 헤더만 들여다보고 가짜 응답을 돌려준다 — 네트워크를 타지 않는다. */
    private class HeaderSpy : Interceptor {
        var authorization: String? = null

        override fun intercept(chain: Interceptor.Chain): Response {
            authorization = chain.request().header("Authorization")
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"items":[]}""".toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun callRegions(spy: HeaderSpy) {
        val api: CourseApi = ApiClient
            .create(
                sessionProvider = {
                    val snapshot = SessionStore.snapshot()
                    ApiClient.Session(snapshot.tokens?.accessToken, snapshot.epoch)
                },
                extraInterceptors = listOf(spy),
            )
            .create()
        runBlocking { api.regions() }
    }

    @Test
    fun `게스트는 Authorization 을 붙이지 않는다`() {
        val spy = HeaderSpy()

        callRegions(spy)

        // 공개 API 는 토큰 없이도 동작해야 한다 (§0-2 게스트 둘러보기)
        assertNull(spy.authorization)
    }

    @Test
    fun `로그인하면 Bearer 토큰이 실린다`() {
        SessionStore.signIn(
            SessionProfile("러너", "runner@test.com", LoginProvider.EMAIL),
            tokens = AuthTokens(accessToken = "access-1", refreshToken = "refresh-1"),
        )
        val spy = HeaderSpy()

        callRegions(spy)

        assertEquals("Bearer access-1", spy.authorization)
    }

    @Test
    fun `로그아웃하면 다시 게스트로 나간다`() {
        SessionStore.signIn(
            SessionProfile("러너", "runner@test.com", LoginProvider.EMAIL),
            tokens = AuthTokens("access-1", "refresh-1"),
        )
        SessionStore.signOut()
        val spy = HeaderSpy()

        callRegions(spy)

        // 토큰을 인스턴스에 박아 두면 여기서 옛 토큰이 남는다
        assertNull(spy.authorization)
    }

    @Test
    fun `토큰은 만들 때가 아니라 부를 때 읽는다`() {
        val spy = HeaderSpy()
        val api: CourseApi = ApiClient
            .create(
                sessionProvider = {
                    val snapshot = SessionStore.snapshot()
                    ApiClient.Session(snapshot.tokens?.accessToken, snapshot.epoch)
                },
                extraInterceptors = listOf(spy),
            )
            .create()

        // 클라이언트를 만든 **뒤에** 로그인해도 다음 호출부터 토큰이 실려야 한다
        SessionStore.signIn(
            SessionProfile("러너", null, LoginProvider.KAKAO),
            tokens = AuthTokens("access-2", "refresh-2"),
        )
        runBlocking { api.regions() }

        assertEquals("Bearer access-2", spy.authorization)
    }
}
