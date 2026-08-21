package com.runninggu.app.data

import com.runninggu.app.data.local.AuthTokens
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.remote.ApiClient
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.RefreshOutcome
import com.runninggu.app.data.remote.apiCall
import com.runninggu.app.data.remote.asRefreshFailure
import com.runninggu.app.data.local.SessionValidation
import com.runninggu.app.data.local.SessionValidator
import com.runninggu.app.data.remote.ContestApi
import com.runninggu.app.data.remote.MeApi
import com.runninggu.app.data.remote.mapper.toSessionProfile
import com.runninggu.app.data.remote.CourseApi
import com.runninggu.app.data.remote.RefreshRequestDto
import com.runninggu.app.data.remote.RefreshResponseDto
import com.runninggu.app.data.remote.TokenApi
import com.runninggu.app.data.remote.TokenAuthenticator
import com.runninggu.app.data.repository.ContestRepository
import com.runninggu.app.data.repository.CourseRepository
import com.runninggu.app.data.repository.RemoteContestRepository
import com.runninggu.app.data.repository.RemoteCourseRepository
import kotlinx.coroutines.runBlocking
import retrofit2.Retrofit
import retrofit2.create

/**
 * 앱이 서버와 이야기하는 **단 하나의 진입점**. (SPEC §9.3 · AP-14)
 *
 * 지금까지 `ApiClient.create()` 를 부르는 곳이 아예 없어서, Retrofit 클라이언트가 만들어진
 * 적이 없었다 — 화면이 전부 스텁을 보고 있었기 때문이다. 서버 엔드포인트가 서면 화면의
 * 저장소만 여기 것으로 바꾸면 된다(AGENTS 4장).
 *
 * **토큰은 여기서 붙는다.** [SessionStore.tokens] 를 `TokenProvider` 로 물려 두었으므로,
 * 로그인 뒤에는 모든 요청에 `Authorization: Bearer` 가 자동으로 실린다(§0-2). 토큰이 없으면
 * 헤더를 안 붙이므로 공개 API 는 게스트로 그대로 동작한다.
 *
 * DI 라이브러리를 쓰지 않는 이유는 새 의존성을 늘리지 않기 위해서다(AGENTS 7장). 화면이
 * 늘어 손이 가면 그때 팀에 물어 도입한다.
 */
object ServiceLocator {

    /**
     * 프로세스에 하나만 둔다 — OkHttp 커넥션 풀과 스레드를 재사용해야 한다.
     *
     * 토큰을 **호출 시점에** 읽는다. 인스턴스를 만들 때 값을 박아 두면 로그인 이후에도
     * 게스트로 나간다.
     */
    private val retrofit: Retrofit by lazy {
        ApiClient.create(
            sessionProvider = {
                // 토큰과 세대를 한 번에 읽는다 (#74 리뷰)
                val snapshot = SessionStore.snapshot()
                ApiClient.Session(snapshot.tokens?.accessToken, snapshot.epoch)
            },
            authenticator = tokenAuthenticator,
        )
    }

    /**
     * 재발급 전용 클라이언트. **authenticator 를 달지 않는다** — 재발급 호출이 401 을 받으면
     * 다시 재발급을 부르는 무한 고리가 된다.
     */
    private val tokenApi: TokenApi by lazy { ApiClient.create().create() }

    /**
     * `401` 을 여기서 처리한다. 화면은 401 을 모르고, **세션이 사라지면 로그인으로**만 지킨다
     * (#74 리뷰 합의 · D-27 `returnTo`).
     */
    private val tokenAuthenticator by lazy {
        TokenAuthenticator(
            sessionEpoch = { SessionStore.sessionEpoch },
            currentAccessToken = { SessionStore.tokens?.accessToken },
            currentRefreshToken = { SessionStore.tokens?.refreshToken },
            refresh = ::refreshTokens,
            onRefreshed = { epoch, renewed ->
                // 리프레시가 회전한다 — 둘 다 갈아끼운다. 세대가 바뀌었으면 false 가 온다 (§1-9)
                SessionStore.updateTokens(
                    expectedEpoch = epoch,
                    tokens = AuthTokens(renewed.accessToken, renewed.refreshToken),
                )
            },
            // 리프레시까지 만료·revoked 일 때만 재로그인이다
            onGiveUp = { epoch -> SessionStore.signOut(expectedEpoch = epoch) },
        )
    }

    /**
     * 재발급 한 번. **실패를 구분해서 돌려준다.** (§1-9)
     *
     * `401` 만 재로그인 신호다. 네트워크가 끊긴 것까지 로그아웃으로 처리하면 지하철에서
     * 앱을 켰다가 세션과 찜 캐시를 잃는다(#74 리뷰 · §4.13 오프라인 규칙).
     *
     * `Authenticator` 는 블로킹 계약이라 여기서 기다린다.
     */
    private fun refreshTokens(refreshToken: String): RefreshOutcome = try {
        val renewed = runBlocking { apiCall { tokenApi.refresh(RefreshRequestDto(refreshToken)) } }
        RefreshOutcome.Renewed(renewed)
    } catch (e: ApiException) {
        // 401 만 재로그인이다. 네트워크·5xx 는 이번 요청만 실패시키고 세션은 지킨다
        e.asRefreshFailure()
    }

    val meApi: MeApi by lazy { retrofit.create() }

    /**
     * 앱 시작 세션 검증. (`screen-api-matrix` A0 · API 명세 §2)
     *
     * **`401` 만 죽은 세션이다.** 여기까지 온 `401` 은 [tokenAuthenticator] 가 재발급까지
     * 시도한 뒤의 결과라 재로그인 말고는 방법이 없다.
     *
     * 네트워크·5xx 는 [SessionValidation.Unknown] 이다 — 지하철에서 앱을 켰다고 로그아웃되면
     * 안 된다(#89 리뷰). 응답 모양이 계약과 다른 경우(모르는 `loginProvider`)도 세션을
     * 죽이지 않는다. 그건 서버 배포 사고지 사용자 세션 문제가 아니다.
     */
    val sessionValidator = SessionValidator {
        try {
            SessionValidation.Valid(apiCall { meApi.me() }.toSessionProfile())
        } catch (e: ApiException.Http) {
            if (e.status == HTTP_UNAUTHORIZED) SessionValidation.Expired else SessionValidation.Unknown
        } catch (e: ApiException) {
            SessionValidation.Unknown
        } catch (e: IllegalArgumentException) {
            SessionValidation.Unknown
        }
    }

    private const val HTTP_UNAUTHORIZED = 401

    val contestApi: ContestApi by lazy { retrofit.create() }
    val courseApi: CourseApi by lazy { retrofit.create() }

    /** 서버 구현. 화면은 인터페이스만 보므로 스텁과 바꿔 끼울 수 있다. */
    val contestRepository: ContestRepository by lazy { RemoteContestRepository(contestApi) }
    val courseRepository: CourseRepository by lazy { RemoteCourseRepository(courseApi) }
}
