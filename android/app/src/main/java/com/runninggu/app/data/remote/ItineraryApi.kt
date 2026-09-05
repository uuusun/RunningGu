package com.runninggu.app.data.remote

import com.runninggu.app.data.remote.dto.BlockCreateRequestDto
import com.runninggu.app.data.remote.dto.BlockCreatedDto
import com.runninggu.app.data.remote.dto.BlockOrderRequestDto
import com.runninggu.app.data.remote.dto.BlockPatchRequestDto
import com.runninggu.app.data.remote.dto.BlockDto
import com.runninggu.app.data.remote.dto.DayBlocksDto
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
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
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

    // ── 저장 후 편집 (§5-7 ~ §5-10) ──────────────────────────────
    //
    // **저장 전 편집과 다른 계약이다.** 저장 전(S7 생성 직후)은 앱이 로컬 트리를 고치고
    // 저장 때 통째로 보낸다. 저장 후(S7-R 복원)는 블록 하나하나가 서버 왕복이다.
    //
    // `POST /itineraries` 로 다시 저장하는 방법을 쓰지 않는 이유가 있다 — 그 API 는
    // 저장 시점 canonical 대회로 **RACE 블록을 재구성**하므로, USER 장소 하나만 고쳐도
    // 대회 정보가 말없이 바뀔 수 있다(#213 · 선경님 리뷰 · SPEC 결정-45).
    //
    // RACE 블록을 건드리는 요청은 전부 `409 SYSTEM_BLOCK_IMMUTABLE` 이다.

    /**
     * 블록 추가. 해당 일자 **맨 끝**에 붙는다. `201` (§5-7)
     *
     * 위치를 고르는 계약이 아니다 — 중간에 넣으려면 추가한 뒤 [reorderBlocks] 로 옮긴다.
     */
    @POST("itineraries/{id}/days/{dayId}/blocks")
    suspend fun addBlock(
        @Path("id") itineraryId: Long,
        @Path("dayId") dayId: Long,
        @Body body: BlockCreateRequestDto,
    ): BlockCreatedDto

    /**
     * USER 블록 수정. **보낸 필드만 반영된다.** 갱신된 block 전체가 온다 (§5-8)
     *
     * 응답 필드는 §5-5 `blocks[]` 와 같으므로 같은 매퍼를 쓴다.
     */
    @PATCH("itineraries/{id}/days/{dayId}/blocks/{blockId}")
    suspend fun updateBlock(
        @Path("id") itineraryId: Long,
        @Path("dayId") dayId: Long,
        @Path("blockId") blockId: Long,
        @Body body: BlockPatchRequestDto,
    ): BlockDto

    /** USER 블록 삭제. `204` (§5-9) */
    @DELETE("itineraries/{id}/days/{dayId}/blocks/{blockId}")
    suspend fun deleteBlock(
        @Path("id") itineraryId: Long,
        @Path("dayId") dayId: Long,
        @Path("blockId") blockId: Long,
    )

    /**
     * USER 블록 순서 변경. 그 일자의 **전체** 블록이 정렬된 채 온다 (§5-10)
     *
     * 보내는 것은 USER 블록 전체 집합이고, 받는 것은 RACE 를 포함한 전체다. 서버가
     * RACE 를 제자리에 끼워 돌려주므로 앱이 다시 합칠 필요가 없다.
     */
    @PUT("itineraries/{id}/days/{dayId}/blocks/order")
    suspend fun reorderBlocks(
        @Path("id") itineraryId: Long,
        @Path("dayId") dayId: Long,
        @Body body: BlockOrderRequestDto,
    ): DayBlocksDto
}
