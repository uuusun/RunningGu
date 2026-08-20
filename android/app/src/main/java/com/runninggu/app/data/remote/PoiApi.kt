package com.runninggu.app.data.remote

import com.runninggu.app.data.remote.dto.PoiSearchResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 장소 API. (API 명세 §4-2 · 공개)
 *
 * **앱은 카카오·KTO 를 직접 부르지 않는다** — REST 키가 서버에만 있다(AGENTS 2장-3).
 * 반경 확대(3건 미만이면 20km 재검색)와 원천 폴백은 서버가 한다(§8.1).
 */
interface PoiApi {

    /**
     * 카테고리·기준점 주변 장소. (§4-2)
     *
     * @param query 키워드 검색. 공백 제거 후 2자 이상이어야 하며 미만이면
     *  서버가 `400 VALIDATION_FAILED` 를 준다. null 이면 주변 추천이다.
     * @param radius 기본 8000m, 최대 20000(KTO 제약).
     * @param size 노출 8건 🔒.
     */
    @GET("pois")
    suspend fun search(
        @Query("category") category: String,
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("query") query: String? = null,
        @Query("radius") radius: Int? = null,
        @Query("size") size: Int? = null,
    ): PoiSearchResponse

    companion object {
        /** 기본 조회 반경(m) 🔧(§4-2). */
        const val DEFAULT_RADIUS_M = 8000

        /** KTO 제약 상한(m) 🔒(§4-2). */
        const val MAX_RADIUS_M = 20000
    }
}
