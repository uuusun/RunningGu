package com.runninggu.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFestivalFormatTest {

    @Test
    fun `지역이 있으면 기간과 함께 표시한다`() {
        assertEquals("08.21~08.23 · 서울", festivalPeriodAndRegion("08.21~08.23", "서울"))
    }

    @Test
    fun `지역이 비어 있으면 구분점 없이 기간만 표시한다`() {
        assertEquals("08.21~08.23", festivalPeriodAndRegion("08.21~08.23", ""))
        assertEquals("08.21~08.23", festivalPeriodAndRegion("08.21~08.23", "   "))
    }
}
