package com.runninggu.app.ui.wizard

import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.PoiCategory
import com.runninggu.app.domain.Recovery
import com.runninggu.app.domain.TripPattern
import com.runninggu.app.domain.stdEvents
import com.runninggu.app.ui.model.RaceSummary
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
    val pattern: TripPattern = TripPattern.DEFAULT, // 기본 "전후로" (SPEC §4.7 · §5.2)
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    /** 직접 선택에서 시작일만 고른 상태. 안내 문구와 다음 탭 처리를 가른다. (SPEC §4.7) */
    val awaitingEndDate: Boolean = false,
    /**
     * S5 종목(단일). 세그먼트가 4종을 상시 노출하므로 미선택 상태는 없다. (SPEC §4.8)
     *
     * 화면 진입 시 [WizardViewModel.start] 가 "이전 선택 → 하프 우선 → 첫 종목" 으로 채운다.
     */
    val event: EventType = EventType.HALF,
    /** S5 여행 취향(복수). 0개면 CTA 를 막는다. (SPEC §4.8) */
    val themes: List<PoiCategory> = PoiCategory.DEFAULT_THEMES,
    /**
     * S6 숙소. **선택 사항**이라 null 이 정상이다 — 건너뛰면 서버가 대회장 중심으로
     * 슬롯을 채운다. (SPEC §4.9)
     */
    val stay: PoiItem? = null,
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

    // ── S5 종목·취향 (SPEC §4.8) ────────────────────────────────

    /** 이 대회가 실제로 여는 종목. 세그먼트 힌트 문구를 가른다. */
    val raceEvents: List<EventType>
        get() = stdEvents(race?.eventTypes)

    /** 선택한 종목을 대회가 여는가. 아니면 "이 대회 종목엔 없지만" 힌트를 띄운다. */
    val isEventInRace: Boolean
        get() = event in raceEvents

    /** "종목 · 회복강도 {intensity}" 라벨에 쓴다. 종목을 바꾸면 즉시 따라간다. */
    val intensity: String
        get() = Recovery[event].intensity

    /** 하프·풀만 회복 안내를 띄운다. (SPEC §4.8 · §5.1 noHard) */
    val showsRecoveryNotice: Boolean
        get() = Recovery[event].noHard

    /** S5 에서 다음으로 갈 수 있는가. 취향을 하나도 안 고르면 막는다. (SPEC §4.8) */
    val canProceedFromPrefs: Boolean
        get() = themes.isNotEmpty()
}
