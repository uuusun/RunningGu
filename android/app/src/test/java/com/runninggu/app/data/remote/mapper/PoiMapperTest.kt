package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.dto.PoiSearchResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 장소 조회 계약. (API 명세 §4-2)
 *
 * 명세의 응답 예시를 그대로 고정한다. `source` 배지는 NFR-2 가 요구하는 값이라
 * 임의로 바꾸거나 감추지 않는다 — 운영에서 실패를 샘플로 숨기지 않기 위해서다.
 */
class PoiMapperTest {

    /** 명세 §4-2 응답 예시 그대로. */
    private val raw = """
        {
          "source": "LIVE",
          "items": [
            {
              "name": "호텔 세종 가온", "category": "LODGING",
              "lat": 36.4912, "lng": 127.2714, "distanceM": 1200,
              "description": "어진동 · 대회장 1.2km",
              "address": "세종특별자치시 어진동 123",
              "url": "http://place.map.kakao.com/...",
              "imageUrl": null
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `명세 예시를 화면 모델로 옮긴다`() {
        val result = ApiJson.decodeFromString(PoiSearchResponse.serializer(), raw).toResult()

        assertEquals("LIVE", result.source)
        val item = result.items.single()
        assertEquals("호텔 세종 가온", item.name)
        assertEquals("세종특별자치시 어진동 123", item.address)
        assertEquals("어진동 · 대회장 1.2km", item.description)
        assertEquals(36.4912, item.lat, 1e-7)
    }

    @Test
    fun `imageUrl 이 null 이어도 항목을 살린다`() {
        // 이미지가 없는 장소가 목록에서 사라지면 안 된다 (§6.2 placeholder 정책)
        val result = ApiJson.decodeFromString(PoiSearchResponse.serializer(), raw).toResult()

        assertEquals(1, result.items.size)
    }

    @Test
    fun `빈 결과도 정상 응답이다`() {
        // 0건은 Empty 이지 오류가 아니다
        val result = ApiJson
            .decodeFromString(PoiSearchResponse.serializer(), """{"source":"LIVE"}""")
            .toResult()

        assertEquals("LIVE", result.source)
        assertTrue(result.items.isEmpty())
    }
}
