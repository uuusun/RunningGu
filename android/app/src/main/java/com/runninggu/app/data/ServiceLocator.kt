package com.runninggu.app.data

import com.runninggu.app.data.repository.RemoteItineraryRepository
import com.runninggu.app.data.repository.ItineraryRepository
import com.runninggu.app.data.remote.ItineraryApi
import com.runninggu.app.data.local.AuthTokens
import android.content.Context
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.local.cache.ContestCache
import com.runninggu.app.data.local.cache.RoomContestCache
import com.runninggu.app.data.local.cache.RunningGuDatabase
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
import com.runninggu.app.data.remote.FavoriteApi
import com.runninggu.app.data.remote.FestivalApi
import com.runninggu.app.data.remote.GeocodeApi
import com.runninggu.app.data.remote.PoiApi
import com.runninggu.app.data.remote.SavedCourseApi
import com.runninggu.app.data.remote.RefreshRequestDto
import com.runninggu.app.data.remote.RefreshResponseDto
import com.runninggu.app.data.remote.TokenApi
import com.runninggu.app.data.remote.TokenAuthenticator
import com.runninggu.app.data.repository.AuthRepository
import com.runninggu.app.data.repository.ContestRepository
import com.runninggu.app.data.repository.RemoteAuthRepository
import com.runninggu.app.data.repository.CourseRepository
import com.runninggu.app.data.repository.FestivalRepository
import com.runninggu.app.data.repository.RemoteFestivalRepository
import com.runninggu.app.data.repository.FavoriteRepository
import com.runninggu.app.data.repository.RemoteFavoriteRepository
import com.runninggu.app.data.repository.GeocodeRepository
import com.runninggu.app.data.repository.MemberRepository
import com.runninggu.app.data.repository.RemoteMemberRepository
import com.runninggu.app.data.repository.PoiRepository
import com.runninggu.app.data.repository.RemoteGeocodeRepository
import com.runninggu.app.data.repository.RemotePoiRepository
import com.runninggu.app.data.repository.RemoteSavedCourseRepository
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

    /** 계정 화면의 닉네임·마케팅 동의 변경. (API 명세 §2 · AP-13) */
    val memberRepository: MemberRepository by lazy { RemoteMemberRepository(meApi) }

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
     * **A3 비밀번호 재설정도 이제 서버를 본다.** `auth/password/reset-request` 가
     * #174 · #182 로 섰다. 새 비밀번호 설정 화면은 앱이 아니라 서버가 서빙하는
     * 웹 페이지라(§4.3 🔒), 앱은 `auth/password/reset` 을 부르지 않는다.
     *
     * **카카오(`auth/kakao`·`auth/kakao/signup`)도 화면이 붙었다.** `ui/auth/KakaoAuthClient`
     * 가 SDK 토큰을 받아 오고 `LoginViewModel` 이 `kakaoLogin` 을 부른다(#216). 가입자 탈퇴도
     * 이어졌다(#238). 남은 것은 **릴리스 키 해시**뿐이다 — 디버그 키 해시는 등록돼 있어 개발
     * 빌드에서는 동작하고, 업로드 키·Google 앱 서명 키는 AP-02 · 이슈 #108 에 걸려 있다.
     */
    val authRepository: AuthRepository by lazy { RemoteAuthRepository(authApi, tokenApi) }

    /**
     * 오프라인 폴백을 켤 Context. [RunningGuApplication] 이 시작할 때 한 번 넘긴다.
     *
     * **안 넘어와도 앱은 돈다** — 캐시만 없는 상태가 되고 서버만 본다. 단위 테스트가
     * `ServiceLocator` 를 건드릴 때가 그 경우다. 키가 없으면 그 기능만 끄는 다른 자리와
     * 같은 방식이다(카카오 SDK · NFR-1·3).
     */
    private var appContext: Context? = null

    /** 앱 시작에서 한 번 부른다. 저장소를 처음 꺼내기 전이어야 폴백이 붙는다. */
    fun bind(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 대회 읽기 캐시. (AP-05 · SPEC §6.1 · 이슈 #105)
     *
     * **첫 조회 때 파일을 연다** — 앱 시작을 늦추지 않으려고 `lazy` 다.
     */
    private val contestCache: ContestCache? by lazy {
        appContext?.let { RoomContestCache(RunningGuDatabase.open(it).contestCache()) }
    }

    /**
     * 서버 구현. 화면은 인터페이스만 보므로 스텁과 바꿔 끼울 수 있다.
     *
     * 성공한 대회 응답은 `cached_contest` 에 남고 **연결이 안 될 때만** 되살아난다.
     * 그전에는 오프라인에서 홈·캘린더·상세가 전부 오류였다.
     */
    val contestRepository: ContestRepository by lazy {
        RemoteContestRepository(contestApi, contestCache)
    }
    /**
     * S8 러닝코스. (API 명세 §6)
     *
     * **이제 셋 다 서버를 본다.** 지역별·지역 칩은 #156, [출발지 주변]은 `/courses/near` 가
     * #174(AP-25 OSM 도시 경로 생성)로 서면서 붙었다. 그전까지는 `near` 만 스텁으로
     * 보내는 한시적인 조합(`NearStubbedCourseRepository`)을 끼워 뒀고, 그 클래스는
     * 예정대로 지웠다.
     */
    val courseRepository: CourseRepository by lazy { RemoteCourseRepository(courseApi) }

    val savedCourseApi: SavedCourseApi by lazy { retrofit.create() }

    /**
     * 저장 코스. (API 명세 §7-A · `/api/me/courses`)
     *
     * **인증이 필요하다.** 게스트가 부르면 `401` 이라 `ApiException.Http.needsLogin` 이 뜬다 —
     * 화면은 로그인 유도로 끝내고 저장을 예약하지 않는다(D-27). 마이는 게스트면 아예
     * 부르지 않는다(`MyViewModel.loadCourses`).
     */
    val itineraryApi: ItineraryApi by lazy { retrofit.create() }

    /**
     * 동선. (API 명세 §5-1 · §5-2 · §5-4 · §5-6)
     *
     * **S10 마이 목록과 S7 결과 화면이 함께 쓴다.** S7 은 예전에
     * [com.runninggu.app.data.repository.FakeItineraryRepository] 를 직접 들고 있었는데,
     * 가짜에는 `save()` 가 없어 저장 CTA 를 붙일 수 없었다. 위저드가 서버 대회를 싣게
     * 되면서(#140 · `contestPhase`) 생성·저장을 함께 옮겼다.
     */
    val itineraryRepository: ItineraryRepository by lazy {
        RemoteItineraryRepository(itineraryApi)
    }

    val savedCourseRepository: SavedCourseRepository by lazy {
        RemoteSavedCourseRepository(savedCourseApi)
    }

    val favoriteApi: FavoriteApi by lazy { retrofit.create() }

    /**
     * 찜. (API 명세 §7-C · AP-21)
     *
     * `FavoriteStore` 가 이걸 본다 — 하트는 S2·S3·S10 이 같은 값을 봐야 해서 보관소가
     * 하나뿐이고, 그 보관소의 뒤가 여기다.
     */
    val favoriteRepository: FavoriteRepository by lazy { RemoteFavoriteRepository(favoriteApi) }

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
