package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.dto.GenerateItineraryResponse
import com.runninggu.app.data.repository.GenerateItineraryRequest
import com.runninggu.app.data.repository.HotelInput
import com.runninggu.app.data.repository.toDto
import com.runninggu.app.domain.BlockCategory
import com.runninggu.app.domain.BlockType
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.PoiCategory
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 동선 생성 계약. (API 명세 §5-1 · SPEC 결정-41)
 *
 * 명세의 요청·응답 예시를 그대로 넣어 고정한다. **앱은 동선을 만들지 않으므로**
 * 이 매핑이 어긋나면 화면이 통째로 틀어진다.
 */
class ItineraryMapperTest {

    // ── 요청 ────────────────────────────────────────────────────

    @Test
    fun `요청은 명세 필드 그대로 나간다`() {
        val request = GenerateItineraryRequest(
            contestId = 153,
            startDate = LocalDate.of(2026, 8, 21),
            endDate = LocalDate.of(2026, 8, 23),
            event = EventType.HALF,
            themes = listOf(PoiCategory.TOUR, PoiCategory.FOOD),
            hotel = HotelInput("호텔 세종 가온", 36.4901, 127.2688),
        )

        val json = Json.encodeToString(
            com.runninggu.app.data.remote.dto.GenerateItineraryRequestDto.serializer(),
            request.toDto(),
        )

        // contestId 는 canonical 숫자다. 문자열로 나가면 서버가 못 읽는다 (#66 리뷰)
        assertTrue(json.contains("\"contestId\":153"))
        // 날짜는 KST 비즈니스 날짜 문자열이다 — timestamp 가 아니다 (AGENTS 2장-4)
        assertTrue(json.contains("\"startDate\":\"2026-08-21\""))
        assertTrue(json.contains("\"endDate\":\"2026-08-23\""))
        // enum 은 서버와 같은 대문자 이름. 한국어 라벨을 보내면 안 된다
        assertTrue(json.contains("\"event\":\"HALF\""))
        assertTrue(json.contains("\"themes\":[\"TOUR\",\"FOOD\"]"))
        assertTrue(json.contains("\"name\":\"호텔 세종 가온\""))
    }

    @Test
    fun `종목 네 가지가 모두 서버 표기로 나간다`() {
        // 부록 C 는 K5·K10 이다. enum 이름(FIVE_K·TEN_K)을 그대로 보내면 서버가 못 읽는다
        fun eventOf(type: EventType) = GenerateItineraryRequest(
            contestId = 1,
            startDate = LocalDate.of(2026, 8, 21),
            endDate = LocalDate.of(2026, 8, 21),
            event = type,
            themes = listOf(PoiCategory.TOUR),
        ).toDto().event

        assertEquals("FULL", eventOf(EventType.FULL))
        assertEquals("HALF", eventOf(EventType.HALF))
        assertEquals("K10", eventOf(EventType.TEN_K))
        assertEquals("K5", eventOf(EventType.FIVE_K))
    }

    @Test
    fun `숙소 없이도 요청할 수 있다`() {
        // "숙소 없이 추천받기" — 서버가 대회장 중심으로 슬롯을 채운다 (SPEC §4.9)
        val request = GenerateItineraryRequest(
            contestId = 153,
            startDate = LocalDate.of(2026, 8, 21),
            endDate = LocalDate.of(2026, 8, 21),
            event = EventType.FIVE_K,
            themes = listOf(PoiCategory.TOUR),
        )

        assertNull(request.toDto().hotel)
    }

    // ── 응답 ────────────────────────────────────────────────────

    /** 명세 §5-1 응답 예시에서 가져왔다. */
    private val responseJson = """
        {
          "title": "세종 2박 3일",
          "event": "HALF",
          "contestId": 153,
          "themes": ["TOUR", "FOOD"],
          "startDate": "2026-08-21", "endDate": "2026-08-23",
          "hotel": { "name": "호텔 세종 가온", "lat": 36.4901, "lng": 127.2688 },
          "recovery": { "label": "D+1 회복 모드", "note": "하프는 완주 다음날 회복이 중요해요" },
          "days": [
            {
              "dayIndex": 0, "date": "2026-08-21", "dayLabel": "D-1",
              "recovery": false, "note": "내일 완주 · 가볍게 먹고 푹 쉬기",
              "blocks": [
                { "startTime": "09:00", "title": "🏁 스타트", "category": "RACE",
                  "placeName": "대회장", "address": "세종", "lat": 36.49, "lng": 127.26,
                  "description": "완주", "blockType": "RACE", "systemManaged": true },
                { "startTime": "15:00", "title": "숙소 체크인", "category": "LODGING",
                  "placeName": "호텔 세종 가온", "address": "세종특별자치시 어진동 123",
                  "lat": 36.4901, "lng": 127.2688, "description": "짐 풀고 휴식",
                  "blockType": "USER", "systemManaged": false }
              ]
            },
            {
              "dayIndex": 1, "date": "2026-08-22", "dayLabel": "D+1",
              "recovery": true, "note": "회복",
              "blocks": [
                { "startTime": "11:00", "title": "온천", "category": "WELLNESS",
                  "description": "POI 조회 실패로 장소가 없다" }
              ]
            }
          ]
        }
    """.trimIndent()

    private fun parse() =
        ApiJson.decodeFromString(GenerateItineraryResponse.serializer(), responseJson).toResult()

    @Test
    fun `명세 예시를 화면 모델로 옮긴다`() {
        val result = parse()

        assertEquals("세종 2박 3일", result.title)
        assertEquals("D+1 회복 모드", result.recovery?.label)
        assertEquals(2, result.days.size)
        assertEquals(LocalDate.of(2026, 8, 21), result.days.first().date)
        // 일자별 회복 플래그는 서버 판정을 그대로 쓴다 (§5.6-6)
        assertEquals(listOf(false, true), result.recoveryFlags)
    }

    @Test
    fun `생성 조건을 버리지 않고 들고 있는다`() {
        // §5-2 저장 요청이 §5-1 응답 구조를 그대로 쓴다 — 버리면 저장·재생성을 못 만든다 (#66 리뷰)
        val snapshot = checkNotNull(parse().request)

        assertEquals(153L, snapshot.contestId)
        assertEquals(listOf("TOUR", "FOOD"), snapshot.themes)
        assertEquals("2026-08-21", snapshot.startDate)
        assertEquals("2026-08-23", snapshot.endDate)
        assertEquals("호텔 세종 가온", snapshot.hotel?.name)
        assertEquals("HALF", snapshot.event)
    }

    @Test
    fun `대회 블록은 서버 값이 아니라 종류로 잠근다`() {
        // systemManaged 가 응답과 어긋나면 잠금이 풀려 대회 블록이 편집된다 (§5.7 · 대조표 B4)
        val race = parse().days.first().blocks.first()

        assertEquals(BlockType.RACE, race.blockType)
        assertTrue(race.systemManaged)
    }

    @Test
    fun `POI 조회가 실패한 블록도 살려서 그린다`() {
        // 서버가 placeName·lat·lng 를 null 로 강등하되 생성은 성공시킨다 (NFR-3)
        val block = parse().days[1].blocks.single()

        assertNull(block.place)
        assertEquals("온천", block.title)
        assertEquals(BlockCategory.WELLNESS, block.catKey)
        assertFalse(block.systemManaged)
    }

    @Test
    fun `모르는 분류는 관광지로 떨어뜨린다`() {
        // 서버가 분류를 새로 추가해도 블록이 사라지면 안 된다 (부록 C)
        val raw = """
            {"title":"t","event":"HALF","days":[
              {"dayIndex":0,"date":"2026-08-21","dayLabel":"D-1","blocks":[
                {"startTime":"10:00","title":"새 분류","category":"MUSEUM_TOUR"}]}]}
        """.trimIndent()

        val block = ApiJson.decodeFromString(GenerateItineraryResponse.serializer(), raw)
            .toResult().days.single().blocks.single()

        assertEquals(BlockCategory.TOUR, block.catKey)
    }

    @Test
    fun `표시할 블록이 없는 정상 응답은 빈 목록이다`() {
        // 200 + days 빈 배열은 Empty 이지 Error 가 아니다 (§5-1)
        val raw = """{"title":"t","event":"HALF","days":[]}"""

        val result = ApiJson.decodeFromString(GenerateItineraryResponse.serializer(), raw).toResult()

        assertTrue(result.days.isEmpty())
        assertNull(result.recovery)
    }
}
