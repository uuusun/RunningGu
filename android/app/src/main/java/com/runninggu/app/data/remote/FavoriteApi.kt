package com.runninggu.app.data.remote

import com.runninggu.app.data.remote.dto.ContestDto
import com.runninggu.app.data.remote.dto.PageDto
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 찜. (API 명세 §7-C 🔒결정-16 · 인증 필요)
 *
 * **항목이 공개 목록(§3-1)과 같은 대회 카드**라 [ContestDto] 를 그대로 쓴다. 다만 페이징은
 * 다르다 — 공개 목록은 커서, 내 것만 보는 목록은 Spring Pageable 이다(§0-4).
 *
 * 공개 목록과 또 하나 다른 점은 **비활성 대회가 빠지지 않는다**는 것이다. `active=false` 로
 * 그대로 오고, 흐림 처리는 화면이 한다(§7-C 🔒 · 결정-46).
 */
interface FavoriteApi {

    /** 찜한 대회 목록. `createdAt DESC` 는 서버가 정한다. */
    @GET("me/favorites")
    suspend fun list(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): PageDto<ContestDto>

    /** 찜 — **멱등** `204`. 이미 찜이어도 성공한다. */
    @PUT("me/favorites/{contestId}")
    suspend fun add(@Path("contestId") contestId: Long)

    /** 해제 — 멱등 `204`. 찜하지 않은 대회여도 성공한다. */
    @DELETE("me/favorites/{contestId}")
    suspend fun remove(@Path("contestId") contestId: Long)
}
