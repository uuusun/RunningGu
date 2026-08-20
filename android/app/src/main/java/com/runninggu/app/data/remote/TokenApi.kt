package com.runninggu.app.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 토큰 재발급. (API 명세 §1-9)
 *
 * 인증 API 중 **이것만** `data/remote` 에 있다 — 나머지(로그인·가입)는 화면 흐름이라
 * `ui/auth` 가 맡고, 이건 네트워크 계층이 401 을 만났을 때 스스로 쓰는 것이기 때문이다.
 */
interface TokenApi {

    /**
     * `200` 새 액세스 + **회전된 리프레시**. 실패는 `401 INVALID_REFRESH_TOKEN` 이다.
     *
     * 리프레시가 회전하므로 응답의 **두 값을 함께** 저장해야 한다 — 액세스만 갈아끼우면
     * 다음 재발급이 실패한다.
     */
    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): RefreshResponseDto
}

@Serializable
data class RefreshRequestDto(val refreshToken: String)

@Serializable
data class RefreshResponseDto(val accessToken: String, val refreshToken: String)
