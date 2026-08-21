package com.runninggu.app.data.remote

import com.runninggu.app.data.remote.dto.MeDto
import retrofit2.http.GET

/**
 * 내 정보. (API 명세 §2)
 *
 * P0 에서 앱이 쓰는 건 **조회 하나**다 — 앱 시작 세션 검증(A0)과 마이 화면이 같은 응답을
 * 본다. 닉네임 변경·약관 변경·비밀번호 변경은 계정 화면을 붙일 때 이 파일에 함께 넣는다.
 */
interface MeApi {

    @GET("me")
    suspend fun me(): MeDto
}
