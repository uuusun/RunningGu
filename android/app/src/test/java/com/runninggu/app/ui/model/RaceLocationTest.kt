package com.runninggu.app.ui.model

import com.runninggu.app.data.model.Contest
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.RegistrationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * 대회장 좌표가 화면 모델까지 살아 오는가. (SPEC §4.6 · §4.9 · #136 리뷰)
 *
 * ## 망가뜨리면 이것만 실패한다
 *
 * `ContestUiMapper` 의 `lat = lat, lng = lng` 두 줄을 지우면 아래 두 건이 실패한다.
 * 실제로 그 두 줄이 없어서 S6 가 대회장 대신 `(0.0, 0.0)` 을 조회했다 — **기니만
 * 앞바다다.** 앱이 좌표를 안 쓰던 시절의 흔적이라 컴파일도 테스트도 안 깨졌고,
 * fake 저장소가 좌표를 무시해서 화면에서도 안 보였다.
 */
class RaceLocationTest {

    private fun contest(lat: Double? = 36.5, lng: Double? = 127.2) = Contest(
        id = "153",
        serverId = 153L,
        name = "2026 세종 호수공원 마라톤",
        region = "세종",
        venue = "세종중앙공원",
        date = LocalDate.of(2026, 8, 22),
        startTime = LocalTime.of(8, 0),
        eventTypes = listOf(EventType.HALF),
        regStart = LocalDate.of(2026, 4, 1),
        regEnd = LocalDate.of(2026, 8, 10),
        regStatusFallback = RegistrationStatus.CLOSED,
        organizer = null,
        officialUrl = null,
        detailUrl = null,
        imageUrl = null,
        lat = lat,
        lng = lng,
        category = "로드",
        checked = LocalDate.of(2026, 7, 15),
        active = true,
        sources = listOf("MARATHON_GO"),
    )

    @Test
    fun `대회장 좌표를 화면 모델로 옮긴다`() {
        val race = contest().toRaceSummary()

        assertEquals(36.5, race.lat!!, 1e-9)
        assertEquals(127.2, race.lng!!, 1e-9)
    }

    @Test
    fun `좌표가 없는 대회는 null 로 남긴다`() {
        val race = contest(lat = null, lng = null).toRaceSummary()

        // 0.0 으로 지어내지 않는다. 0,0 은 기니만 앞바다라 조용히 엉뚱한 곳을 조회한다
        assertNull(race.lat)
        assertNull(race.lng)
    }

    @Test
    fun `좌표가 둘 다 있어야 위치가 있는 것으로 본다`() {
        assertTrue(contest().toRaceSummary().hasLocation)
        assertFalse(contest(lat = null).toRaceSummary().hasLocation)
        assertFalse(contest(lng = null).toRaceSummary().hasLocation)
    }
}
