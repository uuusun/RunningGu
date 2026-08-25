package com.runninggu.app.ui.wizard

import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.PoiCategory
import com.runninggu.app.domain.Recovery
import com.runninggu.app.domain.TripPattern
import com.runninggu.app.domain.stdEvents
import com.runninggu.app.ui.model.RaceSummary
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import com.runninggu.app.data.model.PoiItem

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
    /**
     * 대회 조회 상태. **위저드 전체의 상태다.** (API 명세 §3-4 · SPEC §3-5 · 이슈 #140)
     *
     * S4~S7 이 전부 이 대회 위에 서므로, 못 불러오면 위저드는 통째로 못 쓴다. 그래서
     * 섹션 단위 `SectionState` 가 아니라 화면 전체 `phase` 자리다.
     *
     * 이름에 `contest` 를 붙인 이유 — 이 클래스는 S4~S7 공유 상태라 그냥 `phase` 면
     * "위저드가 몇 단계인가" 로 읽힌다(#140 리뷰). 실제로는 대회 조회 상태 하나다.
     *
     * **예전에는 `race == null` 이 곧 로딩이었다.** 동기 조회라 스쳐 지나갔지만 서버로
     * 바꾸면 조회 실패도 `race == null` 이라 "불러오는 중…" 이 영영 돈다.
     */
    val contestPhase: Phase = Phase.LOADING,
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
    /** [Phase.ERROR] 일 때 보여줄 문구. 서버가 준 말이 있으면 그걸 쓴다. */
    val errorMessage: String? = null,
    /**
     * **사용자가 S4 를 지나왔는가.** (#192 리뷰)
     *
     * 프로세스가 죽으면 이 ViewModel 도 사라지고, 되살아난 것은 **대회 하나뿐**이다 —
     * 날짜·종목·취향·숙소는 전부 기본값으로 돌아온다. 그런데 시스템은 사용자가 있던
     * **S6·S7 로 복원**하므로, 그대로 두면 S7 이 **사용자가 고른 적 없는 조건으로 동선을
     * 만든다.** 화면에는 정상으로 보이고 사용자는 자기가 고른 것과 다른 결과를 받는다.
     *
     * 조용히 틀린 결과를 주느니 **다시 고르게 한다.** 이 값이 false 인 채 S5~S7 이
     * 열렸다면 그 상태는 사용자의 것이 아니므로 S4 로 되돌린다.
     *
     * 입력 전체를 저장해 되살리는 쪽이 더 낫지만(사용자가 다시 안 골라도 된다) 지금 구조로는
     * `PoiItem` 까지 담아야 해서 범위가 커진다. 그건 별도 작업으로 둔다.
     */
    val planConfirmed: Boolean = false,
) {
    /**
     * [NOT_FOUND] 는 `404 CONTEST_NOT_FOUND` 와 **canonical id 가 없는 대회** 전용이다.
     * (API 명세 §3-4 · S3 [com.runninggu.app.ui.racedetail.RaceDetailUiState.Phase] 와 같은 기준)
     *
     * 가르는 기준은 **재시도가 소용있는가** 하나다 — 없는 대회는 다시 눌러도 생기지
     * 않으므로 [ERROR] 와 달리 [다시 시도] 를 주지 않는다.
     */
    enum class Phase { LOADING, LOADED, ERROR, NOT_FOUND }

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
