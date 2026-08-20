package com.runninggu.app.data.repository

import com.runninggu.app.data.model.Festival
import com.runninggu.app.data.remote.FestivalApi
import com.runninggu.app.data.remote.apiCall
import com.runninggu.app.data.remote.mapper.toDomain
import java.time.YearMonth

/**
 * 축제 조회 창구. (API 명세 §4-1 · SPEC §4.4)
 *
 * 홈은 마감임박과 축제를 **따로** 부르고 한쪽이 실패해도 다른 쪽을 보여준다(§3-5 부분 실패).
 * 그래서 이 창구는 실패를 삼키지 않고 그대로 던진다 — 영역별 상태는 화면이 정한다.
 */
interface FestivalRepository {

    /** @param yearMonth null 이면 서버가 이번 달로 본다. */
    suspend fun list(
        yearMonth: YearMonth? = null,
        size: Int = FestivalApi.DEFAULT_SIZE,
    ): List<Festival>
}

/** 서버 구현. */
class RemoteFestivalRepository(private val api: FestivalApi) : FestivalRepository {

    override suspend fun list(yearMonth: YearMonth?, size: Int): List<Festival> = apiCall {
        // "2026-08" — YearMonth.toString() 이 곧 계약 형식이다 (§4-1)
        api.list(yearMonth = yearMonth?.toString(), size = size).toDomain()
    }
}
