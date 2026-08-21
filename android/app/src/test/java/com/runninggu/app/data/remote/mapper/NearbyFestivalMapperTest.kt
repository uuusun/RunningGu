package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.dto.NearbyFestivalListDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 대회 인근 축제 계약. (API 명세 §3-5 · SPEC §4.6)
 *
 * 홈 축제(§4-1)와 **다른 엔드포인트**다 — 기준이 "대회일 ±14일 · 반경 40km · 거리순 6건"
 * 이고 `distanceKm` 이 있다. 모델을 합치면 한쪽에만 있는 필드를 다른 쪽이 null 로 든다.
 */
class NearbyFestivalMapperTest {

    /** 명세 §3-5 응답 예시 그대로. */
    private val raw = """
        {
          "items": [
            {
              "contentId": "2764321",
              "name": "세종 빛 축제",
              "startDate": "2026-08-20", "endDate": "2026-08-25",
              "distanceKm": 0.8,
              "imageUrl": "http://tong.visitkorea.or.kr/x.jpg",
              "address": "세종특별자치시 연기면"
            },
            {
              "contentId": "2764322",
              "name": "먼 축제",
              "startDate": "2026-08-21", "endDate": "2026-08-22",
              "distanceKm": 12.4,
              "imageUrl": null,
              "address": "세종특별자치시 조치원읍"
            }
          ]
        }
    """.trimIndent()

    private fun parse() =
        ApiJson.decodeFromString(NearbyFestivalListDto.serializer(), raw).toDomain()

    @Test
    fun `명세 예시를 화면 모델로 옮긴다`() {
        val first = parse().first()

        assertEquals("2764321", first.contentId)
        assertEquals("세종 빛 축제", first.name)
        assertEquals(LocalDate.of(2026, 8, 20), first.startDate)
        assertEquals(0.8, first.distanceKm, 1e-9)
        assertEquals("세종특별자치시 연기면", first.address)
    }

    @Test
    fun `거리순 정렬을 다시 하지 않는다`() {
        // 서버가 반경 필터와 거리순 정렬까지 끝내 준다 (§8.3) — 앱이 재정렬하면 두 벌이 된다
        val distances = parse().map { it.distanceKm }

        assertEquals(listOf(0.8, 12.4), distances)
    }

    @Test
    fun `이미지가 없어도 항목을 살린다`() {
        val second = parse()[1]

        assertNull(second.imageUrl)
        assertEquals("먼 축제", second.name)
    }

    @Test
    fun `빈 배열은 정상이다`() {
        // "대회 기간에 열리는 인근 축제가 없어요" 빈 상태다 — 실패(502)와 구분해야 한다
        val empty = ApiJson
            .decodeFromString(NearbyFestivalListDto.serializer(), """{"items":[]}""")
            .toDomain()

        assertTrue(empty.isEmpty())
    }

    @Test
    fun `날짜가 깨져도 버리지 않는다`() {
        val broken = """
            {"items":[{"contentId":"1","name":"n","startDate":"","endDate":"몰라",
             "distanceKm":1.0,"address":"주소"}]}
        """.trimIndent()

        val festival = ApiJson
            .decodeFromString(NearbyFestivalListDto.serializer(), broken)
            .toDomain()
            .single()

        assertNull(festival.startDate)
        assertEquals("n", festival.name)
    }
}
