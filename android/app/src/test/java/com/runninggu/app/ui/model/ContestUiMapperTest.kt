package com.runninggu.app.ui.model

import com.runninggu.app.data.model.Contest
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.RegistrationStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * 대회 → 화면 모델 변환. (API 명세 §3-1 · AP-14)
 *
 * 여기서 깨지면 카드 표기가 틀어진다 — 종목 순서·출처 표기·시작시각이 전부 이 변환을 지난다.
 */
class ContestUiMapperTest {

    private fun contest(
        startTime: LocalTime? = LocalTime.of(8, 0),
        eventTypes: List<EventType> = listOf(EventType.FULL, EventType.HALF, EventType.TEN_K),
        sources: List<String> = listOf("MARATHON_ONLINE", "MARATHON_GO"),
    ) = Contest(
        id = "153",
        serverId = 153L,
        name = "2026 세종 호수공원 마라톤",
        region = "세종",
        venue = "세종중앙공원",
        date = LocalDate.of(2026, 8, 22),
        startTime = startTime,
        eventTypes = eventTypes,
        regStart = LocalDate.of(2026, 4, 1),
        regEnd = LocalDate.of(2026, 8, 10),
        regStatusFallback = RegistrationStatus.CLOSED,
        organizer = null,
        officialUrl = null,
        detailUrl = null,
        imageUrl = "https://example.test/a.jpg",
        lat = 36.5,
        lng = 127.2,
        category = "로드",
        checked = LocalDate.of(2026, 7, 15),
        active = true,
        sources = sources,
    )

    @Test
    fun `서버 대회를 화면 모델로 옮긴다`() {
        val race = contest().toRaceSummary()

        assertEquals("153", race.id)
        assertEquals(153L, race.serverId)
        assertEquals("2026 세종 호수공원 마라톤", race.name)
        assertEquals("세종", race.region)
        assertEquals("세종중앙공원", race.venue)
        assertEquals(LocalDate.of(2026, 8, 22), race.date)
        assertEquals("08:00", race.startTime)
        assertEquals(LocalDate.of(2026, 4, 1), race.regStart)
        assertEquals(LocalDate.of(2026, 8, 10), race.regEnd)
        assertEquals(RegistrationStatus.CLOSED, race.regStatusFallback)
        assertEquals(LocalDate.of(2026, 7, 15), race.checked)
        assertEquals(true, race.active)
    }

    @Test
    fun `종목은 서버가 준 순서를 그대로 둔다`() {
        // 재정렬하면 SPEC §5.4 의 [풀, 하프, 10K, 5K] 가 깨진다
        val race = contest(
            eventTypes = listOf(EventType.FULL, EventType.HALF, EventType.TEN_K, EventType.FIVE_K),
        ).toRaceSummary()

        assertEquals(listOf("풀", "하프", "10K", "5K"), race.eventTypes)
    }

    @Test
    fun `원천 토큰을 카드 표기로 바꾸고 가운뎃점으로 잇는다`() {
        assertEquals("마라톤온라인·마라톤GO", contest().toRaceSummary().source)
    }

    @Test
    fun `모르는 원천 토큰은 버리지 않고 그대로 보여준다`() {
        // 원천이 늘었을 때 출처가 조용히 사라지는 것보다 낯선 값이 보이는 편이 낫다
        val race = contest(sources = listOf("MARATHON_GO", "NEW_SOURCE")).toRaceSummary()

        assertEquals("마라톤GO·NEW_SOURCE", race.source)
    }

    @Test
    fun `시작시각이 없으면 비운다`() {
        // 153건 중 6건이 비어 있다. "00:00" 으로 채우면 새벽 출발로 읽힌다
        assertEquals("", contest(startTime = null).toRaceSummary().startTime)
    }
}
