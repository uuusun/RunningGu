package com.runninggu.app.ui.home

import com.runninggu.app.ui.common.DataOrigin
import com.runninggu.app.ui.common.SectionState
import com.runninggu.app.ui.common.cachedAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * 캐시 출처가 화면까지 오는가. (매핑표 171행 · #276)
 *
 * ## 망가뜨리면 이것만 실패한다
 *
 * `SectionState.cachedAt` 확장이 `Content` 가 아닌 상태에서도 값을 주게 하면
 * `오류 상태는 캐시로 그린 것이 아니다` 만 실패한다.
 *
 * **화면이 이 값을 실제로 그리는지는 여기서 못 잡는다.** Compose 자리라 단위
 * 테스트가 닿지 않는다 — 기기에서 본 것을 PR 에 적었다(#257 리뷰에서 같은
 * 자리를 잘못 적어 선경님이 잡아 주셨다).
 */
class CachedNoticeTextTest {

    private val at: Instant = Instant.parse("2026-09-04T06:30:00Z")

    @Test
    fun `캐시에서 온 영역은 저장 시각을 준다`() {
        val state = SectionState.Content(listOf("a"), origin = DataOrigin.LocalCache(at))
        assertEquals(at, state.cachedAt)
    }

    @Test
    fun `서버에서 온 영역은 시각이 없다`() {
        assertNull(SectionState.Content(listOf("a")).cachedAt)
    }

    // 오류·로딩에는 그릴 내용 자체가 없다. 여기서 시각이 나오면 [다시 시도] 위에
    // "오프라인 · … 기준" 이 붙어 무엇을 다시 시도하는지 흐려진다.
    //
    // **`Empty` 는 여기서 뺐다.** 처음에 오류와 같이 묶어 "캐시로 그린 것이 아니다" 라고
    // 단언했는데 틀렸다 — 캐시에서 되살린 0건도 있다(#283 리뷰 · 선경님). 아래에서 가른다.
    @Test
    fun `오류와 로딩은 캐시로 그린 것이 아니다`() {
        assertNull(SectionState.Error(message = null).cachedAt)
        assertNull(SectionState.Loading.cachedAt)
    }

    // 접수 종료 필터가 다 걸러내면 바로 이 상태다. 시각이 안 오면 화면이 영역을 접어서
    // "마지막 성공 결과가 비어 있다" 와 "방금 서버가 0건을 줬다" 가 구별되지 않는다.
    @Test
    fun `캐시에서 되살린 0건은 저장 시각을 준다`() {
        assertEquals(at, SectionState.Empty(origin = DataOrigin.LocalCache(at)).cachedAt)
    }

    @Test
    fun `서버가 준 0건은 시각이 없다`() {
        assertNull(SectionState.Empty().cachedAt)
    }

    // 저장은 UTC, 표시는 KST 다 (§6.6). 09시 차이라 날짜가 넘어가는 경우가 있다.
    @Test
    fun `UTC 저장 시각을 KST 로 옮겨 적는다`() {
        assertEquals("09.04 15:30", cachedAtLabel(at))
        assertEquals("09.05 00:10", cachedAtLabel(Instant.parse("2026-09-04T15:10:00Z")))
    }
}
