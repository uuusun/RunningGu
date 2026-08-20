package com.runninggu.app.data.remote

import com.runninggu.app.data.remote.dto.PageDto
import com.runninggu.app.data.remote.dto.SaveCourseRequestDto
import com.runninggu.app.data.remote.dto.SaveCourseResponseDto
import com.runninggu.app.data.remote.dto.SavedCourseDetailDto
import com.runninggu.app.data.remote.dto.SavedCourseDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 저장 코스 API. (API 명세 §7-A · **인증 필요**)
 *
 * 게스트가 부르면 `401` 이라 [ApiException.Http.needsLogin] 이 뜬다 — 화면은 로그인 유도를
 * 띄우고 저장을 **예약하지 않는다**(D-27).
 */
interface SavedCourseApi {

    /** 코스 저장. 신규 `201`, 같은 경로면 `200` 에 기존 id. (§7-A) */
    @POST("me/courses")
    suspend fun save(@Body body: SaveCourseRequestDto): SaveCourseResponseDto

    /** 목록(Pageable). `pathPolyline` 은 안 온다. (§0-4 · §7-A) */
    @GET("me/courses")
    suspend fun list(
        @Query("page") page: Int? = null,
        @Query("size") size: Int? = null,
    ): PageDto<SavedCourseDto>

    /** 상세 — 경로·고도·출처 포함. */
    @GET("me/courses/{id}")
    suspend fun detail(@Path("id") id: Long): SavedCourseDetailDto

    /** 삭제 `204`. */
    @DELETE("me/courses/{id}")
    suspend fun delete(@Path("id") id: Long)
}
