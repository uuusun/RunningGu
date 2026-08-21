package com.runninggu.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 목록 key 가 서버 유일성 보장과 같은 조합인가. (API 명세 §4-2 · #120 리뷰)
 *
 * 서버는 **정규화한 이름과 좌표**로 중복을 지운다. 앱 key 가 다른 조합이면 서버 중복 제거를
 * 통과한 항목 둘이 앱에서 같은 key 가 되고, `LazyColumn` 이 예외를 던진다.
 */
class PoiItemTest {

    private fun poi(name: String, address: String, lat: Double, lng: Double) = PoiItem(
        name = name,
        address = address,
        description = "",
        lat = lat,
        lng = lng,
    )

    @Test
    fun `이름과 주소가 같아도 좌표가 다르면 다른 key 다`() {
        // 서버 중복 제거는 좌표로 하므로 이 둘은 함께 내려올 수 있다.
        val a = poi("호텔 가온", "세종특별자치시 어진동", 36.4912, 127.2714)
        val b = poi("호텔 가온", "세종특별자치시 어진동", 36.4980, 127.2801)

        assertNotEquals(a.listKey, b.listKey)
    }

    @Test
    fun `주소가 비어 있어도 좌표가 다르면 갈린다`() {
        // 원천에 주소가 없으면 빈 문자열이다 — 이름만으로는 겹친다.
        val a = poi("전망대", "", 36.4912, 127.2714)
        val b = poi("전망대", "", 36.5000, 127.3000)

        assertNotEquals(a.listKey, b.listKey)
    }

    @Test
    fun `이름과 좌표가 같으면 같은 key 다`() {
        // 서버가 지우는 조합이라 실제로는 함께 오지 않는다. 규칙이 뒤집히지 않았는지만 고정한다.
        val a = poi("호텔 가온", "세종특별자치시 어진동 123", 36.4912, 127.2714)
        val b = poi("호텔 가온", "", 36.4912, 127.2714)

        assertEquals(a.listKey, b.listKey)
    }
}
