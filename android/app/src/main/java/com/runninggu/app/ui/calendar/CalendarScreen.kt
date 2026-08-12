package com.runninggu.app.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.runninggu.app.ui.common.PlaceholderScreen

/**
 * S2 캘린더 — 리스트·캘린더 뷰·검색·필터 모달 F1. (SPEC §4.5 / AP-10)
 *
 * [initialQuery]는 홈에서 검색 실행 시 넘어온 검색어다. 목록 필터의 초기값으로 쓴다.
 */
@Composable
fun CalendarScreen(
    initialQuery: String = "",
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = "캘린더",
        description = if (initialQuery.isBlank()) {
            "S2 · AP-10"
        } else {
            "S2 · AP-10 — 검색어 \"$initialQuery\""
        },
        modifier = modifier,
    )
}
