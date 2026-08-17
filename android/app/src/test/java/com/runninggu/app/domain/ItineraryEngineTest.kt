package com.runninggu.app.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 동선 엔진이 SPEC §5.6 표대로 블록을 만드는지 지킨다.
 *
 * [PoiSource] 를 가짜로 끼워 네트워크 없이 돈다 — 엔진이 POI 조회를 직접 하지 않게
 * 설계한 이유가 이것이다.
 */
class ItineraryEngineTest {

    // ── 고정 입력 ──────────────────────────────────────────────

    private val raceDate = LocalDate.of(2026, 10, 25)

    private val race = RaceInfo(
        id = "chuncheon-2026",
        name = "춘천마라톤",
        date = raceDate,
        lat = 37.87,
        lng = 127.73,
        venue = "춘천 공지천",
        region = "강원",
        startTime = "09:00",
        officialUrl = "https://example.test/chuncheon",
    )

    private val stay = Poi("호텔 춘천", 37.88, 127.72, desc = "숙박", addr = "강원 춘천시 1로")

    /** 카테고리마다 8건씩 서로 다른 이름을 주는 가짜 공급원. */
    private class FakePoiSource(
        private val kind: PoiSourceKind = PoiSourceKind.LIVE,
        private val countPerCategory: Int = 8,
    ) : PoiSource {
        val requested = mutableListOf<PoiCategory>()

        override suspend fun load(category: PoiCategory, center: LatLng, count: Int): PoiResult {
            requested += category
            val places = (1..countPerCategory).map {
                Poi(
                    name = "${category.key}-$it",
                    lat = center.lat,
                    lng = center.lng,
                    desc = "${category.label} 설명",
                )
            }
            return PoiResult(kind, places.take(count))
        }
    }

    private fun plan(
        event: EventType,
        start: LocalDate = raceDate.minusDays(1),
        end: LocalDate = raceDate.plusDays(1),
        themes: List<PoiCategory> = listOf(PoiCategory.TOUR, PoiCategory.FOOD),
        stay: Poi? = this.stay,
    ) = ItineraryPlan(race, stay, event, themes, start, end)

    private fun ItineraryDay.times() = blocks.map { it.time }
    private fun ItineraryDay.titles() = blocks.map { it.title }

    // ── §5.6-4 일자별 블록 ──────────────────────────────────────

    @Test
    fun `전날은 체크인과 카보로딩 저녁 두 블록이다`() = runBlocking {
        val result = ItineraryEngine(FakePoiSource()).build(plan(EventType.HALF))
        val pre = result.days.first { it.off == -1 }

        assertEquals(listOf("15:00", "18:30"), pre.times())
        assertEquals(listOf("숙소 체크인", "카보로딩 저녁"), pre.titles())
        assertEquals("내일 완주 · 가볍게 먹고 푹 쉬기", pre.note)
    }

    @Test
    fun `회복이 필요한 종목의 대회일은 온천과 회복 저녁이다`() = runBlocking {
        val result = ItineraryEngine(FakePoiSource()).build(plan(EventType.FULL))
        val dday = result.days.first { it.off == 0 }

        assertEquals(listOf("09:00", "11:00", "18:00"), dday.times())
        assertEquals("온천·회복", dday.titles()[1])
        assertEquals("완주 후 회복 집중, 도보 최소", dday.note) // §5.1 풀 dday
    }

    @Test
    fun `가벼운 관광은 하프에만 붙고 풀에는 없다`() = runBlocking {
        val half = ItineraryEngine(FakePoiSource()).build(plan(EventType.HALF))
        val full = ItineraryEngine(FakePoiSource()).build(plan(EventType.FULL))

        assertTrue("가벼운 관광" in half.days.first { it.off == 0 }.titles())
        assertFalse("가벼운 관광" in full.days.first { it.off == 0 }.titles())
    }

    @Test
    fun `회복이 필요없는 종목의 대회일은 관광 카페 저녁이다`() = runBlocking {
        val result = ItineraryEngine(FakePoiSource()).build(plan(EventType.TEN_K))
        val dday = result.days.first { it.off == 0 }

        assertEquals(listOf("09:00", "13:00", "15:30", "18:30"), dday.times())
        assertEquals(listOf("오후 자유 관광", "카페 한 잔", "맛집 저녁"), dday.titles().drop(1))
        assertEquals("완주 후 가벼운 관광·축제", dday.note) // §5.1 10K dday
    }

    @Test
    fun `마지막날에만 체크아웃이 붙는다`() = runBlocking {
        val result = ItineraryEngine(FakePoiSource())
            .build(plan(EventType.FIVE_K, end = raceDate.plusDays(2)))

        val plusOne = result.days.first { it.off == 1 }
        val last = result.days.first { it.off == 2 }

        assertFalse("체크아웃·귀가" in plusOne.titles())
        assertTrue("체크아웃·귀가" in last.titles())
    }

    // ── A1 · A2 산책 블록 제거 ──────────────────────────────────

    @Test
    fun `산책 블록은 어디에도 없다`() = runBlocking {
        val result = ItineraryEngine(FakePoiSource())
            .build(plan(EventType.HALF, end = raceDate.plusDays(2)))

        val titles = result.days.flatMap { it.titles() }
        assertFalse(titles.any { "산책" in it })
        // 원본이 D-1 20:00 · D-day 20:30 · D+N 08:00 에 넣던 자리다 (§5.6 삭제 확정)
        assertFalse(result.days.flatMap { it.times() }.any { it in setOf("20:00", "20:30", "08:00") })
    }

    @Test
    fun `산책용 POI 를 조회하지 않는다`() = runBlocking {
        val source = FakePoiSource()
        ItineraryEngine(source).build(plan(EventType.HALF))

        // 원본은 숙소 기준 nature 6건을 따로 받아 pools.walk 에 넣었다 (§5.6-3 에서 제거)
        assertFalse(PoiCategory.NATURE in source.requested)
    }

    // ── A4 대회 블록 잠금 (§5.6-7) ──────────────────────────────

    @Test
    fun `대회 블록만 시스템 관리 대상이다`() = runBlocking {
        val result = ItineraryEngine(FakePoiSource()).build(plan(EventType.HALF))
        val all = result.days.flatMap { it.blocks }
        val race = all.filter { it.blockType == BlockType.RACE }

        assertEquals(1, race.size)
        assertTrue(race.single().systemManaged)
        assertEquals("🏁 춘천마라톤 스타트", race.single().title)
        assertTrue(all.filter { it.blockType == BlockType.USER }.none { it.systemManaged })
    }

    @Test
    fun `대회 블록 시각은 대회 출발시각이고 없으면 여덟시다`() = runBlocking {
        val withTime = ItineraryEngine(FakePoiSource()).build(plan(EventType.HALF))
        assertEquals("09:00", withTime.days.first { it.off == 0 }.blocks.first().time)

        val noTime = ItineraryEngine(FakePoiSource())
            .build(plan(EventType.HALF).copy(race = race.copy(startTime = "")))
        assertEquals("08:00", noTime.days.first { it.off == 0 }.blocks.first().time)
    }

    // ── §5.6-2 카테고리 풀 ──────────────────────────────────────

    @Test
    fun `회복 종목은 웰니스를 아니면 카페를 보탠다`() = runBlocking {
        val halfSource = FakePoiSource()
        ItineraryEngine(halfSource).build(plan(EventType.HALF, themes = listOf(PoiCategory.HISTORY)))
        assertTrue(PoiCategory.WELLNESS in halfSource.requested)
        assertFalse(PoiCategory.CAFE in halfSource.requested)

        val tenKSource = FakePoiSource()
        ItineraryEngine(tenKSource).build(plan(EventType.TEN_K, themes = listOf(PoiCategory.HISTORY)))
        assertTrue(PoiCategory.CAFE in tenKSource.requested)
        assertFalse(PoiCategory.WELLNESS in tenKSource.requested)
    }

    @Test
    fun `맛집과 관광지는 취향과 무관하게 항상 적재한다`() = runBlocking {
        val source = FakePoiSource()
        ItineraryEngine(source).build(plan(EventType.FIVE_K, themes = listOf(PoiCategory.HISTORY)))

        assertTrue(PoiCategory.FOOD in source.requested)
        assertTrue(PoiCategory.TOUR in source.requested)
    }

    @Test
    fun `취향이 비면 관광지와 맛집을 기본으로 쓴다`() = runBlocking {
        val source = FakePoiSource()
        ItineraryEngine(source).build(plan(EventType.FIVE_K, themes = emptyList()))

        assertEquals(PoiCategory.DEFAULT_THEMES.toSet(), setOf(PoiCategory.TOUR, PoiCategory.FOOD))
        assertTrue(source.requested.containsAll(PoiCategory.DEFAULT_THEMES))
    }

    // ── §5.6-4 장소 중복 없음 ───────────────────────────────────

    @Test
    fun `장소는 전체 일정에서 중복되지 않는다`() = runBlocking {
        val result = ItineraryEngine(FakePoiSource())
            .build(plan(EventType.TEN_K, end = raceDate.plusDays(2)))

        // 대회장·숙소는 의사 POI 라 여러 번 나올 수 있어 제외한다.
        val names = result.days.flatMap { it.blocks }
            .filter { it.catKey !in setOf(BlockCategory.RACE, BlockCategory.LODGING) }
            .mapNotNull { it.place?.name }

        assertEquals(names.size, names.toSet().size)
    }

    // ── §5.6-6 회복 배지 ────────────────────────────────────────

    @Test
    fun `회복 배지는 하프 풀에만 뜬다`() = runBlocking {
        val engine = ItineraryEngine(FakePoiSource())

        assertNotNull(engine.build(plan(EventType.HALF)).recovery)
        assertNotNull(engine.build(plan(EventType.FULL)).recovery)
        assertNull(engine.build(plan(EventType.FIVE_K)).recovery)
        assertNull(engine.build(plan(EventType.TEN_K)).recovery)
    }

    @Test
    fun `D플러스가 있으면 그 라벨을 없으면 D-day 를 쓴다`() = runBlocking {
        val engine = ItineraryEngine(FakePoiSource())

        val withPlus = engine.build(plan(EventType.HALF)).recovery
        assertEquals("D+1 회복 모드", withPlus?.label)

        val onlyDday = engine.build(plan(EventType.HALF, start = raceDate, end = raceDate)).recovery
        assertEquals("D-day 회복 모드", onlyDday?.label)
    }

    // ── §6.3 계약 ───────────────────────────────────────────────

    @Test
    fun `일자 라벨과 오프셋이 대회일 기준이다`() = runBlocking {
        val result = ItineraryEngine(FakePoiSource())
            .build(plan(EventType.FIVE_K, end = raceDate.plusDays(1)))

        assertEquals(listOf(-1, 0, 1), result.days.map { it.off })
        assertEquals(listOf("D-1", "D-day", "D+1"), result.days.map { it.label })
        assertEquals("10.24 토", result.days.first().dateLabel)
    }

    @Test
    fun `블록 id 는 서로 다르다`() = runBlocking {
        val result = ItineraryEngine(FakePoiSource())
            .build(plan(EventType.HALF, end = raceDate.plusDays(2)))

        val ids = result.days.flatMap { it.blocks }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `출처는 적재한 카테고리마다 기록된다`() = runBlocking {
        val result = ItineraryEngine(FakePoiSource(PoiSourceKind.SAMPLE)).build(plan(EventType.HALF))

        assertTrue(result.sources.isNotEmpty())
        assertTrue(result.sources.values.all { it == PoiSourceKind.SAMPLE })
        assertEquals(setOf(PoiCategory.FOOD, PoiCategory.TOUR, PoiCategory.WELLNESS), result.sources.keys)
    }

    // ── 가장자리 ────────────────────────────────────────────────

    @Test
    fun `숙소가 없어도 동선을 만든다`() = runBlocking {
        val result = ItineraryEngine(FakePoiSource()).build(plan(EventType.HALF, stay = null))
        val checkIn = result.days.first { it.off == -1 }.blocks.first()

        assertEquals("숙소 체크인", checkIn.title)
        assertNull(checkIn.place)
        assertEquals("여장 풀기", checkIn.desc)
    }

    @Test
    fun `당일치기는 대회일 하루만 만든다`() = runBlocking {
        val result = ItineraryEngine(FakePoiSource())
            .build(plan(EventType.FIVE_K, start = raceDate, end = raceDate))

        assertEquals(1, result.days.size)
        assertEquals(0, result.days.single().off)
    }

    @Test
    fun `POI 가 바닥나면 앞의 장소를 다시 쓴다`() = runBlocking {
        // 카테고리당 1건뿐이면 여러 날에 걸쳐 같은 곳이 반복된다 — 원본과 같은 동작이다.
        val result = ItineraryEngine(FakePoiSource(countPerCategory = 1))
            .build(plan(EventType.FIVE_K, end = raceDate.plusDays(2)))

        val foods = result.days.flatMap { it.blocks }
            .filter { it.catKey == BlockCategory.FOOD }
            .mapNotNull { it.place?.name }

        assertTrue(foods.size > 1)
        assertEquals(1, foods.toSet().size)
    }
}
