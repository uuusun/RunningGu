package com.runninggu.app.data.remote

import com.runninggu.app.data.remote.dto.GeocodeDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 출발지 검색. (API 명세 §4-4 · 공개)
 *
 * 카카오 REST 는 서버가 부른다 — 앱 키로 직접 부르지 않는다(AGENTS 2장-3).
 */
interface GeocodeApi {

    /**
     * 장소명·주소로 좌표 한 건. (§4-4)
     *
     * 결과가 없으면 `404 NO_RESULT` 라 [ApiException.Http] 로 올라온다.
     */
    @GET("geocode")
    suspend fun search(@Query("query") query: String): GeocodeDto
}
