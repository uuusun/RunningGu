package com.runninggu.app.ui.model

import com.runninggu.app.ui.sample.SampleData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 지난·비활성 대회 흐림 판정. (SPEC §4.13 · API 명세 §7-C 🔒 · 결정-46)
 *
 * 판정을 서버가 아니라 **클라가** 한다는 것이 계약이라, 규칙이 여기 있다는 것 자체를
 * 테스트로 고정한다.
 */
class RaceSummaryDimmingTest {

    private val today = LocalDate.of(2026, 8, 20)

    private fun race(
        id: String = "r1",
        daysFromToday: Long = 10,
        active: Boolean = true,
        serverId: Long? = 1L,
    ) = RaceSummary(
        id = id,
        serverId = serverId,
        active = active,
        name = "테스트 대회",
        region = "서울",
        venue = "여의도한강공원",
        date = today.plusDays(daysFromToday),
        startTime = "09:00",
        regStart = today.minusDays(30),
        regEnd = today.plusDays(3),
        eventTypes = listOf("10K"),
        source = "마라톤온라인",
        checked = today.minusDays(1),
    )

    @Test
    fun `오늘 이후의 활성 대회는 흐리지 않다`() {
        assertFalse(race(daysFromToday = 10).isDimmed(today))
    }

    @Test
    fun `대회 당일은 아직 흐리지 않다`() {
        // 오늘 열리는 대회는 지난 대회가 아니다 — isBefore 라 경계가 여기서 갈린다
        assertFalse(race(daysFromToday = 0).isDimmed(today))
    }

    @Test
    fun `지난 대회는 흐리다`() {
        assertTrue(race(daysFromToday = -1).isDimmed(today))
    }

    @Test
    fun `비활성 대회는 날짜가 남아 있어도 흐리다`() {
        // 원천에서 사라진 대회다. 날짜가 미래여도 신청할 수 있다고 보이면 안 된다
        assertTrue(race(daysFromToday = 30, active = false).isDimmed(today))
    }

    @Test
    fun `공개 목록에는 비활성 대회도 지난 대회도 없다`() {
        // API 명세 §3-1 이 `active=true AND contest_date >= 오늘` 고정 🔒 이다.
        // 스텁이 이 규칙을 깨면 화면이 실제 서버와 다른 것을 보게 된다.
        val today = com.runninggu.app.domain.today()
        assertTrue(SampleData.races.all { it.active })
        assertTrue(SampleData.races.none { it.date.isBefore(today) })
    }

    @Test
    fun `샘플 대회에는 가짜 canonical id 를 넣지 않는다`() {
        // 데모용 id 는 스텁 저장소 안에만 둔다 — 모델에 넣으면 실서버로 나갈 수 있다
        // (#66 리뷰). GenerateBlockedTest 도 이 전제 위에 서 있다.
        assertTrue(SampleData.allRaces.all { it.serverId == null })
    }

    @Test
    fun `찜에서만 만나는 대회 두 갈래가 다 있다`() {
        // 화면에서 흐림과 "정보 제공 종료" 를 모두 확인할 수 있어야 한다
        val archived = SampleData.allRaces - SampleData.races.toSet()
        val today = com.runninggu.app.domain.today()
        assertTrue("지난 대회가 없다", archived.any { it.date.isBefore(today) })
        assertTrue("비활성 대회가 없다", archived.any { !it.active })
    }

    @Test
    fun `비활성 대회도 상세로 찾을 수 있다`() {
        // 비활성을 404 로 숨기지 않는 것이 계약이다 (§3-4 · 결정-46)
        val inactive = SampleData.allRaces.first { !it.active }
        val found = SampleData.raceById(inactive.id)
        assertTrue(found != null && !found.active)
    }
}
