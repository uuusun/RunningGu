package com.runninggu.app.ui.wizard

import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.runninggu.app.domain.BlockCategory
import com.runninggu.app.domain.BlockType
import com.runninggu.app.domain.ItineraryBlock
import com.runninggu.app.domain.ItineraryDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * S7 편집 목록의 순서 변경을 **접근성 서비스로도 할 수 있는지**. (이슈 #71)
 *
 * #51 에서 위/아래 버튼을 그립 롱프레스 드래그로 바꾸면서, TalkBack 사용자는 순서를
 * 바꿀 방법이 사라졌었다 — 드래그는 손가락으로만 되는 동작이다. 커스텀 액션이 그 자리를
 * 대신하는데, **접근성 회귀는 화면만 봐서는 보이지 않는다.** 그래서 여기서 고정한다.
 */
class EditListAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private fun block(id: String, title: String, race: Boolean = false) = ItineraryBlock(
        id = id,
        time = "10:00",
        title = title,
        catKey = if (race) BlockCategory.RACE else BlockCategory.TOUR,
        place = null,
        desc = "",
        blockType = if (race) BlockType.RACE else BlockType.USER,
        systemManaged = race,
    )

    /** 첫 행이 대회 블록인 하루. 실제 D-day 구성과 같다. */
    private fun day(vararg blocks: ItineraryBlock) = ItineraryDay(
        date = LocalDate.of(2026, 9, 7),
        off = 0,
        label = "D-day",
        dateLabel = "09.07 월",
        note = "",
        blocks = blocks.toList(),
    )

    private fun setContent(day: ItineraryDay, onMove: (Int, Int) -> Unit = { _, _ -> }) {
        compose.setContent {
            EditList(
                day = day,
                openedId = null,
                onOpenedChange = {},
                onRemove = {},
                onMove = onMove,
                onReplace = {},
            )
        }
    }

    /** 그립 노드가 가진 커스텀 액션 이름들. */
    private fun gripActions(title: String): List<String> =
        compose.onNodeWithContentDescription("$title 순서 변경")
            .fetchSemanticsNode()
            .customActionLabels()

    private fun SemanticsNode.customActionLabels(): List<String> =
        customActions().map(CustomAccessibilityAction::label)

    @Test
    fun 첫_행에는_위로_이동을_주지_않는다() {
        // moveBlock 이 범위 밖 인덱스를 조용히 무시해서, 남겨 두면 눌러도 아무 일이 없다
        setContent(day(block("b1", "첫 일정"), block("b2", "둘째 일정"), block("b3", "셋째 일정")))

        assertEquals(listOf("아래로 이동"), gripActions("첫 일정"))
    }

    @Test
    fun 마지막_행에는_아래로_이동을_주지_않는다() {
        setContent(day(block("b1", "첫 일정"), block("b2", "둘째 일정"), block("b3", "셋째 일정")))

        assertEquals(listOf("위로 이동"), gripActions("셋째 일정"))
    }

    @Test
    fun 가운데_행에는_둘_다_준다() {
        setContent(day(block("b1", "첫 일정"), block("b2", "둘째 일정"), block("b3", "셋째 일정")))

        assertEquals(listOf("위로 이동", "아래로 이동"), gripActions("둘째 일정"))
    }

    @Test
    fun 대회_블록에는_그립_자체가_없다() {
        // SPEC §4.10 — RACE 행은 그립·교체·삭제·스와이프를 아예 주지 않는다
        setContent(day(block("race", "마라톤 스타트", race = true), block("b2", "둘째 일정")))

        compose.onNodeWithContentDescription("마라톤 스타트 순서 변경").assertDoesNotExist()
    }

    @Test
    fun 위로_이동을_실행하면_한_칸_앞으로_옮긴다() {
        var moved: Pair<Int, Int>? = null
        setContent(
            day(block("b1", "첫 일정"), block("b2", "둘째 일정"), block("b3", "셋째 일정")),
            onMove = { from, to -> moved = from to to },
        )

        compose.onNodeWithContentDescription("둘째 일정 순서 변경").performCustomAction("위로 이동")

        assertEquals(1 to 0, moved)
    }

    @Test
    fun 아래로_이동을_실행하면_한_칸_뒤로_옮긴다() {
        var moved: Pair<Int, Int>? = null
        setContent(
            day(block("b1", "첫 일정"), block("b2", "둘째 일정"), block("b3", "셋째 일정")),
            onMove = { from, to -> moved = from to to },
        )

        compose.onNodeWithContentDescription("둘째 일정 순서 변경").performCustomAction("아래로 이동")

        assertEquals(1 to 2, moved)
    }

    @Test
    fun 삭제_버튼이_어떤_일정인지_알린다() {
        // 행이 여럿인데 "삭제 버튼" 만 읽어 주면 무엇을 지우는지 알 수 없다
        setContent(day(block("b1", "첫 일정"), block("b2", "둘째 일정")))

        compose.onNodeWithContentDescription("둘째 일정 삭제").assertExists()
    }

    @Test
    fun 대회_블록은_삭제도_노출하지_않는다() {
        setContent(day(block("race", "마라톤 스타트", race = true), block("b2", "둘째 일정")))

        compose.onNodeWithContentDescription("마라톤 스타트 삭제").assertDoesNotExist()
    }
}

/** 노드가 노출하는 커스텀 액션들. 없으면 빈 목록이다. */
private fun SemanticsNode.customActions(): List<CustomAccessibilityAction> =
    config.getOrNull(SemanticsActions.CustomActions).orEmpty()

/** 이름으로 커스텀 액션 하나를 실행한다. 없으면 있는 것들을 함께 알려 준다. */
private fun SemanticsNodeInteraction.performCustomAction(label: String) {
    val actions = fetchSemanticsNode().customActions()
    val action = actions.firstOrNull { it.label == label }
    assertTrue(
        "커스텀 액션 '$label' 이 없다 — 있는 것: ${actions.map(CustomAccessibilityAction::label)}",
        action != null,
    )
    action!!.action()
}
