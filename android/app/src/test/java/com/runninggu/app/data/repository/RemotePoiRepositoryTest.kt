package com.runninggu.app.data.repository

import com.runninggu.app.data.remote.PoiApi
import com.runninggu.app.data.remote.dto.PoiSearchResponse
import com.runninggu.app.domain.PoiCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 요청 파라미터 계약. (API 명세 §4-2)
 *
 * 검색어가 짧으면 서버가 `400 VALIDATION_FAILED` 를 준다 — 앱이 먼저 거른다.
 */
class RemotePoiRepositoryTest {

    private class RecordingApi : PoiApi {
        var category: String? = null
        var query: String? = null
        var size: Int? = null

        override suspend fun search(
            category: String,
            lat: Double,
            lng: Double,
            query: String?,
            radius: Int?,
            size: Int?,
        ): PoiSearchResponse {
            this.category = category
            this.query = query
            this.size = size
            return PoiSearchResponse(source = "LIVE")
        }
    }

    @Test
    fun `카테고리는 서버 enum 이름으로 나간다`() = runBlocking {
        val api = RecordingApi()

        RemotePoiRepository(api).search(PoiCategory.LODGING, 36.49, 127.27)

        assertEquals("LODGING", api.category)
        assertEquals(PoiRepository.DEFAULT_SIZE, api.size)
    }

    @Test
    fun `두 글자 미만 검색어는 보내지 않는다`() = runBlocking {
        val api = RecordingApi()

        RemotePoiRepository(api).search(PoiCategory.LODGING, 36.49, 127.27, query = " 호 ")

        assertNull(api.query)
    }

    @Test
    fun `두 글자 이상이면 공백을 떼고 보낸다`() = runBlocking {
        val api = RecordingApi()

        RemotePoiRepository(api).search(PoiCategory.LODGING, 36.49, 127.27, query = " 세종 호텔 ")

        assertEquals("세종 호텔", api.query)
    }
}
