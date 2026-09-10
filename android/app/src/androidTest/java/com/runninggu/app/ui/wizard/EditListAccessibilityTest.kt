package com.runninggu.app.ui.wizard

import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
 * **S7 저장 동선 편집 화면의 계측을 여기 모은다.** 화면 하나에 클래스 하나다.
 *
 * 이름은 처음 목적(접근성)을 그대로 두었다. **클래스를 늘리지 않는 것이 규칙의 뜻**이라
 * 여기 얹는다 — AGENTS 3장이 클래스를 세는 이유는 *"어느 화면을 기기에 올려 봐야 하나"*
 * 이고, 이 화면은 이미 여기서 뜬다(#315 · 민지님).
 *
 * ## ① 순서 변경을 접근성 서비스로도 할 수 있는지 (이슈 #71)
 *
 * #51 에서 위/아래 버튼을 그립 롱프레스 드래그로 바꾸면서, TalkBack 사용자는 순서를
 * 바꿀 방법이 사라졌었다 — 드래그는 손가락으로만 되는 동작이다. 커스텀 액션이 그 자리를
 * 대신하는데, **접근성 회귀는 화면만 봐서는 보이지 않는다.** 그래서 여기서 고정한다.
 *
 * ## ② 편집 안내의 배치 (이슈 #315)
 *
 * 진행·실패 안내는 `isEditing` **밖**에 있어야 한다. `[완료]` 를 누른 뒤 도착한 실패가
 * 안 보이면 **서버에는 안 갔는데 사용자는 됐다고 믿는다**(#311 리뷰 · 선경님).
 *
 * **단위 테스트가 이 배치를 못 지킨다.** 안으로 도로 넣어도 상태는 그대로라 VM 테스트가
 * 볼 것이 없다 — 실제로 되돌려도 전부 통과했다. 그래서 여기서 그린다.
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
                onTimeChange = { _, _ -> },
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

    // ── ② 편집 안내의 배치 (#315) ────────────────────────────────────────────

    /** `savedEditSection` 만 `LazyColumn` 에 넣어 그린다. ViewModel 을 세우지 않는다. */
    private fun setSection(state: ResultUiState) {
        compose.setContent {
            LazyColumn {
                savedEditSection(
                    state = state,
                    day = day(block("b1", "첫째 일정"), block("b2", "둘째 일정")),
                    openedBlockId = null,
                    onOpenedChange = {},
                    onRemoveBlock = {},
                    onMoveBlock = { _, _ -> },
                    onReplaceBlock = {},
                    onAddPlace = {},
                    onTimeChange = { _, _ -> },
                )
            }
        }
    }

    /**
     * **`[완료]` 를 누른 뒤 도착한 실패가 보여야 한다.**
     *
     * `isEditing = false` 인데 `editError` 가 있는 상태 — 편집을 끝냈지만 요청은 아직
     * 돌고 있었고 그 뒤 실패한 경우다. 안내를 `isEditing` 안으로 되돌리면 여기서 사라진다.
     */
    @Test
    fun 편집을_끝낸_뒤에도_실패가_보인다() {
        setSection(ResultUiState(isEditing = false, editError = "네트워크에 연결할 수 없어요."))

        compose.onNodeWithText("네트워크에 연결할 수 없어요.").assertExists()
    }

    /** 왕복 중에도 마찬가지다 — `[완료]` 를 눌러도 진행 안내가 남는다. */
    @Test
    fun 편집을_끝낸_뒤에도_진행_안내가_보인다() {
        setSection(ResultUiState(isEditing = false, editInFlight = true))

        compose.onNodeWithText("고치는 중이에요…").assertExists()
    }

    /**
     * **안내가 편집 목록보다 위다.** 순서가 뒤집히면 고치려던 행을 스크롤해야 실패를 본다.
     *
     * `EditNotice` 는 편집 목록의 첫 줄이라 그 위에 안내가 와야 한다.
     */
    @Test
    fun 안내는_편집_목록보다_위에_온다() {
        setSection(ResultUiState(isEditing = true, editError = "동선이 그 사이 바뀌었어요. 다시 열어 주세요."))

        val notice = compose.onNodeWithText("동선이 그 사이 바뀌었어요. 다시 열어 주세요.")
            .fetchSemanticsNode().positionInRoot.y
        val editList = compose.onNodeWithText("첫째 일정").fetchSemanticsNode().positionInRoot.y
        assertTrue("안내가 편집 목록보다 아래에 있다", notice < editList)
    }

    /** 안내가 없을 때는 아무것도 안 그린다 — 빈 자리가 남으면 목록이 밀린다. */
    @Test
    fun 고칠_것이_없으면_안내가_없다() {
        setSection(ResultUiState(isEditing = true))

        compose.onNodeWithText("고치는 중이에요…").assertDoesNotExist()
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
