package com.runninggu.app.data.remote

import com.runninggu.app.data.remote.dto.LogoutRequestDto
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * **인증자를 타면 안 되는 호출들.** (API 명세 §1-9 · §1-10)
 *
 * 이 인터페이스는 [ApiClient.create] 의 **인증자 없는 클라이언트**로 만든다. 두 호출 다
 * 리프레시 토큰을 본문에 담아 스스로 자격을 증명하므로 `Authorization` 헤더가 필요 없고,
 * **인증자를 타면 오히려 틀린다.**
 *
 * 재발급이 인증자를 타면 401 → 재발급 → 401 로 재귀한다. 로그아웃은 더 조용히 틀린다 —
 * 액세스가 만료된 채로 부르면 401 에서 인증자가 리프레시를 R1 → R2 로 회전시키고, 원래
 * 요청은 **본문에 담긴 옛 R1 그대로 재시도**된다. 서버는 이미 revoke 된 R1 을 멱등하게
 * `204` 로 받아 주고, **새로 발급된 R2 는 살아남는다.** 사용자는 로그아웃했다고 믿는데
 * 서버에는 쓸 수 있는 세션이 남는다(이슈 #113).
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

    /**
     * 로그아웃 — 그 리프레시가 속한 family 의 현재 토큰을 revoke 한다. `204`. (§1-10)
     *
     * **이미 revoke·만료됐거나 모르는 토큰도 `204`** 다(멱등). 앱이 재시도해도 안전하고,
     * "이미 로그아웃됨" 을 오류로 받아 화면이 실패로 떨어지는 일이 없다.
     */
    @POST("auth/logout")
    suspend fun logout(@Body body: LogoutRequestDto)
}

@Serializable
data class RefreshRequestDto(val refreshToken: String)

@Serializable
data class RefreshResponseDto(val accessToken: String, val refreshToken: String)
