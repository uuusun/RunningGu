package com.runninggu.app.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 편집 연산이 SPEC §5.7 대로 도는지 지킨다.
 *
 * 특히 **대회 블록 거부**(A4)를 연산마다 확인한다 — 원본에는 이 가드가 없어서
 * 목업에서 🏁 스타트 블록이 삭제됐다(대조표 B4).
 */
class ItineraryEditsTest {

    private val raceDate = LocalDate.of(2026, 10, 25)

    private class FakePoiSource : PoiSource {
        override suspend fun load(category: PoiCategory, center: LatLng, count: Int) =
            PoiResult(
                PoiSourceKind.LIVE,
                (1..count).map { Poi("${category.key}-$it", 37.0 + it / 100.0, 127.0 + it / 100.0, desc = category.label) },
            )
    }

    /** 엔진이 실제로 만든 동선으로 편집한다 — 손으로 만든 가짜와 어긋나지 않게. */
    private fun itinerary(end: LocalDate = raceDate.plusDays(1)): List<ItineraryDay> = runBlocking {
        ItineraryEngine(FakePoiSource()).build(
            ItineraryPlan(
                race = RaceInfo("r1", "춘천마라톤", raceDate, 37.87, 127.73, startTime = "09:00"),
                stay = Poi("호텔", 37.88, 127.72, addr = "강원 춘천시"),
                event = EventType.HALF,
                themes = listOf(PoiCategory.TOUR, PoiCategory.FOOD),
                start = raceDate.minusDays(1),
                end = end,
            ),
        ).days
    }

    private fun List<ItineraryDay>.ddayIndex() = indexOfFirst { it.off == 0 }
    private fun List<ItineraryDay>.dday() = first { it.off == 0 }
    private fun ItineraryDay.raceBlock() = blocks.first { it.blockType == BlockType.RACE }
    private fun ItineraryDay.userBlock() = blocks.first { it.blockType == BlockType.USER }

    // ── A4 대회 블록 거부 ───────────────────────────────────────

    @Test
    fun `대회 블록은 편집 대상이 아니다`() {
        val days = itinerary()
        assertFalse(ItineraryEdits.canEdit(days.dday().raceBlock()))
        assertTrue(ItineraryEdits.canEdit(days.dday().userBlock()))
    }

    @Test
    fun `대회 블록은 삭제되지 않는다`() {
        val days = itinerary()
        val i = days.ddayIndex()
        val raceId = days.dday().raceBlock().id

        val after = ItineraryEdits.removeBlock(days, i, raceId)

        assertEquals(days[i].blocks.size, after[i].blocks.size)
        assertNotNull(after[i].blocks.find { it.id == raceId })
    }

    @Test
    fun `대회 블록은 수정되지 않는다`() {
        val days = itinerary()
        val i = days.ddayIndex()
        val raceId = days.dday().raceBlock().id

        val after = ItineraryEdits.updateBlock(days, i, raceId) { it.copy(time = "23:59", title = "바뀜") }

        val block = after[i].blocks.first { it.id == raceId }
        assertEquals("09:00", block.time)
        assertEquals("🏁 춘천마라톤 스타트", block.title)
    }

    @Test
    fun `대회 블록은 장소가 교체되지 않는다`() {
        val days = itinerary()
        val i = days.ddayIndex()
        val raceId = days.dday().raceBlock().id
        val original = days.dday().raceBlock().place

        val after = ItineraryEdits.replacePlace(days, i, raceId, Poi("엉뚱한 곳", 0.0, 0.0))

        assertEquals(original, after[i].blocks.first { it.id == raceId }.place)
    }

    @Test
    fun `대회 블록은 순서를 옮길 수 없다`() {
        val days = itinerary()
        val i = days.ddayIndex()
        val raceIndex = days.dday().blocks.indexOfFirst { it.blockType == BlockType.RACE }

        val after = ItineraryEdits.moveBlock(days, i, raceIndex, raceIndex + 2)

        assertEquals(days[i].blocks.map { it.id }, after[i].blocks.map { it.id })
    }

    @Test
    fun `추가한 블록은 대회 블록이 될 수 없다`() {
        val days = itinerary()
        val i = days.ddayIndex()

        val after = ItineraryEdits.addBlock(
            days, i,
            ItineraryBlock(
                id = "무시됨", time = "16:00", title = "몰래 대회", catKey = BlockCategory.RACE,
                place = null, desc = "", blockType = BlockType.RACE, systemManaged = true,
            ),
        )

        val added = after[i].blocks.last()
        assertEquals(BlockType.USER, added.blockType)
        assertFalse(added.systemManaged)
        assertEquals(1, after[i].blocks.count { it.blockType == BlockType.RACE })
    }

    // ── §5.7 정상 편집 ──────────────────────────────────────────

    @Test
    fun `사용자 블록은 수정되고 id 는 유지된다`() {
        val days = itinerary()
        val i = days.ddayIndex()
        val target = days.dday().userBlock()

        val after = ItineraryEdits.updateBlock(days, i, target.id) { it.copy(time = "12:00", desc = "바꾼 설명") }

        val block = after[i].blocks.first { it.id == target.id }
        assertEquals("12:00", block.time)
        assertEquals("바꾼 설명", block.desc)
        assertEquals(target.id, block.id) // §6.3 안정적 id
    }

    @Test
    fun `사용자 블록은 삭제된다`() {
        val days = itinerary()
        val i = days.ddayIndex()
        val target = days.dday().userBlock()

        val after = ItineraryEdits.removeBlock(days, i, target.id)

        assertEquals(days[i].blocks.size - 1, after[i].blocks.size)
        assertNull(after[i].blocks.find { it.id == target.id })
    }

    @Test
    fun `장소 교체는 id 를 유지하고 설명을 새 장소 것으로 바꾼다`() {
        val days = itinerary()
        val i = days.ddayIndex()
        val target = days.dday().userBlock()
        val newPlace = Poi("대체 장소", 37.5, 127.5, desc = "새 설명")

        val after = ItineraryEdits.replacePlace(days, i, target.id, newPlace, BlockCategory.CAFE)

        val block = after[i].blocks.first { it.id == target.id }
        assertEquals(newPlace, block.place)
        assertEquals("새 설명", block.desc)
        assertEquals(BlockCategory.CAFE, block.catKey)
    }

    @Test
    fun `분류를 안 주면 기존 분류를 둔다`() {
        val days = itinerary()
        val i = days.ddayIndex()
        val target = days.dday().userBlock()

        val after = ItineraryEdits.replacePlace(days, i, target.id, Poi("어딘가", 37.5, 127.5))

        assertEquals(target.catKey, after[i].blocks.first { it.id == target.id }.catKey)
    }

    @Test
    fun `블록을 원하는 자리에 넣는다`() {
        val days = itinerary()
        val i = days.ddayIndex()
        val newBlock = ItineraryBlock("x", "16:00", "추가한 일정", BlockCategory.CAFE, null, "")

        val atEnd = ItineraryEdits.addBlock(days, i, newBlock)
        assertEquals("추가한 일정", atEnd[i].blocks.last().title)

        val atOne = ItineraryEdits.addBlock(days, i, newBlock, atIndex = 1)
        assertEquals("추가한 일정", atOne[i].blocks[1].title)

        // 범위를 넘으면 맨 뒤
        val beyond = ItineraryEdits.addBlock(days, i, newBlock, atIndex = 999)
        assertEquals("추가한 일정", beyond[i].blocks.last().title)
    }

    @Test
    fun `추가한 블록 id 는 동선 전체에서 겹치지 않는다`() {
        val days = itinerary(end = raceDate.plusDays(2))
        val newBlock = ItineraryBlock("x", "16:00", "추가", BlockCategory.CAFE, null, "")

        var result = ItineraryEdits.addBlock(days, 0, newBlock)
        result = ItineraryEdits.addBlock(result, 1, newBlock)
        result = ItineraryEdits.addBlock(result, 2, newBlock)

        val ids = result.flatMap { it.blocks }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `사용자 블록은 같은 날 안에서 순서가 바뀐다`() {
        val days = itinerary()
        val i = days.ddayIndex()
        val before = days[i].blocks.map { it.id }

        // 대회 블록이 0번이므로 1↔2 를 바꾼다
        val after = ItineraryEdits.moveBlock(days, i, 1, 2)

        assertEquals(before[0], after[i].blocks[0].id)
        assertEquals(before[2], after[i].blocks[1].id)
        assertEquals(before[1], after[i].blocks[2].id)
    }

    @Test
    fun `사용자 블록은 대회 블록을 넘어갈 수 없다`() {
        // 계약이 대회 블록의 고정 위치를 넘는 요청을 `409 SYSTEM_BLOCK_IMMUTABLE` 로
        // 거부한다(API 명세 §5-10). 화면이 애초에 그 상태를 못 만들게 여기서 막는다.
        val days = itinerary()
        val i = days.ddayIndex()
        val raceIndex = days.dday().blocks.indexOfFirst { it.blockType == BlockType.RACE }
        val before = days[i].blocks.map { it.id }

        // 대회 블록 뒤에 있는 USER 블록을 맨 앞(대회 앞)으로 끌어 본다
        val after = ItineraryEdits.moveBlock(days, i, raceIndex + 1, raceIndex)

        assertEquals(before, after[i].blocks.map { it.id })
    }

    @Test
    fun `대회 블록을 지나 여러 칸 옮기는 것도 막는다`() {
        val days = itinerary()
        val i = days.ddayIndex()
        val raceIndex = days.dday().blocks.indexOfFirst { it.blockType == BlockType.RACE }
        val before = days[i].blocks.map { it.id }

        // 대회 블록이 0번이면 2번을 0번으로 — 사이에 대회가 있다
        val after = ItineraryEdits.moveBlock(days, i, raceIndex + 2, raceIndex)

        assertEquals(before, after[i].blocks.map { it.id })
    }

    @Test
    fun `대회 블록을 지나지 않으면 그대로 옮긴다`() {
        // 막는 것은 "넘는 것" 뿐이다. 같은 쪽에서 자리를 바꾸는 것은 계약상 문제없다.
        val days = itinerary()
        val i = days.ddayIndex()
        val raceIndex = days.dday().blocks.indexOfFirst { it.blockType == BlockType.RACE }
        val before = days[i].blocks.map { it.id }

        val after = ItineraryEdits.moveBlock(days, i, raceIndex + 1, raceIndex + 2)

        assertEquals(before[raceIndex + 2], after[i].blocks[raceIndex + 1].id)
        assertEquals(before[raceIndex + 1], after[i].blocks[raceIndex + 2].id)
    }

    @Test
    fun `범위를 벗어난 인덱스는 무시한다`() {
        val days = itinerary()
        val i = days.ddayIndex()

        assertEquals(days[i].blocks, ItineraryEdits.moveBlock(days, i, -1, 0)[i].blocks)
        assertEquals(days[i].blocks, ItineraryEdits.moveBlock(days, i, 0, 99)[i].blocks)
        assertSame(days, ItineraryEdits.removeBlock(days, 99, "blk_1"))
    }

    // ── 불변성 ─────────────────────────────────────────────────

    @Test
    fun `편집해도 원본 리스트는 그대로다`() {
        val days = itinerary()
        val i = days.ddayIndex()
        val originalIds = days[i].blocks.map { it.id }
        val originalSize = days[i].blocks.size

        ItineraryEdits.removeBlock(days, i, days.dday().userBlock().id)
        ItineraryEdits.addBlock(days, i, ItineraryBlock("x", "1", "2", BlockCategory.CAFE, null, ""))
        ItineraryEdits.moveBlock(days, i, 1, 2)

        assertEquals(originalSize, days[i].blocks.size)
        assertEquals(originalIds, days[i].blocks.map { it.id })
    }

    @Test
    fun `편집하지 않은 일자는 손대지 않는다`() {
        val days = itinerary()
        val i = days.ddayIndex()

        val after = ItineraryEdits.removeBlock(days, i, days.dday().userBlock().id)

        days.indices.filter { it != i }.forEach { assertSame(days[it], after[it]) }
    }

    // ── §5.7 파생값 ─────────────────────────────────────────────

    @Test
    fun `지도 핀은 좌표가 있는 블록만 세운다`() {
        val days = itinerary()
        val pins = ItineraryEdits.dayPins(days.dday())

        val withPlace = days.dday().blocks.count { it.place != null }
        assertEquals(withPlace, pins.size)
    }

    @Test
    fun `핀 번호는 카드 순번 그대로라 중간이 빈다`() {
        // 좌표 없는 블록이 섞여도 좌표 있는 것만 1부터 다시 매기지 않는다. 그러면 같은
        // 장소가 카드에서 3, 지도에서 2로 보인다 (SPEC §5.7 🔒 · #208 리뷰 합의)
        val day = ItineraryDay(
            raceDate, 0, "D-day", "10.25 일", "",
            listOf(
                ItineraryBlock("blk_1", "09:00", "대회", BlockCategory.RACE, Poi("대회장", 37.52, 126.93), ""),
                ItineraryBlock("blk_2", "12:00", "장소 미정", BlockCategory.FOOD, null, ""),
                ItineraryBlock("blk_3", "15:00", "카페", BlockCategory.CAFE, Poi("로스터리", 37.53, 126.94), ""),
            ),
        )

        val pins = ItineraryEdits.dayPins(day)

        assertEquals(listOf(1, 3), pins.map { it.n })
        assertEquals(listOf("blk_1", "blk_3"), pins.map { it.blockId })
    }

    @Test
    fun `대회 블록도 지도 핀에는 포함된다`() {
        val days = itinerary()
        val raceId = days.dday().raceBlock().id

        assertNotNull(ItineraryEdits.dayPins(days.dday()).find { it.blockId == raceId })
    }

    @Test
    fun `장소가 없으면 핀도 없다`() {
        val day = ItineraryDay(
            raceDate, 0, "D-day", "10.25 일", "",
            listOf(ItineraryBlock("blk_1", "10:00", "장소 미정", BlockCategory.TOUR, null, "")),
        )
        assertTrue(ItineraryEdits.dayPins(day).isEmpty())
        assertTrue(ItineraryEdits.dayPins(null).isEmpty())
    }

    @Test
    fun `장소 수는 전체 일자를 합산한다`() {
        val days = itinerary(end = raceDate.plusDays(2))
        val expected = days.sumOf { day -> day.blocks.count { it.place != null } }

        assertEquals(expected, ItineraryEdits.countPlaces(days))
    }

    @Test
    fun `장소를 지우면 수와 핀이 함께 줄어든다`() {
        val days = itinerary()
        val i = days.ddayIndex()
        val target = days.dday().blocks.first { it.blockType == BlockType.USER && it.place != null }

        val after = ItineraryEdits.removeBlock(days, i, target.id)

        assertEquals(ItineraryEdits.countPlaces(days) - 1, ItineraryEdits.countPlaces(after))
        assertEquals(ItineraryEdits.dayPins(days[i]).size - 1, ItineraryEdits.dayPins(after[i]).size)
    }
}
