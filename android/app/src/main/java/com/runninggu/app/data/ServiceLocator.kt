package com.runninggu.app.data

import android.content.Context
import com.runninggu.app.data.local.AuthTokens
import com.runninggu.app.data.local.FusedLocationProvider
import com.runninggu.app.data.local.LocationProvider
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.remote.ApiClient
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.RefreshOutcome
import com.runninggu.app.data.remote.apiCall
import com.runninggu.app.data.remote.asRefreshFailure
import com.runninggu.app.data.local.SessionValidator
import com.runninggu.app.data.remote.ApiSessionValidator
import com.runninggu.app.data.remote.AuthApi
import com.runninggu.app.data.remote.ContestApi
import com.runninggu.app.data.remote.MeApi
import com.runninggu.app.data.remote.CourseApi
import com.runninggu.app.data.remote.FestivalApi
import com.runninggu.app.data.remote.GeocodeApi
import com.runninggu.app.data.remote.PoiApi
import com.runninggu.app.data.remote.RefreshRequestDto
import com.runninggu.app.data.remote.RefreshResponseDto
import com.runninggu.app.data.remote.TokenApi
import com.runninggu.app.data.remote.TokenAuthenticator
import com.runninggu.app.data.repository.AuthRepository
import com.runninggu.app.data.repository.ContestRepository
import com.runninggu.app.data.repository.RemoteAuthRepository
import com.runninggu.app.data.repository.CourseRepository
import com.runninggu.app.data.repository.NearStubbedCourseRepository
import com.runninggu.app.data.repository.FestivalRepository
import com.runninggu.app.data.repository.RemoteFestivalRepository
import com.runninggu.app.data.repository.GeocodeRepository
import com.runninggu.app.data.repository.PoiRepository
import com.runninggu.app.data.repository.RemoteGeocodeRepository
import com.runninggu.app.data.repository.RemotePoiRepository
import com.runninggu.app.data.repository.FakeSavedCourseRepository
import com.runninggu.app.data.repository.SavedCourseRepository
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
     * 기기에서 값을 얻는 것들(위치 등)이 Context 를 필요로 한다. 앱 시작 때 한 번 물린다.
     *
     * **Application context 만 받는다** — Activity 를 들고 있으면 화면 회전에서 샌다.
     */
    private var appContext: Context? = null

    fun bind(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * "내 위치". (SPEC §4.11-1 ①)
     *
     * 서버가 아니라 기기에서 오는 값이지만, 화면이 의존성을 찾는 자리를 둘로 나누면
     * 어디를 봐야 할지 헷갈린다 — 여기 함께 둔다.
     */
    val locationProvider: LocationProvider by lazy {
        FusedLocationProvider(
            requireNotNull(appContext) { "ServiceLocator.bind(context) 를 먼저 불러야 한다" },
        )
    }

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

    val authApi: AuthApi by lazy { retrofit.create() }

    val meApi: MeApi by lazy { retrofit.create() }

    /**
     * 앱 시작 세션 검증. (`screen-api-matrix` A0 · API 명세 §2)
     *
     * 판정 규칙과 시간 제한은 [ApiSessionValidator] 가 갖는다 — 기기 없이 테스트하려고
     * 갈라 두었다.
     */
    val sessionValidator: SessionValidator by lazy {
        ApiSessionValidator(
            me = { apiCall { meApi.me() } },
            isSignedOut = { SessionStore.tokens == null },
        )
    }


    val contestApi: ContestApi by lazy { retrofit.create() }
    val courseApi: CourseApi by lazy { retrofit.create() }

    /**
     * 인증. (API 명세 §1)
     *
     * **A1 로그인·A2 가입이 이걸 쓴다**(2026-08-22). 서버 `auth/api` 에 `signup`·`login`·
     * `refresh`·`logout` 과 `email/exists`·`email/send-code`·`email/verify`·
     * `nickname/exists` 가 서면서 화면 기본값을 옮겼다 — 그전까지는 셋 다
     * `FakeAuthRepository` 를 보고 있었다.
     *
     * **A3 비밀번호 재설정만 아직 가짜다.** `auth/password/reset-request` ·
     * `auth/password/reset` 이 서버에 없다(AP-07). 서면 `ResetViewModel` 의
     * 기본값 한 줄만 바꾸면 된다.
     *
     * 카카오(`auth/kakao`·`auth/kakao/signup`)도 서버에 없는데, 부르는 화면이
     * 아직 없어 지금은 드러나지 않는다.
     */
    val authRepository: AuthRepository by lazy { RemoteAuthRepository(authApi, tokenApi) }

    /** 서버 구현. 화면은 인터페이스만 보므로 스텁과 바꿔 끼울 수 있다. */
    val contestRepository: ContestRepository by lazy { RemoteContestRepository(contestApi) }
    /**
     * S8 러닝코스. (API 명세 §6)
     *
     * **[내 주변]만 아직 스텁이다** — `/courses/near` 가 AP-25 에 묶여 있다.
     * 지역별·지역 칩은 #156 으로 서버가 섰다. 자세한 이유는
     * [NearStubbedCourseRepository] KDoc 에 있다.
     */
    val courseRepository: CourseRepository by lazy {
        NearStubbedCourseRepository(remote = RemoteCourseRepository(courseApi))
    }

    /**
     * 저장 코스. **아직 스텁이다** — 백엔드에 `/api/me/courses` 가 없다(§7-A · AP-07).
     *
     * 다른 항목처럼 `Remote…` 로 바꾸면 화면은 그대로 붙는다. 그때까지 스텁으로 두는 이유는,
     * 없는 엔드포인트를 부르면 화면이 오류만 보여줘서 만든 것을 확인할 수 없기 때문이다.
     */
    val savedCourseRepository: SavedCourseRepository by lazy { FakeSavedCourseRepository }

    val festivalApi: FestivalApi by lazy { retrofit.create() }

    /**
     * 홈 축제. (API 명세 4-1 · `GET /api/festivals`)
     *
     * **KTO 프록시라 `502`·`504` 가 실제로 난다.** 그래서 이 저장소는 실패를 삼키지
     * 않고 그대로 던지고, 홈이 영역 상태로 받는다(AGENTS 2장-5).
     */
    val festivalRepository: FestivalRepository by lazy { RemoteFestivalRepository(festivalApi) }

    // ── 서버에 선 나머지 ────────────────────────────────────────

    val poiApi: PoiApi by lazy { retrofit.create() }
    val geocodeApi: GeocodeApi by lazy { retrofit.create() }

    /**
     * 위저드 숙소·슬롯 후보. (API 명세 4-2 · `GET /api/pois`)
     *
     * 서버 `PoiController` 가 `category`·`lat`·`lng`·`radius`·`query`·`size` 를 받는다 —
     * 앱 [PoiApi] 와 같다.
     */
    val poiRepository: PoiRepository by lazy { RemotePoiRepository(poiApi) }

    /** S8 출발지 검색. (`GET /api/geocode`) 서버 `GeocodeController` 가 `query` 하나를 받는다. */
    val geocodeRepository: GeocodeRepository by lazy { RemoteGeocodeRepository(geocodeApi) }
}
