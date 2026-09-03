package com.runninggu.app.ui.racedetail

import com.runninggu.app.ui.model.RaceSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * S3 [공유] 로 나가는 본문. (SPEC §4.6 · A4)
 *
 * ## 망가뜨리면 이것만 실패한다
 *
 * - `openableWebUrl` 대신 `race.officialUrl` 을 그대로 쓰면 → `열 수 없는 주소는 빼고 보낸다` 만 실패
 * - `venue.isNotBlank()` 가드를 빼면 → `장소가 없으면 가운뎃점도 없다` 만 실패
 * - 요일을 빼면 → `이름 · 날짜 · 장소 · 링크 순으로 넣는다` 만 실패
 */
class RaceShareTextTest {

    private fun race(
        venue: String = "뚝섬 한강공원 수변무대",
        officialUrl: String? = "https://example.org/gd-peace",
    ) = RaceSummary(
        id = "gd-peace-11",
        name = "제11회 김대중 평화 마라톤 대회",
        region = "서울",
        venue = venue,
        date = LocalDate.of(2026, 9, 6),
        startTime = "08:00",
        regStart = LocalDate.of(2026, 5, 13),
        regEnd = LocalDate.of(2026, 8, 30),
        eventTypes = listOf("하프", "10K", "5K"),
        source = "마라톤GO·마라톤온라인",
        checked = LocalDate.of(2026, 6, 14),
        officialUrl = officialUrl,
    )

    @Test
    fun `이름 · 날짜 · 장소 · 링크 순으로 넣는다`() {
        assertEquals(
            """
            제11회 김대중 평화 마라톤 대회
            09.06 일 08:00 · 뚝섬 한강공원 수변무대
            https://example.org/gd-peace
            """.trimIndent(),
            raceShareText(race()),
        )
    }

    // 화면의 [공식 페이지 ↗] 가 같은 기준으로 버튼을 감춘다. 화면에 없는 링크가
    // 공유로만 나가면 받은 사람이 못 여는 주소를 받는다.
    @Test
    fun `열 수 없는 주소는 빼고 보낸다`() {
        assertEquals(
            """
            제11회 김대중 평화 마라톤 대회
            09.06 일 08:00 · 뚝섬 한강공원 수변무대
            """.trimIndent(),
            raceShareText(race(officialUrl = "javascript:alert(1)")),
        )
    }

    @Test
    fun `주소가 아예 없어도 본문은 선다`() {
        assertEquals(
            """
            제11회 김대중 평화 마라톤 대회
            09.06 일 08:00 · 뚝섬 한강공원 수변무대
            """.trimIndent(),
            raceShareText(race(officialUrl = null)),
        )
    }

    @Test
    fun `장소가 없으면 가운뎃점도 없다`() {
        assertEquals(
            """
            제11회 김대중 평화 마라톤 대회
            09.06 일 08:00
            https://example.org/gd-peace
            """.trimIndent(),
            raceShareText(race(venue = "")),
        )
    }
}
