package com.runninggu.app.data.remote

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * `401` 을 만나면 토큰을 재발급하고 원 요청을 다시 보낸다. (API 명세 §1-9 · §0-2)
 *
 * **화면은 401 을 몰라도 된다.** 여기서 조용히 처리하고, 재발급까지 실패한 경우에만
 * 세션을 지운다 — 그러면 UI 는 "세션이 사라지면 로그인으로"(D-27 `returnTo`) 하나만
 * 지키면 된다(#74 리뷰에서 @mo-gun 님과 합의한 방향).
 *
 * 이게 없으면 액세스(30분)가 만료된 뒤 **로그인 상태로 보이는데 아무것도 안 되는** 상태에
 * 갇힌다. 앱을 하루 두 번만 열어도 거의 매번 걸린다.
 *
 * @param currentRefreshToken 지금 가진 리프레시. 없으면(게스트) 재발급하지 않는다.
 * @param onRefreshed 새 토큰 쌍 저장. **리프레시가 회전하므로 둘 다** 넘어온다.
 * @param onGiveUp 재발급 실패(리프레시 만료·revoked). 세션을 지우는 자리다.
 */
class TokenAuthenticator(
    private val currentRefreshToken: () -> String?,
    private val refresh: (String) -> RefreshResponseDto?,
    private val onRefreshed: (RefreshResponseDto) -> Unit,
    private val onGiveUp: () -> Unit,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // 재발급한 토큰으로도 401 이면 더 해볼 게 없다 — 무한 재시도를 막는다
        if (response.priorResponseCount() >= MAX_RETRY) return null

        // 게스트는 재발급할 게 없다. 공개 API 의 401 은 서버 문제이므로 그대로 올린다
        val refreshToken = currentRefreshToken() ?: return null

        val renewed = refresh(refreshToken)
        if (renewed == null) {
            // 리프레시가 만료·revoked 다(§1-9 `401 INVALID_REFRESH_TOKEN`). 재로그인이 필요하다
            onGiveUp()
            return null
        }
        onRefreshed(renewed)

        return response.request.newBuilder()
            .header("Authorization", "Bearer ${renewed.accessToken}")
            .build()
    }

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
        /** 한 번만 재발급해 본다. */
        const val MAX_RETRY = 1
    }
}
