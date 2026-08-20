package com.runninggu.app.data.remote

import com.runninggu.app.BuildConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 백엔드 API 클라이언트. (SPEC §9.3 · §9.4)
 *
 * **자체 백엔드 외의 호스트는 여기 등장하면 안 된다**(AGENTS 2장-3).
 * KTO·카카오 REST 는 전부 서버를 거치고, 앱이 가진 키는 카카오 네이티브 키 하나뿐이다.
 */
object ApiClient {

    /** 액세스 토큰 공급자. AP-08 인증이 들어오면 실제 저장소를 물린다. */
    fun interface TokenProvider {
        fun accessToken(): String?
    }

    /**
     * 요청을 만들 때의 세션 세대. (#74 리뷰)
     *
     * 재발급 왕복 중에 로그아웃·계정 전환이 일어나면 이 값이 달라진다 —
     * [TokenAuthenticator] 가 그걸 보고 **남의 세션 요청을 재시도하지 않는다.**
     */
    data class SessionTag(val epoch: Int)

    /** 토큰 없이 도는 기본값 — 게스트도 공개 API 는 쓸 수 있다(§0-2). */
    private val NoToken = TokenProvider { null }

    @OptIn(ExperimentalSerializationApi::class)
    fun create(
        baseUrl: String = BuildConfig.BASE_URL,
        tokenProvider: TokenProvider = NoToken,
        /** 요청 시점의 세션 세대. 기본값은 세션 개념이 없는 호출(재발급 전용 클라이언트)용이다. */
        sessionEpoch: () -> Int = { 0 },
        json: Json = ApiJson,
        extraInterceptors: List<Interceptor> = emptyList(),
        /** `401` 재발급 처리. null 이면 401 이 그대로 화면까지 올라간다. */
        authenticator: Authenticator? = null,
    ): Retrofit {
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor(tokenProvider, sessionEpoch))
            .apply {
                extraInterceptors.forEach(::addInterceptor)
                authenticator?.let(::authenticator)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE.toMediaType()))
            .build()
    }

    /**
     * `Authorization: Bearer {accessToken}`. 토큰이 없으면 헤더를 붙이지 않는다(§0-2).
     *
     * 헤더를 붙일 때 **그 시점의 세션 세대를 태그로 함께 단다** — 재발급 후 재시도할 때
     * 이 요청이 아직 같은 세션의 것인지 확인하는 근거다(#74 리뷰).
     */
    private fun authInterceptor(provider: TokenProvider, sessionEpoch: () -> Int) =
        Interceptor { chain ->
            val token = provider.accessToken()
            val request = if (token.isNullOrBlank()) {
                chain.request()
            } else {
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .tag(SessionTag::class.java, SessionTag(sessionEpoch()))
                    .build()
            }
            chain.proceed(request)
        }

    private const val JSON_MEDIA_TYPE = "application/json"

    /**
     * 서버가 외부 API 를 프록시하는 구간이 있어(연결 1초·응답 2.5초 → 504, §0-5)
     * 앱 타임아웃은 그보다 넉넉해야 서버의 504 를 받아볼 수 있다.
     */
    private const val CONNECT_TIMEOUT_SEC = 10L
    private const val READ_TIMEOUT_SEC = 15L
}
