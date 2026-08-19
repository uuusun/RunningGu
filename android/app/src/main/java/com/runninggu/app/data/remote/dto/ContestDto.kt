package com.runninggu.app.data.remote.dto

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * 대회. (API 명세 §3-1 카드 · §3-3 마감임박 · §3-4 상세)
 *
 * **세 응답의 합집합 하나로 둔다.** 명세가 "마감임박 = 카드 + `dDayApply`",
 * "상세 = 카드 + `organizer, officialUrl, lat, lng, dDay`" 로 카드를 그대로 확장하는 구조라,
 * 클래스를 셋으로 나누면 공통 15개 필드가 세 벌이 되어 계약이 갈라진다.
 *
 * 대신 **어느 응답에서 오는 값인지 주석으로 표시**하고, 매퍼가 용도별로 읽는다.
 * 없는 필드는 서버가 보내지 않으므로 기본값(null)이 된다.
 */
@Serializable
data class ContestDto(
    // ── 카드 공통 (§3-1) ──
    val id: Long,
    val name: String,
    val region: String = "",
    val place: String = "",
    @Contextual val contestDate: LocalDate,
    @Contextual val startTime: LocalTime? = null,
    /** `FULL|HALF|K10|K5`. 빈 배열이면 화면이 "종목 미표기" 배지를 붙인다. */
    val events: List<String> = emptyList(),
    /** 서버가 조회 시점에 파생한 값. 앱은 재계산하되 단정할 수 없을 때 이걸 쓴다(SPEC §5.5). */
    val regStatus: String? = null,
    /** PR #46 에서 목록 응답에 추가됐다 — 오프라인 재계산 근거(이슈 #44). */
    @Contextual val applyStart: LocalDate? = null,
    @Contextual val applyEnd: LocalDate? = null,
    val imageUrl: String? = null,
    /** `MARATHON_ONLINE|MARATHON_GO`. 카드에 "{출처} · 확인 MM.DD" 로 쓴다(A3). */
    val sources: List<String> = emptyList(),
    @Contextual val checkedAt: Instant? = null,
    /** 게스트는 항상 false. */
    val favorite: Boolean = false,

    // ── 마감임박에만 (§3-3) ──
    /** `applyEnd − 오늘` — "마감 D-n" 배지. */
    val dDayApply: Int? = null,

    // ── 상세에만 (§3-4) ──
    val organizer: String? = null,
    val officialUrl: String? = null,
    /** nullable — 좌표가 없으면 지도·인근 축제·동선 생성을 못 쓴다. */
    val lat: Double? = null,
    val lng: Double? = null,
    /** 대회일 − 오늘(KST). 앱도 `domain.dDay()` 로 계산할 수 있다. */
    val dDay: Int? = null,
)

/** 목록 응답. 커서는 불투명하므로 앱이 해석하지 않는다. (§0-4 · §3-1) */
@Serializable
data class ContestListDto(
    val items: List<ContestDto> = emptyList(),
    val nextCursor: String? = null,
    val hasNext: Boolean = false,
)

/** 마감 임박. (§3-3) */
@Serializable
data class ClosingSoonDto(
    val items: List<ContestDto> = emptyList(),
)

/** 월간 점 집계. (§3-2) */
@Serializable
data class DailyCountsDto(
    val counts: List<Entry> = emptyList(),
) {
    @Serializable
    data class Entry(
        @Contextual val date: LocalDate,
        val count: Int = 0,
    )
}
