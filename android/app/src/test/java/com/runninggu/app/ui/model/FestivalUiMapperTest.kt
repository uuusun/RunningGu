package com.runninggu.app.ui.model

import com.runninggu.app.data.model.Festival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * 홈 축제 카드가 무엇을 들고 화면까지 가는가. (API 명세 §4-1 · #247)
 *
 * **`imageUrl` 이 여기서 끊겨 있었다.** 서버도 주고 `data/model/Festival` 도 들고 있는데
 * UI 모델에서 버려져, 축제 카드에 이미지를 넣으려면 서버부터 다시 봐야 하는 줄 알았다.
 * 이 파일이 그 연결을 고정한다.
 */
class FestivalUiMapperTest {

    private fun festival(
        imageUrl: String? = "http://tong.visitkorea.or.kr/cms/a.jpg",
        start: LocalDate? = LocalDate.of(2026, 8, 1),
        end: LocalDate? = LocalDate.of(2026, 8, 9),
    ) = Festival(
        contentId = "2764321",
        name = "부산 바다축제",
        startDate = start,
        endDate = end,
        region = "부산",
        imageUrl = imageUrl,
        inProgress = true,
    )

    @Test
    fun `이미지 주소를 화면까지 그대로 넘긴다`() {
        // **앱이 스킴을 고치거나 크기를 붙이지 않는다.** 원천 URL 을 손대기 시작하면
        // 이미지가 안 뜰 때 서버가 잘못 준 것인지 앱이 망가뜨린 것인지 못 가른다
        val summary = festival().toFestivalSummary()

        assertEquals("http://tong.visitkorea.or.kr/cms/a.jpg", summary.imageUrl)
    }

    @Test
    fun `이미지가 없으면 null 이다`() {
        // 명세 §4-1 이 nullable 로 두고 "없으면 앱이 기본 placeholder 를 표시한다" 고 했다.
        // 빈 문자열은 `FestivalMapper` 가 이미 null 로 정리해서 여기까지 오지 않는다
        assertNull(festival(imageUrl = null).toFestivalSummary().imageUrl)
    }

    @Test
    fun `나머지 필드도 그대로 옮긴다`() {
        val summary = festival().toFestivalSummary()

        assertEquals("2764321", summary.id)
        assertEquals("부산 바다축제", summary.name)
        assertEquals("부산", summary.region)
        assertEquals(true, summary.isOngoing)
    }

    @Test
    fun `기간은 한쪽만 있어도 빈 문자열을 주지 않는다`() {
        // 화면이 "{기간} · {지역}" 으로 이어 붙이므로 빈 값이 오면 " · 부산" 이 된다
        assertEquals("08.01~08.09", festival().toFestivalSummary().period)
        assertEquals("08.01~", festival(end = null).toFestivalSummary().period)
        assertEquals("~08.09", festival(start = null).toFestivalSummary().period)
        assertEquals(
            "기간 미정",
            festival(start = null, end = null).toFestivalSummary().period,
        )
    }
}
