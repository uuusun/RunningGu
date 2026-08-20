package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.dto.ClosingSoonDto
import com.runninggu.app.data.remote.dto.ContestDto
import com.runninggu.app.data.remote.dto.ContestListDto
import com.runninggu.app.data.remote.dto.DailyCountsDto
import com.runninggu.app.data.remote.mapper.toServerName
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.RegistrationStatus
import com.runninggu.app.domain.regStatusOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * 대회 API 계약. (API 명세 §3)
 *
 * 백엔드 구현이 아직 없으므로 **명세에 실린 예시 JSON 을 그대로** 넣어 고정한다.
 * 계약이 바뀌면 이 테스트가 먼저 깨져야 한다.
 */
class ContestMapperTest {

    /** 명세 §3-1 의 응답 예시 (PR #46 반영본 — applyStart 포함). */
    private val listJson = """
        {
          "items": [
            {
              "id": 153,
              "name": "2026 세종 호수공원 마라톤",
              "region": "세종", "place": "세종중앙공원",
              "contestDate": "2026-08-22", "startTime": "08:00",
              "events": ["FULL", "HALF", "K10"],
              "regStatus": "CLOSED",
              "applyStart": "2026-04-01", "applyEnd": "2026-08-10",
              "imageUrl": "https://example.test/a.jpg",
              "sources": ["MARATHON_ONLINE", "MARATHON_GO"],
              "checkedAt": "2026-07-15T04:30:00Z",
              "favorite": false
            }
          ],
          "nextCursor": "MjAyNi0wOC0yMnwxNTM",
          "hasNext": true
        }
    """.trimIndent()

    @Test
    fun `목록 응답을 계약대로 읽는다`() {
        val dto = ApiJson.decodeFromString(ContestListDto.serializer(), listJson)
        val c = dto.items.single().toContest()

        assertEquals("153", c.id)
        assertEquals("2026 세종 호수공원 마라톤", c.name)
        assertEquals("세종중앙공원", c.venue) // 서버 place → 앱 venue
        assertEquals(LocalDate.of(2026, 8, 22), c.date)
        assertEquals(LocalTime.of(8, 0), c.startTime)
        assertEquals(listOf(EventType.FULL, EventType.HALF, EventType.TEN_K), c.eventTypes)
        assertEquals(LocalDate.of(2026, 4, 1), c.regStart)
        assertEquals(LocalDate.of(2026, 8, 10), c.regEnd)
        assertEquals(RegistrationStatus.CLOSED, c.regStatusFallback)
        assertEquals(listOf("MARATHON_ONLINE", "MARATHON_GO"), c.sources)
        assertTrue(c.hasLocation.not()) // 목록에는 좌표가 없다
        // 커서는 불투명 문자열 — 그대로 들고 다닌다 (§0-4)
        assertEquals("MjAyNi0wOC0yMnwxNTM", dto.nextCursor)
        assertTrue(dto.hasNext)
    }

    @Test
    fun `서버 id 는 canonical 로 보존된다`() {
        // 화면 키는 문자열이지만 서버 호출용 숫자 id 를 잃으면 안 된다 (#52 리뷰)
        val contest = ApiJson.decodeFromString(
            ContestListDto.serializer(),
            """{"items":[{"id":153,"name":"n","region":"서울","venue":"v",
               "contestDate":"2026-09-04"}],"nextCursor":null}""",
        ).items.single().toContest()

        assertEquals(153L, contest.serverId)
        assertEquals("153", contest.id)
        assertTrue(contest.isServerBacked)
    }

    @Test
    fun `비활성 대회 상세는 404 가 아니라 active false 로 온다`() {
        // 찜·저장 동선에서 진입한 상세는 삭제하지 않고 돌려준다 (결정-46)
        val contest = ApiJson.decodeFromString(
            ContestDto.serializer(),
            """{"id":153,"name":"n","region":"서울","venue":"v",
               "contestDate":"2026-09-04","active":false}""",
        ).toContest()

        assertEquals(false, contest.active)
        assertEquals(153L, contest.serverId)
    }

    @Test
    fun `active 를 안 주면 서비스 중으로 본다`() {
        val contest = ApiJson.decodeFromString(
            ContestDto.serializer(),
            """{"id":153,"name":"n","region":"서울","venue":"v","contestDate":"2026-09-04"}""",
        ).toContest()

        assertEquals(true, contest.active)
    }

    @Test
    fun `종목 네 가지가 모두 서버 표기로 나간다`() {
        // 부록 C — K5·K10 이다. enum 이름(FIVE_K·TEN_K)을 그대로 보내면 서버가 못 읽는다
        assertEquals("FULL", EventType.FULL.toServerName())
        assertEquals("HALF", EventType.HALF.toServerName())
        assertEquals("K10", EventType.TEN_K.toServerName())
        assertEquals("K5", EventType.FIVE_K.toServerName())
    }

    @Test
    fun `checkedAt 은 KST 날짜로 옮긴다`() {
        val c = ApiJson.decodeFromString(ContestListDto.serializer(), listJson).items.single().toContest()
        // 2026-07-15T04:30Z = KST 13:30 같은 날. UTC 로 자르면 하루가 어긋나는 자리다
        assertEquals(LocalDate.of(2026, 7, 15), c.checked)
    }

    @Test
    fun `UTC 자정 직전 timestamp 도 KST 로 하루 넘긴다`() {
        val raw = """
            {"items":[{"id":1,"name":"n","contestDate":"2026-08-22",
                       "checkedAt":"2026-07-15T23:30:00Z"}]}
        """.trimIndent()
        val c = ApiJson.decodeFromString(ContestListDto.serializer(), raw).items.single().toContest()
        // KST 로는 다음 날 08:30 이다
        assertEquals(LocalDate.of(2026, 7, 16), c.checked)
    }

    @Test
    fun `서버 종목 enum 을 도메인으로 옮긴다`() {
        val raw = """
            {"items":[{"id":1,"name":"n","contestDate":"2026-08-22",
                       "events":["K5","K10","HALF","FULL"]}]}
        """.trimIndent()
        val c = ApiJson.decodeFromString(ContestListDto.serializer(), raw).items.single().toContest()

        assertEquals(
            listOf(EventType.FIVE_K, EventType.TEN_K, EventType.HALF, EventType.FULL),
            c.eventTypes,
        )
    }

    @Test
    fun `모르는 종목은 그것만 빼고 나머지를 살린다`() {
        // 서버가 종목을 추가해도 목록이 통째로 비면 안 된다
        val raw = """
            {"items":[{"id":1,"name":"n","contestDate":"2026-08-22",
                       "events":["FULL","ULTRA"]}]}
        """.trimIndent()
        val c = ApiJson.decodeFromString(ContestListDto.serializer(), raw).items.single().toContest()

        assertEquals(listOf(EventType.FULL), c.eventTypes)
    }

    @Test
    fun `종목이 비면 빈 목록이다`() {
        // 화면이 "종목 미표기" 배지를 붙이는 신호다 (§3-1)
        val raw = """{"items":[{"id":1,"name":"n","contestDate":"2026-08-22","events":[]}]}"""
        val c = ApiJson.decodeFromString(ContestListDto.serializer(), raw).items.single().toContest()

        assertTrue(c.eventTypes.isEmpty())
    }

    @Test
    fun `모르는 접수 상태는 null 이라 날짜로 판정한다`() {
        val raw = """
            {"items":[{"id":1,"name":"n","contestDate":"2026-08-22",
                       "regStatus":"PENDING","applyStart":"2026-05-01","applyEnd":"2026-08-10"}]}
        """.trimIndent()
        val c = ApiJson.decodeFromString(ContestListDto.serializer(), raw).items.single().toContest()

        assertNull(c.regStatusFallback)
        assertEquals(
            RegistrationStatus.OPEN,
            regStatusOf(c.regStart, c.regEnd, c.regStatusFallback, LocalDate.of(2026, 6, 1)),
        )
    }

    @Test
    fun `applyStart 가 없으면 서버 값을 그대로 믿는다`() {
        // 이슈 #44 · SPEC §5.5 — 마감일만 미래인 경우는 단정하지 않는다
        val raw = """
            {"items":[{"id":1,"name":"n","contestDate":"2026-08-22",
                       "regStatus":"BEFORE","applyEnd":"2026-08-10"}]}
        """.trimIndent()
        val c = ApiJson.decodeFromString(ContestListDto.serializer(), raw).items.single().toContest()

        assertNull(c.regStart)
        assertEquals(
            RegistrationStatus.BEFORE,
            regStatusOf(c.regStart, c.regEnd, c.regStatusFallback, LocalDate.of(2026, 6, 1)),
        )
    }

    @Test
    fun `상세는 좌표와 주최를 더 준다`() {
        val raw = """
            {"items":[{"id":153,"name":"n","contestDate":"2026-08-22",
                       "organizer":"세종시","officialUrl":"https://example.test",
                       "lat":36.48,"lng":127.28,"dDay":11}]}
        """.trimIndent()
        val c = ApiJson.decodeFromString(ContestListDto.serializer(), raw).items.single().toContest()

        assertEquals("세종시", c.organizer)
        assertTrue(c.hasLocation)
        assertEquals(36.48, c.lat!!, 1e-9)
    }

    @Test
    fun `마감 임박은 남은 일수를 함께 준다`() {
        val raw = """
            {"items":[{"id":1,"name":"n","contestDate":"2026-08-22",
                       "applyEnd":"2026-08-10","dDayApply":11}]}
        """.trimIndent()
        val dto = ApiJson.decodeFromString(ClosingSoonDto.serializer(), raw)

        assertEquals(11, dto.items.single().dDayApply)
    }

    @Test
    fun `월간 점 집계를 읽는다`() {
        val dto = ApiJson.decodeFromString(
            DailyCountsDto.serializer(),
            """{"counts":[{"date":"2026-08-22","count":2}]}""",
        )

        assertEquals(LocalDate.of(2026, 8, 22), dto.counts.single().date)
        assertEquals(2, dto.counts.single().count)
    }
}
