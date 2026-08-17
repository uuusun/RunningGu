package com.runninggu.app.ui.wizard

import com.runninggu.app.ui.model.RaceSummary
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 일정 패턴. (SPEC §5.2 `PATTERNS`)
 *
 * [offsetStart]·[offsetEnd]는 대회일 기준 오프셋이다 — 규칙 값은 명세가 기준이므로 바꾸지 않는다.
 * [CUSTOM]만 오프셋이 없고 사용자가 미니 캘린더로 직접 고른다.
 */
enum class TripPattern(
    val label: String,
    val hint: String,
    val offsetStart: Long?,
    val offsetEnd: Long?,
) {
    PRE("전날부터", "1박 2일", -1, 0),
    POST("대회+다음날", "1박 2일", 0, 1),
    AROUND("전후로", "2박 3일", -1, 1),
    DAY("당일치기", "", 0, 0),
    CUSTOM("직접 선택", "", null, null),
}

/**
 * 위저드(S4~S7) 공유 상태. (SPEC §2.4)
 *
 * 화면마다 ViewModel을 따로 두면 뒤로 갔다 오는 사이 선택이 날아가므로,
 * wizard 내비게이션 그래프 하나에 ViewModel을 묶어 S4~S7이 같은 객체를 본다.
 *
 * 지금은 S4가 쓰는 값만 있다. S5~S7에서 event·themes·stay·days·recovery 등을
 * 이 클래스에 이어 붙인다 (SPEC §2.4 위저드 공유 상태 목록).
 */
data class WizardUiState(
    val race: RaceSummary? = null,
    val pattern: TripPattern = TripPattern.AROUND, // 기본 "전후로" (SPEC §4.7)
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    /** 직접 선택에서 시작일만 고른 상태. 안내 문구와 다음 탭 처리를 가른다. (SPEC §4.7) */
    val awaitingEndDate: Boolean = false,
) {
    /** 기간 일수. 당일치기는 1. */
    val dayCount: Int
        get() = if (start != null && end != null) {
            (ChronoUnit.DAYS.between(start, end) + 1).toInt()
        } else {
            0
        }

    /** 선택한 날짜가 기간 안에 드는가 — 미니 캘린더 하이라이트에 쓴다. */
    fun isInRange(date: LocalDate): Boolean =
        start != null && end != null && !date.isBefore(start) && !date.isAfter(end)

    /** 다음 단계로 갈 수 있는가. 직접 선택에서 종료일을 안 골랐으면 막는다. */
    val canProceed: Boolean
        get() = start != null && end != null && !awaitingEndDate
}
