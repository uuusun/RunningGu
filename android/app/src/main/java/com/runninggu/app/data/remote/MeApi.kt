package com.runninggu.app.data.remote

import com.runninggu.app.data.remote.dto.MeDto
import com.runninggu.app.data.remote.dto.PasswordChangeRequest
import com.runninggu.app.data.remote.dto.PasswordChangeResponseDto
import com.runninggu.app.data.remote.dto.UpdateMarketingRequest
import com.runninggu.app.data.remote.dto.UpdateNicknameRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.PUT

/**
 * 내 정보. (API 명세 §2)
 *
 * **조회·닉네임·약관 셋은 같은 응답을 준다** — 명세가 "성공 시 현재 프로필을 같은 형태로
 * 200 반환한다" 고 못 박았다. 그래서 화면은 무엇이 바뀌었는지 따질 필요 없이 [MeDto] 로
 * 세션을 통째로 갈아끼우면 된다. 부분 응답이었다면 호출부마다 병합 규칙이 생겼을 자리다(#151).
 *
 * **[updatePassword] 만 모양이 다르다** — 프로필이 아니라 새 token pair 가 온다(§2-1).
 */
interface MeApi {

    @GET("me")
    suspend fun me(): MeDto

    /** 닉네임 변경. 중복이면 `409 NICKNAME_DUPLICATED`. (§2) */
    @PATCH("me")
    suspend fun updateNickname(@Body body: UpdateNicknameRequest): MeDto

    /**
     * 선택 약관(마케팅) 변경. (§2)
     *
     * **멱등이다** — 같은 값을 다시 보내도 `200` 이고 이력을 중복으로 쌓지 않는다. 그래서
     * 토글을 연타해도 실패 문구가 뜨지 않는다.
     */
    @PATCH("me/agreements")
    suspend fun updateMarketing(@Body body: UpdateMarketingRequest): MeDto

    /**
     * 비밀번호 변경 — **EMAIL 수단 전용**. (§2-1 · D-28)
     *
     * 성공하면 서버가 **기존 refresh token 을 전부 revoke** 하고 현재 기기용 token pair 를
     * 다시 준다. 호출부는 두 토큰을 **원자적으로** 갈아끼워야 한다 — 하나만 넣으면 다음
     * 재발급이 실패해 방금 비밀번호를 바꾼 사용자가 로그아웃된다.
     *
     * 현재 비밀번호가 틀리면 `400 CURRENT_PASSWORD_MISMATCH` 다. 새 비밀번호 형식 위반은
     * `400 INVALID_PASSWORD` 이고 둘은 사용자가 할 일이 다르다 — 문구를 갈라야 한다.
     */
    @PUT("me/password")
    suspend fun updatePassword(@Body body: PasswordChangeRequest): PasswordChangeResponseDto
}
