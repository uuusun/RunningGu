package com.runninggu.app.data.remote

import com.runninggu.app.data.local.SessionValidation
import com.runninggu.app.data.local.SessionValidator
import com.runninggu.app.data.remote.dto.MeDto
import com.runninggu.app.data.remote.mapper.toSessionProfile
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `GET /api/me` 로 복원한 세션을 확인한다. (`screen-api-matrix` A0 · API 명세 §2)
 *
 * ## `401` 이라고 다 죽은 세션이 아니다
 *
 * 여기까지 `401` 이 올라왔다는 건 [TokenAuthenticator] 가 재발급을 시도한 **뒤**라는 뜻인데,
 * 그 시도가 두 가지 이유로 실패할 수 있다.
 *
 * | 재발급 결과 | 세션 | 여기서 할 일 |
 * |---|---|---|
 * | `401 INVALID_REFRESH_TOKEN` | 정말 죽었다 | 로그아웃 |
 * | 네트워크·5xx | **살아 있다** | 그대로 둔다 |
 *
 * 둘을 안 가르면 **지하철에서 앱을 켰다고 로그아웃됩니다**(#89 리뷰).
 *
 * 가르는 근거는 [isSignedOut] 이다. 재발급이 만료로 끝나면 `TokenAuthenticator` 가
 * `onGiveUp` 으로 이미 세션을 비운다(#74). 그러니 호출이 끝난 뒤 세션이 남아 있으면
 * **재발급이 만료가 아닌 이유로 실패한 것**이다.
 *
 * @param me `GET /api/me` 한 번. `apiCall` 로 감싼 것을 넘긴다
 * @param isSignedOut 지금 세션이 비었는가
 */
class ApiSessionValidator(
    private val me: suspend () -> MeDto,
    private val isSignedOut: () -> Boolean,
    /** 테스트에서 짧게 줄인다. 운영 값은 [TIMEOUT_MS] 다. */
    private val timeoutMs: Long = TIMEOUT_MS,
) : SessionValidator {

    override suspend fun validate(): SessionValidation {
        // **정해진 시간 안에 끝낸다.** 시작 화면이 이 결과를 기다리는데(SPEC §2.2),
        // 오프라인이면 OkHttp 타임아웃만큼 빈 화면이 된다 (#89 리뷰)
        val result = withTimeoutOrNull(timeoutMs) { validateOnce() }
        return result ?: SessionValidation.Unknown
    }

    private suspend fun validateOnce(): SessionValidation = try {
        SessionValidation.Valid(me().toSessionProfile())
    } catch (e: ApiException.Http) {
        when {
            e.status != HTTP_UNAUTHORIZED -> SessionValidation.Unknown
            // 재발급이 만료로 끝났으면 인증자가 이미 세션을 비웠다
            isSignedOut() -> SessionValidation.Expired
            // 세션이 남아 있다 = 재발급이 네트워크·5xx 로 실패했다. 지키고 넘어간다
            else -> SessionValidation.Unknown
        }
    } catch (e: ApiException) {
        // 네트워크·해석 실패. 서버 배포 사고로 사용자 세션을 죽이지 않는다
        SessionValidation.Unknown
    } catch (e: IllegalArgumentException) {
        // 모르는 loginProvider — 계약이 바뀐 것이지 세션 문제가 아니다
        SessionValidation.Unknown
    }

    companion object {
        /**
         * 시작 화면이 기다려 줄 시간. 넘으면 세션을 지킨 채 열고, 죽은 토큰이면 첫 인증
         * 요청이 `401` 을 받아 정리한다(#74).
         */
        const val TIMEOUT_MS = 3_000L

        private const val HTTP_UNAUTHORIZED = 401
    }
}
