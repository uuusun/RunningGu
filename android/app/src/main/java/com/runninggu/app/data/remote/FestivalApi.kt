package com.runninggu.app.data.remote

import com.runninggu.app.data.remote.dto.FestivalListDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 축제 API. (API 명세 §4-1 · 공개)
 *
 * 조회 월과 겹치는 전국 축제를 **진행 중 우선, 시작일 오름차순**으로 서버가 정렬해 준다 —
 * 앱은 순서를 다시 만들지 않는다.
 */
interface FestivalApi {

    /**
     * 홈 축제 섹션. (§4-1)
     *
     * @param yearMonth `2026-08`. 생략하면 서버가 이번 달로 본다.
     * @param size 기본 6 🔧.
     */
    @GET("festivals")
    suspend fun list(
        @Query("yearMonth") yearMonth: String? = null,
        @Query("size") size: Int? = null,
    ): FestivalListDto

    companion object {
        /** 홈 노출 건수 🔧(§4-1). */
        const val DEFAULT_SIZE = 6
    }
}
