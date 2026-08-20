package com.runninggu.app.data.repository

import com.runninggu.app.data.remote.FestivalApi
import com.runninggu.app.data.remote.dto.FestivalListDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.YearMonth

/** 요청 파라미터 계약. (API 명세 §4-1) */
class RemoteFestivalRepositoryTest {

    private class RecordingApi : FestivalApi {
        var yearMonth: String? = null
        var size: Int? = null

        override suspend fun list(yearMonth: String?, size: Int?): FestivalListDto {
            this.yearMonth = yearMonth
            this.size = size
            return FestivalListDto()
        }
    }

    @Test
    fun `조회 월은 2026-08 형식으로 나간다`() = runBlocking {
        val api = RecordingApi()

        RemoteFestivalRepository(api).list(YearMonth.of(2026, 8))

        assertEquals("2026-08", api.yearMonth)
        assertEquals(FestivalApi.DEFAULT_SIZE, api.size)
    }

    @Test
    fun `월을 안 주면 서버가 이번 달로 본다`() = runBlocking {
        // 앱이 기기 시계로 이번 달을 만들면 KST 기준과 어긋날 수 있다 — 서버에 맡긴다
        val api = RecordingApi()

        RemoteFestivalRepository(api).list()

        assertNull(api.yearMonth)
    }
}
