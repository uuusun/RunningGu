package com.runninggu.app.data.remote

import com.runninggu.app.data.remote.dto.MeDto
import com.runninggu.app.data.remote.dto.UpdateMarketingRequest
import com.runninggu.app.data.remote.dto.UpdateNicknameRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH

/**
 * 내 정보. (API 명세 §2)
 *
 * **셋 다 같은 응답을 준다** — 명세가 "성공 시 현재 프로필을 같은 형태로 200 반환한다" 고
 * 못 박았다. 그래서 화면은 무엇이 바뀌었는지 따질 필요 없이 [MeDto] 로 세션을 통째로
 * 갈아끼우면 된다. 부분 응답이었다면 호출부마다 병합 규칙이 생겼을 자리다(#151).
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
}
