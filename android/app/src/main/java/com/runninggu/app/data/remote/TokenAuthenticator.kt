package com.runninggu.app.data.remote

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * 재발급 시도 결과. (API 명세 §1-9)
 *
 * **실패를 한 덩어리로 뭉치면 안 된다.** 리프레시가 죽은 것(재로그인 필요)과 네트워크가
 * 끊긴 것(다음에 다시 하면 됨)은 사용자에게 전혀 다른 일이다 — 뭉치면 지하철에서
 * 잠깐 끊겼다고 로그아웃된다(#74 리뷰).
 */
sealed interface RefreshOutcome {
    data class Renewed(val tokens: RefreshResponseDto) : RefreshOutcome

    /** `401 INVALID_REFRESH_TOKEN` — 만료·revoked. **이때만** 재로그인이다. */
    data object Expired : RefreshOutcome

    /** 네트워크 실패·5xx. 이번 요청만 실패시키고 **세션은 지킨다**. */
    data object Failed : RefreshOutcome
}

/**
 * 재발급 호출이 실패했을 때 어떤 실패인지 가른다. (§1-9)
 *
 * **`401` 만 재로그인 신호다.** 네트워크가 끊긴 것·서버 5xx 까지 세션 삭제로 처리하면,
 * 지하철에서 앱을 켰다가 로그인과 찜 캐시를 잃는다(#74 리뷰 · §4.13 오프라인 규칙).
 */
fun ApiException.asRefreshFailure(): RefreshOutcome =
    if (this is ApiException.Http && status == HTTP_UNAUTHORIZED) {
        RefreshOutcome.Expired
    } else {
        RefreshOutcome.Failed
    }

/** `401` — 리프레시가 만료·revoked 됐다는 뜻이다. */
private const val HTTP_UNAUTHORIZED = 401

/**
 * `401` 을 만나면 토큰을 재발급하고 원 요청을 다시 보낸다. (API 명세 §1-9 · §0-2)
 *
 * **화면은 401 을 몰라도 된다.** 여기서 조용히 처리하고, 리프레시까지 죽은 경우에만
 * 세션을 지운다 — 그러면 UI 는 "세션이 사라지면 로그인으로"(D-27 `returnTo`) 하나만
 * 지키면 된다.
 *
 * 이게 없으면 액세스(30분)가 만료된 뒤 **로그인 상태로 보이는데 아무것도 안 되는** 상태에
 * 갇힌다.
 *
 * @param currentAccessToken 지금 세션의 액세스. 동시 401 에서 "누가 이미 갱신했나" 를 본다.
 * @param currentRefreshToken 지금 리프레시. 없으면(게스트) 재발급하지 않는다.
 * @param onRefreshed 새 토큰 쌍 저장. **리프레시가 회전하므로 둘 다** 넘어온다.
 * @param onGiveUp 리프레시가 죽었다. 세션을 지우는 자리다.
 */
class TokenAuthenticator(
    private val currentAccessToken: () -> String?,
    private val currentRefreshToken: () -> String?,
    private val refresh: (String) -> RefreshOutcome,
    private val onRefreshed: (RefreshResponseDto) -> Unit,
    private val onGiveUp: () -> Unit,
) : Authenticator {

    /**
     * **한 번에 하나만 재발급한다.**
     *
     * 리프레시는 회전한다(§1-9). 401 이 동시에 여럿 나면 각 스레드가 같은 리프레시로
     * 재발급을 부르고, 먼저 성공한 쪽이 그 토큰을 무효로 만든다. 그러면 나머지가
     * `401 INVALID_REFRESH_TOKEN` 을 받아 **재발급에 성공했는데도 로그아웃된다**
     * — 한 화면에서 요청을 둘만 보내도 걸린다(#74 리뷰).
     */
    @Synchronized
    override fun authenticate(route: Route?, response: Response): Request? {
        // 재발급한 토큰으로도 401 이면 서버 문제다 — 무한 재시도를 막는다
        if (response.priorResponseCount() >= MAX_RETRY) return null

        // 게스트는 재발급할 게 없다. 공개 API 의 401 은 그대로 화면까지 올린다
        val refreshToken = currentRefreshToken() ?: return null

        // 기다리는 사이 다른 요청이 이미 갱신해 뒀으면, 그 토큰으로 다시 보내기만 하면 된다
        val failed = response.request.header(AUTHORIZATION)?.removePrefix(BEARER)
        val current = currentAccessToken()
        if (current != null && current != failed) return response.request.retryWith(current)

        return when (val outcome = refresh(refreshToken)) {
            is RefreshOutcome.Renewed -> {
                onRefreshed(outcome.tokens)
                response.request.retryWith(outcome.tokens.accessToken)
            }

            // 재로그인 신호는 이것 하나뿐이다
            RefreshOutcome.Expired -> {
                onGiveUp()
                null
            }

            // 끊겼을 뿐이다. 세션을 지우면 사용자가 이유 없이 튕긴다
            RefreshOutcome.Failed -> null
        }
    }

    private fun Request.retryWith(accessToken: String): Request =
        newBuilder().header(AUTHORIZATION, "$BEARER$accessToken").build()

    private fun Response.priorResponseCount(): Int {
        var count = 0
        var prior = priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
        const val BEARER = "Bearer "

        /** 한 번만 재발급해 본다. */
        const val MAX_RETRY = 1
    }
}
