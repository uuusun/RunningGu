package com.runninggu.app.ui.my

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.runninggu.app.data.model.SavedItinerary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 동선 삭제는 **확인을 거쳐야** 서버로 나간다. (매핑표 S10 · #181 리뷰)
 *
 * 동선은 되돌릴 수 없는 사용자 데이터인데 휴지통이 곧바로 `DELETE` 를 쏘고 있었다.
 * **"요청이 나가지 않는다" 는 화면을 그려야만 확인된다** — 상태가 Compose 안에 있어서
 * 단위 테스트로는 닿지 않는다. `EditList` 를 계측 테스트로 그린 것과 같은 이유다(#71).
 */
class ItineraryDeleteConfirmTest {

    @get:Rule
    val compose = createComposeRule()

    private val deleted = mutableListOf<String>()

    private fun setContent() {
        deleted.clear()
        compose.setContent {
            ItineraryList(
                state = SavedItinerariesState.Content(
                    itineraries = listOf(itinerary("it_1", "서울 2박 3일")),
                    hasNext = false,
                    totalElements = 1,
                ),
                onDelete = { deleted += it },
                onBrowseRaces = {},
                onRetry = {},
                onLoadMore = {},
            )
        }
    }

    private fun itinerary(id: String, title: String) = SavedItinerary(
        id = id,
        title = title,
        raceName = "서울 마라톤",
        event = "하프",
        recoveryLabel = null,
        period = "09.05~09.07",
        placeCount = 8,
        needsRegeneration = false,
        active = true,
    )

    @Test
    fun 휴지통만_눌러서는_삭제가_나가지_않는다() {
        setContent()

        compose.onNodeWithContentDescription("삭제").performClick()

        compose.onNodeWithText("저장한 동선을 지울까요?").assertIsDisplayed()
        assertEquals("확인 전에 요청이 나갔다", emptyList<String>(), deleted)
    }

    @Test
    fun 확인을_눌러야_삭제가_나간다() {
        setContent()

        compose.onNodeWithContentDescription("삭제").performClick()
        compose.onNodeWithText("삭제").performClick()

        assertEquals(listOf("it_1"), deleted)
    }

    @Test
    fun 취소하면_삭제가_나가지_않는다() {
        setContent()

        compose.onNodeWithContentDescription("삭제").performClick()
        compose.onNodeWithText("취소").performClick()

        assertEquals("취소했는데 요청이 나갔다", emptyList<String>(), deleted)
        // 모달이 닫혀야 카드를 다시 볼 수 있다
        compose.onNodeWithText("서울 2박 3일").assertIsDisplayed()
    }
}
