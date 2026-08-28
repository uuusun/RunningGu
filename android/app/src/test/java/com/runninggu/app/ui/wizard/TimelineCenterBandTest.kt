package com.runninggu.app.ui.wizard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 스크롤 중앙 밴드 판정. (SPEC §4.10 — "상하 45% 제외")
 *
 * 화면 없이 좌표만으로 검증한다. `LazyListState.layoutInfo` 를 그대로 썼으면 기기가
 * 있어야만 확인되는 코드였다 — 계측 테스트는 CI 가 돌리지 않는다(AGENTS 3장).
 *
 * 뷰포트는 0..1000 으로 둔다. 밴드는 **450..550** 이다.
 */
class TimelineCenterBandTest {

    private val blockIds = setOf("blk_1", "blk_2", "blk_3")

    private fun centered(vararg items: TimelineItemBounds) =
        centeredBlockId(viewportStart = 0, viewportEnd = 1000, items = items.toList(), blockIds = blockIds)

    @Test
    fun `중심이 밴드 안에 든 카드를 고른다`() {
        val id = centered(
            TimelineItemBounds("blk_1", offset = 0, size = 200),      // 중심 100
            TimelineItemBounds("blk_2", offset = 400, size = 200),    // 중심 500 ← 밴드
            TimelineItemBounds("blk_3", offset = 700, size = 200),    // 중심 800
        )

        assertEquals("blk_2", id)
    }

    @Test
    fun `밴드를 벗어난 카드만 있으면 아무것도 고르지 않는다`() {
        // 상하 45% 안에 든 것은 활성으로 삼지 않는다. 가장자리 카드까지 잡으면
        // 스크롤 내내 활성이 요동친다
        val id = centered(
            TimelineItemBounds("blk_1", offset = 0, size = 200),      // 중심 100
            TimelineItemBounds("blk_3", offset = 700, size = 200),    // 중심 800
        )

        assertNull(id)
    }

    @Test
    fun `카드가 밴드보다 크면 뷰포트 중앙을 덮은 것을 고른다`() {
        // 중심(400)이 밴드 밖이지만 카드가 화면 한가운데를 차지하고 있다. 여기서 포기하면
        // **긴 카드만 활성이 안 되는** 규칙이 된다
        val id = centered(TimelineItemBounds("blk_2", offset = 0, size = 800))

        assertEquals("blk_2", id)
    }

    @Test
    fun `둘이 걸치면 뷰포트 중앙에 가까운 쪽을 고른다`() {
        val id = centered(
            TimelineItemBounds("blk_1", offset = 400, size = 60),     // 중심 430 — 밴드 밖
            TimelineItemBounds("blk_2", offset = 460, size = 60),     // 중심 490 ← 더 가깝다
            TimelineItemBounds("blk_3", offset = 520, size = 60),     // 중심 550 — 밴드 경계
        )

        assertEquals("blk_2", id)
    }

    @Test
    fun `카드가 아닌 item 은 고르지 않는다`() {
        // 지도·요약·머리글도 같은 LazyColumn 의 item 이다. 거르지 않으면 지도가 가운데
        // 왔을 때 "map" 을 활성 블록으로 넘긴다
        val id = centered(
            TimelineItemBounds("map", offset = 400, size = 200),
            TimelineItemBounds("footer", offset = 620, size = 200),
        )

        assertNull(id)
    }

    @Test
    fun `목록에 없는 블록 id 는 고르지 않는다`() {
        // 일자를 옮기는 중에는 지난 일자의 카드가 잠깐 남아 있을 수 있다
        val id = centered(TimelineItemBounds("blk_9", offset = 400, size = 200))

        assertNull(id)
    }

    @Test
    fun `뷰포트가 아직 없으면 고르지 않는다`() {
        // 첫 프레임에는 높이가 0 이다. 여기서 나눗셈을 하면 밴드가 뒤집힌다
        val id = centeredBlockId(
            viewportStart = 0,
            viewportEnd = 0,
            items = listOf(TimelineItemBounds("blk_1", offset = 0, size = 200)),
            blockIds = blockIds,
        )

        assertNull(id)
    }

    @Test
    fun `뷰포트가 0 에서 시작하지 않아도 같은 자리를 잡는다`() {
        // contentPadding 이 있으면 viewportStartOffset 이 0 이 아니다
        val id = centeredBlockId(
            viewportStart = 200,
            viewportEnd = 1200,
            items = listOf(
                TimelineItemBounds("blk_1", offset = 200, size = 200),   // 중심 300
                TimelineItemBounds("blk_2", offset = 600, size = 200),   // 중심 700 ← 밴드 650..750
            ),
            blockIds = blockIds,
        )

        assertEquals("blk_2", id)
    }
}
