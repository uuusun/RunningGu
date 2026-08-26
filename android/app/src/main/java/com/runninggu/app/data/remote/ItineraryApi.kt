package com.runninggu.app.data.remote

import com.runninggu.app.data.remote.dto.GenerateItineraryRequestDto
import com.runninggu.app.data.remote.dto.GenerateItineraryResponse
import com.runninggu.app.data.remote.dto.ItineraryDetailDto
import com.runninggu.app.data.remote.dto.ItinerarySummaryDto
import com.runninggu.app.data.remote.dto.PageDto
import com.runninggu.app.data.remote.dto.SaveItineraryRequestDto
import com.runninggu.app.data.remote.dto.SaveItineraryResponseDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 동선 API. (API 명세 §5 · 생성은 공개)
 *
 * **생성은 서버 단일 주체다**(SPEC 결정-41). 앱은 카테고리별 POI 를 모아 자체 엔진으로
 * 동선을 조립하지 않는다 — 이 응답을 표시하고 저장 전 USER 블록만 편집한다.
 */
interface ItineraryApi {

    /**
     * 동선 생성. **무상태 · 게스트 허용**. (§5-1)
     *
     * 정상인데 표시할 블록이 없으면 `200` 에 `days: []` 가 온다 — 화면은 Empty 다.
     * 네트워크·4xx·5xx 는 Error 이고 Empty 로 강등하지 않는다.
     */
    @POST("itineraries/generate")
    suspend fun generate(@Body body: GenerateItineraryRequestDto): GenerateItineraryResponse

    /**
     * 동선 저장. **인증 필요**. (§5-2 🔒)
     *
     * 요청은 [generate] 응답 구조 그대로에 클라이언트 편집을 반영한 것이다.
     * 같은 `(user, contestId, startDate, endDate)` 가 이미 있으면 새로 만들지 않고
     * **교체**하며 `replaced=true` 로 알린다 — trip id 가 `{대회id}-{시작일}-{종료일}` 인
     * SPEC §4.10 의 "동일 id 교체" 계약을 잇는다.
     */
    @POST("itineraries")
    suspend fun save(@Body body: SaveItineraryRequestDto): SaveItineraryResponseDto

    /**
     * 저장 동선 상세. **인증·소유자**. (§5-5)
     *
     * S7 복원·편집 모드 진입용이다. 트리는 저장 시점 snapshot 이고, 최신 대회는 `contest`
     * 로 따로 온다 — 서버가 둘을 섞지 않으므로 앱도 섞지 않는다.
     */
    @GET("itineraries/{id}")
    suspend fun detail(@Path("id") id: Long): ItineraryDetailDto

    /** 내 동선 목록. Pageable 이다 (§5-4 · §0-4). */
    @GET("itineraries")
    suspend fun list(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): PageDto<ItinerarySummaryDto>

    /** 저장 동선 삭제. `204` 다 (§5-6). */
    @DELETE("itineraries/{id}")
    suspend fun delete(@Path("id") id: Long)
}
