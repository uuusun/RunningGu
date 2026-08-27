package com.runninggu.app.ui.wizard

import com.runninggu.app.data.model.ItineraryRequestSnapshot
import com.runninggu.app.data.model.ItineraryResult
import com.runninggu.app.domain.BlockCategory
import com.runninggu.app.domain.BlockType
import com.runninggu.app.domain.ItineraryBlock
import com.runninggu.app.domain.ItineraryDay
import com.runninggu.app.domain.Poi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * S7 동선 결과의 지도 핀. (SPEC §3-8 · §4.10 · AP-03)
 *
 * > 좌표 있는 항목만 **방문 순서 번호 핀** + 항목을 잇는 **폴리라인**. 활성 핀 확대·강조.
 * > 카메라: 핀/경로 구성 변경 시 전체 bounds, 활성 핀만 변경 시 해당 좌표로 이동. — §3-8
 *
 * S8 러닝코스와 **반대로 잇는다.** 하루 동선은 방문 순서가 있어서 선이 의미를 갖고,
 * 흩어진 걷기 스팟은 그렇지 않다(§4.11-4).
 */
class DayMapPinsTest {

    @Test
    fun `좌표 있는 블록만 핀이 된다`() {
        // 서버가 외부 POI 조회에 실패하면 장소를 null 로 강등하되 생성은 성공시킨다
        // (API 명세 §5-1 · NFR-3). 그 블록은 지도에 세울 자리가 없다.
        val state = stateOf(
            block("blk_1", place = poi(37.52, 126.93)),
            block("blk_2", place = null),
            block("blk_3", place = poi(37.53, 126.94)),
        )

        val pins = state.mapPins

        assertEquals(listOf("blk_1", "blk_3"), pins.map { it.id })
    }

    @Test
    fun `좌표가 다 있으면 번호가 이어진다`() {
        val state = stateOf(
            block("blk_1", place = poi(37.52, 126.93)),
            block("blk_2", place = poi(37.53, 126.94)),
            block("blk_3", place = poi(37.54, 126.95)),
        )

        assertEquals(listOf(1, 2, 3), state.mapPins.map { it.order })
    }

    @Test
    fun `좌표가 하나도 없으면 핀이 없다`() {
        // 지도 영역만 안내로 바뀌고 타임라인은 그대로다 — 실패 격리 (§3-8 · NFR-1·3)
        val state = stateOf(block("blk_1", place = null))

        assertTrue(state.mapPins.isEmpty())
    }

    @Test
    fun `대회 블록도 핀으로 선다`() {
        // 편집은 막지만 지도에는 나와야 한다 (SPEC §5.7)
        val state = stateOf(
            block("blk_1", place = poi(37.52, 126.93), type = BlockType.RACE),
        )

        assertEquals("blk_1", state.mapPins.single().id)
    }

    @Test
    fun `회복일에는 핀 액센트가 주황이 된다`() {
        // 동선 파랑 · 회복일 주황 (§3-8 범례)
        val state = stateOf(
            block("blk_1", place = poi(37.52, 126.93)),
            recoveryFlags = listOf(true),
        )

        assertTrue(state.mapPins.single().recovery)
    }

    @Test
    fun `회복일이 아니면 주황이 아니다`() {
        val state = stateOf(
            block("blk_1", place = poi(37.52, 126.93)),
            recoveryFlags = listOf(false),
        )

        assertFalse(state.mapPins.single().recovery)
    }

    // ── 활성 핀 (§3-8) ─────────────────────────────────────────

    @Test
    fun `번호는 카드 순번 그대로라 중간이 빈다`() {
        // 좌표 있는 것만 1부터 다시 매기면 같은 장소가 카드에서 3, 지도에서 2로 보인다
        // (SPEC §5.7 🔒 · §4.11-4 와 같은 규칙 · #208 리뷰 합의)
        val state = stateOf(
            block("blk_1", place = poi(37.52, 126.93)),
            block("blk_2", place = null),
            block("blk_3", place = poi(37.54, 126.95)),
        )

        assertEquals(listOf(1, 3), state.mapPins.map { it.order })
    }

    @Test
    fun `고른 블록이 활성 핀이 된다`() {
        val state = stateOf(
            block("blk_1", place = poi(37.52, 126.93)),
            block("blk_2", place = poi(37.53, 126.94)),
        ).copy(activeBlockId = "blk_2")

        assertEquals("blk_2", state.activePinId)
    }

    @Test
    fun `이 일자에 없는 블록은 활성 핀이 아니다`() {
        // 일자를 옮겼는데 남아 있으면 카메라가 어제 자리로 간다
        val state = stateOf(block("blk_1", place = poi(37.52, 126.93)))
            .copy(activeBlockId = "어제_블록")

        assertNull(state.activePinId)
    }

    @Test
    fun `좌표 없는 블록은 활성 핀이 될 수 없다`() {
        // 핀이 없으니 카메라가 갈 곳도 없다
        val state = stateOf(
            block("blk_1", place = poi(37.52, 126.93)),
            block("blk_2", place = null),
        ).copy(activeBlockId = "blk_2")

        assertNull(state.activePinId)
    }

    @Test
    fun `일자를 옮기면 그 일자의 핀만 선다`() {
        val state = ResultUiState(
            phase = ResultUiState.Phase.CONTENT,
            result = resultOf(
                days = listOf(
                    day(0, block("blk_1", place = poi(37.52, 126.93))),
                    day(1, block("blk_9", place = poi(37.60, 127.00))),
                ),
                recoveryFlags = listOf(false, true),
            ),
        )

        assertEquals("blk_1", state.mapPins.single().id)
        assertFalse(state.mapPins.single().recovery)

        val nextDay = state.copy(activeDayIndex = 1)

        assertEquals("blk_9", nextDay.mapPins.single().id)
        // 둘째 날이 회복일이라 액센트가 바뀐다
        assertTrue(nextDay.mapPins.single().recovery)
    }

    // ── 도구 ──────────────────────────────────────────────────

    private fun stateOf(
        vararg blocks: ItineraryBlock,
        recoveryFlags: List<Boolean> = listOf(false),
    ) = ResultUiState(
        phase = ResultUiState.Phase.CONTENT,
        result = resultOf(listOf(day(0, *blocks)), recoveryFlags),
    )

    private fun resultOf(days: List<ItineraryDay>, recoveryFlags: List<Boolean>) = ItineraryResult(
        title = "2박 3일",
        request = ItineraryRequestSnapshot(
            contestId = 1,
            event = "HALF",
            themes = listOf("TOUR"),
            startDate = "2026-09-03",
            endDate = "2026-09-05",
            hotel = null,
        ),
        days = days,
        recovery = null,
        recoveryFlags = recoveryFlags,
    )

    private fun day(off: Int, vararg blocks: ItineraryBlock) = ItineraryDay(
        date = LocalDate.of(2026, 9, 4).plusDays(off.toLong()),
        off = off,
        label = if (off == 0) "D-day" else "D+$off",
        dateLabel = "09.0${4 + off}",
        note = "",
        blocks = blocks.toList(),
    )

    private fun block(
        id: String,
        place: Poi?,
        type: BlockType = BlockType.USER,
    ) = ItineraryBlock(
        id = id,
        time = "10:00",
        title = "블록 $id",
        catKey = if (type == BlockType.RACE) BlockCategory.RACE else BlockCategory.TOUR,
        place = place,
        desc = "",
        blockType = type,
        systemManaged = type == BlockType.RACE,
    )

    private fun poi(lat: Double, lng: Double) = Poi(name = "장소", lat = lat, lng = lng, addr = "주소")
}
