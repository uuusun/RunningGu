package com.runninggu.app.data.remote

import com.runninggu.app.data.remote.dto.ClosingSoonDto
import com.runninggu.app.data.remote.dto.ContestDto
import com.runninggu.app.data.remote.dto.ContestListDto
import com.runninggu.app.data.remote.dto.DailyCountsDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 대회 API. (API 명세 §3 · 공개 — 게스트 허용 §0-2)
 *
 * 목록은 `contest_date >= 오늘(KST)` 고정이고 정렬은 `(contestDate, id) ASC` 다 — 서버가 한다.
 */
interface ContestApi {

    /**
     * 목록. (§3-1)
     *
     * @param events `FULL|HALF|K10|K5` — 복수 선택은 **OR**, 나머지 필터와는 AND (결정-12)
     * @param cursor 이전 응답의 `nextCursor` **그대로**. 앱이 만들거나 해석하지 않는다(§0-4)
     * @param size 기본 20 · 최대 50
     */
    @GET("contests")
    suspend fun list(
        @Query("q") query: String? = null,
        @Query("events") events: List<String>? = null,
        @Query("openOnly") openOnly: Boolean? = null,
        @Query("regions") regions: List<String>? = null,
        @Query("date") date: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("size") size: Int? = null,
    ): ContestListDto

    /**
     * 월간 뷰 점 집계. (§3-2)
     *
     * 목록과 **같은 필터**를 넘겨야 점과 목록이 어긋나지 않는다.
     */
    @GET("contests/daily-counts")
    suspend fun dailyCounts(
        @Query("year") year: Int,
        @Query("month") month: Int,
        @Query("q") query: String? = null,
        @Query("events") events: List<String>? = null,
        @Query("openOnly") openOnly: Boolean? = null,
        @Query("regions") regions: List<String>? = null,
    ): DailyCountsDto

    /** 홈 마감 임박. 기본 4건. (§3-3 · SPEC §4.4-3) */
    @GET("contests/closing-soon")
    suspend fun closingSoon(@Query("limit") limit: Int = DEFAULT_CLOSING_SOON_LIMIT): ClosingSoonDto

    /** 상세. 없으면 `404 CONTEST_NOT_FOUND`. (§3-4) */
    @GET("contests/{id}")
    suspend fun detail(@Path("id") id: Long): ContestDto

    companion object {
        /** 홈 마감임박 기본 노출 수 🔒(§3-3). */
        const val DEFAULT_CLOSING_SOON_LIMIT = 4

        /** 목록 기본·최대 페이지 크기 (§0-4). */
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 50
    }
}
