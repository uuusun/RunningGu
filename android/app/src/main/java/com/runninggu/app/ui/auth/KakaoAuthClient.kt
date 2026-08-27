package com.runninggu.app.ui.auth

import android.content.Context
import com.kakao.sdk.auth.model.OAuthToken
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import com.kakao.sdk.user.UserApiClient
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 카카오 SDK 에서 **액세스 토큰만** 받아 온다. (SPEC §4.1 · API 명세 §1-7 · AP-08)
 *
 * 이 토큰으로 우리 서버에 `POST /auth/kakao` 를 부르면 서버가 카카오에 유효성을 확인하고
 * 세션을 준다. **앱은 카카오에서 프로필을 직접 읽지 않는다** — 그건 서버가 한다(AGENTS 2장-3).
 *
 * ## 두 경로가 있다
 *
 * 카카오톡이 깔려 있으면 **톡으로**, 없으면 **카카오 계정 웹**으로 로그인한다. 톡 경로는
 * 사용자가 톡에서 취소하고 돌아올 수 있는데, 그때 SDK 가 [ClientErrorCause.Cancelled] 를
 * 준다. **그 경우 웹으로 다시 시도한다** — 카카오가 권하는 방식이고, 톡 계정과 다른 계정으로
 * 들어가려는 사용자가 여기서 막히지 않는다.
 *
 * 다만 **사용자가 웹에서도 취소하면 거기서 끝낸다.** 계속 되물으면 로그인 화면을 벗어날 수
 * 없다.
 */
sealed interface KakaoAuthResult {
    /** SDK 가 준 액세스 토큰. 서버에 그대로 넘긴다. */
    data class Token(val accessToken: String) : KakaoAuthResult

    /** 사용자가 그만뒀다. **오류가 아니다** — 아무 말도 하지 않는다. */
    data object Cancelled : KakaoAuthResult

    /** SDK 가 실패했다. 화면이 안내한다. */
    data class Failed(val cause: Throwable?) : KakaoAuthResult
}

/**
 * 카카오 로그인을 띄우고 토큰을 기다린다.
 *
 * SDK 는 콜백으로 결과를 주므로 코루틴으로 감싼다. **취소를 예외로 만들지 않는다** —
 * 사용자가 그만둔 것은 실패가 아니라서, 호출부가 `try/catch` 로 다루면 "로그인 실패"
 * 문구를 띄우게 된다.
 */
suspend fun requestKakaoToken(context: Context): KakaoAuthResult {
    if (!KakaoAuthAvailability.isReady) return KakaoAuthResult.Failed(null)
    val talkAvailable = UserApiClient.instance.isKakaoTalkLoginAvailable(context)
    if (!talkAvailable) return loginWithAccount(context)

    return when (val talk = loginWithTalk(context)) {
        // 톡에서 그만둔 것은 **웹으로 다시 물어본다.** 톡 계정과 다른 계정을 쓰려는
        // 사용자가 여기서 막히면 로그인할 방법이 없다
        is KakaoAuthResult.Cancelled -> loginWithAccount(context)
        else -> talk
    }
}

private suspend fun loginWithTalk(context: Context): KakaoAuthResult =
    suspendCancellableCoroutine { continuation ->
        UserApiClient.instance.loginWithKakaoTalk(context) { token, error ->
            continuation.resume(token.toResult(error))
        }
    }

private suspend fun loginWithAccount(context: Context): KakaoAuthResult =
    suspendCancellableCoroutine { continuation ->
        UserApiClient.instance.loginWithKakaoAccount(context) { token, error ->
            continuation.resume(token.toResult(error))
        }
    }

/**
 * SDK 콜백의 `(token, error)` 짝을 결과로 옮긴다.
 *
 * **둘 다 null 인 경우도 실패로 본다.** SDK 계약상 하나는 온다고 하지만, 그 약속이 깨졌을 때
 * "성공했는데 토큰이 없는" 상태를 만들면 화면이 로그인된 것처럼 그린다.
 */
private fun OAuthToken?.toResult(error: Throwable?): KakaoAuthResult = when {
    error is ClientError && error.reason == ClientErrorCause.Cancelled -> KakaoAuthResult.Cancelled
    error != null -> KakaoAuthResult.Failed(error)
    this != null -> KakaoAuthResult.Token(accessToken)
    else -> KakaoAuthResult.Failed(null)
}
