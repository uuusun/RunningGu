package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.dto.FestivalListDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 홈 축제 섹션 계약. (API 명세 §4-1 · SPEC §4.4)
 *
 * 조회 월과 겹치는 전국 축제를 **진행 중 우선, 시작일 오름차순**으로 서버가 정렬해 준다.
 * 앱은 순서를 다시 만들지 않고, `inProgress` 도 다시 계산하지 않는다 — 두 벌 규칙이 생긴다.
 */
class FestivalMapperTest {

    /** 명세 §4-1 응답 항목 그대로. */
    private val raw = """
        {
          "items": [
            {
              "contentId": "2870000",
              "name": "여의도 봄꽃축제",
              "startDate": "2026-08-01",
              "endDate": "2026-08-10",
              "region": "서울",
              "imageUrl": "https://tong.visitkorea.or.kr/cms/a.jpg",
              "inProgress": true
            },
            {
              "contentId": "2870001",
              "name": "세종 호수축제",
              "startDate": "2026-08-20",
              "endDate": "2026-08-22",
              "region": "세종",
              "imageUrl": null,
              "inProgress": false
            }
          ]
        }
    """.trimIndent()

    private fun parse() = ApiJson.decodeFromString(FestivalListDto.serializer(), raw).toDomain()

    @Test
    fun `명세 예시를 화면 모델로 옮긴다`() {
        val festivals = parse()

        assertEquals(2, festivals.size)
        val first = festivals.first()
        assertEquals("2870000", first.contentId)
        assertEquals("여의도 봄꽃축제", first.name)
        assertEquals(LocalDate.of(2026, 8, 1), first.startDate)
        assertEquals(LocalDate.of(2026, 8, 10), first.endDate)
        assertEquals("서울", first.region)
    }

    @Test
    fun `서버가 정한 순서를 바꾸지 않는다`() {
        // 진행 중 우선 · 시작일 오름차순은 서버 몫이다 (§4-1)
        val names = parse().map { it.name }

        assertEquals(listOf("여의도 봄꽃축제", "세종 호수축제"), names)
    }

    @Test
    fun `진행 중 판정은 서버 값을 그대로 쓴다`() {
        // 앱이 오늘 날짜로 다시 계산하면 서버와 기준이 갈린다 — 배지 하나 때문에 두 벌 규칙이 생긴다
        val festivals = parse()

        assertTrue(festivals[0].inProgress)
        assertFalse(festivals[1].inProgress)
    }

    @Test
    fun `이미지가 없어도 항목을 살린다`() {
        // 이미지 없는 축제가 목록에서 사라지면 안 된다 (§6.2 placeholder 정책)
        val second = parse()[1]

        assertNull(second.imageUrl)
        assertEquals("세종 호수축제", second.name)
    }

    @Test
    fun `날짜가 깨져도 버리지 않는다`() {
        // 이름과 지역만으로도 카드는 그린다 — 한 건 때문에 섹션이 비면 안 된다
        val broken = """{"items":[{"contentId":"1","name":"n","startDate":"","endDate":"몰라"}]}"""

        val festival = ApiJson.decodeFromString(FestivalListDto.serializer(), broken).toDomain().single()

        assertNull(festival.startDate)
        assertNull(festival.endDate)
        assertEquals("n", festival.name)
    }

    @Test
    fun `빈 응답도 정상이다`() {
        val empty = ApiJson.decodeFromString(FestivalListDto.serializer(), """{}""").toDomain()

        assertTrue(empty.isEmpty())
    }
}
