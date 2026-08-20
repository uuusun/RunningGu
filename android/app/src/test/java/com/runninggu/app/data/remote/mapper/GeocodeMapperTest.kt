package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.dto.GeocodeDto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 출발지 검색 계약. (API 명세 §4-4)
 *
 * 명세의 응답 예시를 그대로 넣어 고정한다. 결과가 **하나뿐**이라는 게 이 계약의 핵심이다 —
 * 목록이 아니라서 화면이 후보를 고르게 하지 않는다(SPEC §4.11-1 ②).
 */
class GeocodeMapperTest {

    @Test
    fun `명세 예시를 그대로 읽는다`() {
        val raw = """
            {"name":"해운대해수욕장","address":"부산 해운대구 ...","lat":35.1587,"lng":129.1604}
        """.trimIndent()

        val place = ApiJson.decodeFromString(GeocodeDto.serializer(), raw).toDomain()

        assertEquals("해운대해수욕장", place.name)
        assertEquals("부산 해운대구 ...", place.address)
        assertEquals(35.1587, place.lat, 1e-7)
        assertEquals(129.1604, place.lng, 1e-7)
    }

    @Test
    fun `이름이 비면 주소를 이름으로 쓴다`() {
        // 주소만 준 결과도 출발지 칩에 뭔가는 적어야 한다
        val raw = """{"address":"서울 중구 세종대로 110","lat":37.5665,"lng":126.978}"""

        val place = ApiJson.decodeFromString(GeocodeDto.serializer(), raw).toDomain()

        assertEquals("서울 중구 세종대로 110", place.name)
    }

    @Test
    fun `결과 없음 코드를 알아본다`() {
        // NO_RESULT 는 _NOT_FOUND 접미사 규칙에 안 걸려 따로 등록해야 한다 (§4-4)
        assertEquals(ApiErrorCode.NO_RESULT, ApiErrorCode.from("NO_RESULT"))
    }
}
