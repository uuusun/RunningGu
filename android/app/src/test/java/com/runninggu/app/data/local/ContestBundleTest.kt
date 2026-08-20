package com.runninggu.app.data.local

import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.RegistrationStatus
import com.runninggu.app.domain.regStatusOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * 번들 파싱. (SPEC §6.1 · §6.2)
 *
 * 실제 번들(`assets/races.json`)을 그대로 읽어 검증한다 — 스크립트가 만드는 모양과
 * 앱이 읽는 모양이 어긋나면 여기서 먼저 깨져야 한다.
 */
class ContestBundleTest {

    private fun bundleText(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("races.json")) {
            "테스트 리소스에 races.json 이 없다"
        }.bufferedReader().use { it.readText() }

    // ── 실제 번들 ──────────────────────────────────────────────

    @Test
    fun `번들을 통째로 읽는다`() {
        val result = ContestBundle.parse(bundleText())

        assertNull(result.error)
        assertTrue(result.isUsable)
        // 스크립트가 canonical 153건을 만든다 (scripts/README)
        assertEquals(153, result.contests.size)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `모든 항목이 필수 필드를 갖는다`() {
        val contests = ContestBundle.parse(bundleText()).contests

        assertTrue(contests.all { it.id.isNotBlank() })
        assertTrue(contests.all { it.name.isNotBlank() })
        // 좌표 누락 0건 — SPEC §6.2 가 명시한 현재 상태다. 깨지면 동선 생성이 막힌다
        assertTrue(contests.all { it.hasLocation })
    }

    @Test
    fun `종목 미표기는 2건뿐이다`() {
        // 번들이 낡으면 이 수가 늘어난다 — PR #47 리뷰에서 실제로 26건짜리 옛 산출물이
        // 들어올 뻔했다. 스크립트를 고쳐 값이 바뀌면 여기도 **의도적으로** 갱신한다.
        val contests = ContestBundle.parse(bundleText()).contests
        val missing = contests.filter { it.eventTypes.isEmpty() }

        assertEquals("종목 미표기: ${missing.map { it.name }}", 2, missing.size)
    }

    @Test
    fun `거리만 있는 트레일 대회도 종목이 채워져 있다`() {
        // SPEC §5.4 ② 거리 버킷(이슈 #48). 토큰 "15km" 는 예전에 5K 로 오분류됐고,
        // 그 값이 번들에 박제되면 오프라인 폴백이 틀린 종목을 보여준다.
        val contests = ContestBundle.parse(bundleText()).contests
        val trail = contests.first { it.name == "경주 남산트레일런" }

        assertEquals(listOf(EventType.TEN_K), trail.eventTypes)
    }

    @Test
    fun `지역은 17개 시도 안에 있다`() {
        val regions = ContestBundle.parse(bundleText()).contests.map { it.region }.toSet()
        val sido = setOf(
            "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종",
            "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주",
        )
        assertTrue("시도 밖 값: ${regions - sido}", sido.containsAll(regions))
    }

    // ── 매핑 ──────────────────────────────────────────────────

    @Test
    fun `한 건을 계약대로 옮긴다`() {
        val raw = """
            [{"id":"roadrun-41543","name":"2026 백산수 심심런","region":"서울",
              "venue":"여의도한강공원","date":"2026-06-06","startTime":"09:00",
              "eventTypes":["10K","5K"],"regStatus":"마감",
              "regStart":"2026-04-20","regEnd":"2026-05-22",
              "organizer":"한국백혈병소아암협회","source":"마라톤온라인","checked":"2026-06-14",
              "officialUrl":"http://simsimrun.com","detailUrl":"http://roadrun.co.kr/x",
              "imageUrl":"","lat":37.52,"lng":126.93,"category":"로드"}]
        """.trimIndent()

        val c = ContestBundle.parse(raw).contests.single()

        assertEquals("roadrun-41543", c.id)
        assertEquals(LocalDate.of(2026, 6, 6), c.date)
        assertEquals(LocalTime.of(9, 0), c.startTime)
        assertEquals(listOf(EventType.TEN_K, EventType.FIVE_K), c.eventTypes)
        assertEquals(RegistrationStatus.CLOSED, c.regStatusFallback)
        // 번들의 한국어 라벨을 서버와 같은 토큰으로 옮긴다 (스냅샷 계약 §2.3)
        assertEquals(listOf("MARATHON_ONLINE"), c.sources)
        // 빈 문자열은 null 로 — 화면이 placeholder 를 쓸 수 있어야 한다(§6.2)
        assertNull(c.imageUrl)
        assertNotNull(c.officialUrl)
    }

    @Test
    fun `병합된 대회의 출처는 서버와 같은 토큰 배열이 된다`() {
        // 번들은 "마라톤GO·마라톤온라인" 한 문자열, 서버는 토큰 두 개다 — 같은 값이어야 한다
        val raw = """
            [{"id":"x","name":"n","date":"2026-09-01","source":"마라톤GO·마라톤온라인"}]
        """.trimIndent()

        val c = ContestBundle.parse(raw).contests.single()

        assertEquals(listOf("MARATHON_GO", "MARATHON_ONLINE"), c.sources)
    }

    @Test
    fun `모르는 출처 라벨은 버리지 않는다`() {
        // 원천이 늘었을 때 조용히 사라지는 것보다 낯선 값이 보이는 편이 낫다
        val raw = """
            [{"id":"x","name":"n","date":"2026-09-01","source":"마라톤GO·새원천"}]
        """.trimIndent()

        val c = ContestBundle.parse(raw).contests.single()

        assertEquals(listOf("MARATHON_GO", "새원천"), c.sources)
    }

    @Test
    fun `출처가 비면 빈 목록이다`() {
        val raw = """[{"id":"x","name":"n","date":"2026-09-01"}]"""

        assertEquals(emptyList<String>(), ContestBundle.parse(raw).contests.single().sources)
    }

    @Test
    fun `번들의 접수 상태는 그대로 쓰지 않고 재계산 근거로만 쓴다`() {
        // 번들은 "마감"이라 하지만 오늘이 접수 기간 안이면 접수중이다 (§5.5)
        val raw = """
            [{"id":"x","name":"n","date":"2026-09-01","regStatus":"마감",
              "regStart":"2026-05-01","regEnd":"2026-08-31"}]
        """.trimIndent()

        val c = ContestBundle.parse(raw).contests.single()

        assertEquals(RegistrationStatus.CLOSED, c.regStatusFallback)
        assertEquals(
            RegistrationStatus.OPEN,
            regStatusOf(c.regStart, c.regEnd, c.regStatusFallback, LocalDate.of(2026, 6, 1)),
        )
    }

    // ── 깨진 입력 ──────────────────────────────────────────────

    @Test
    fun `날짜가 깨진 항목만 버리고 나머지는 살린다`() {
        // 폴백이라 한 건 때문에 전부 못 쓰면 안 된다
        val raw = """
            [{"id":"a","name":"정상","date":"2026-06-06"},
             {"id":"b","name":"깨짐","date":"2026/06/06"}]
        """.trimIndent()

        val result = ContestBundle.parse(raw)

        assertEquals(1, result.contests.size)
        assertEquals("a", result.contests.single().id)
        // 조용히 사라지지 않게 남긴다
        assertEquals(1, result.skipped)
    }

    @Test
    fun `모르는 필드가 있어도 읽는다`() {
        val raw = """[{"id":"a","name":"n","date":"2026-06-06","newField":123}]"""
        assertEquals(1, ContestBundle.parse(raw).contests.size)
    }

    @Test
    fun `형식이 아예 다르면 비어 있고 사유를 남긴다`() {
        val result = ContestBundle.parse("""{"races": []}""")

        assertFalse(result.isUsable)
        assertNotNull(result.error)
    }
}
