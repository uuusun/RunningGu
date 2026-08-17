package com.runninggu.app.ui.wizard

import com.runninggu.app.domain.Itinerary
import com.runninggu.app.domain.ItineraryDay
import com.runninggu.app.domain.ItineraryEdits
import com.runninggu.app.domain.Recovery

/**
 * S7 동선 결과의 UI 계약. (SPEC §4.10 · §3-5)
 *
 * 생성은 실패할 수 있고 성공해도 0건일 수 있어서, 네 상태를 [Phase]로 구분한다 —
 * §4.10이 빈 상태("동선이 아직 없어요" + 조건 바꾸기)와 오류 상태(다시 시도)를 따로 정의한다.
 */
data class ResultUiState(
    val phase: Phase = Phase.LOADING,
    val itinerary: Itinerary? = null,
    /** 일자 탭에서 고른 일자. 지도와 타임라인이 이 값을 본다. (SPEC §4.10) */
    val activeDayIndex: Int = 0,
    val errorMessage: String? = null,
) {
    enum class Phase { LOADING, CONTENT, EMPTY, ERROR }

    val days: List<ItineraryDay>
        get() = itinerary?.days.orEmpty()

    val activeDay: ItineraryDay?
        get() = days.getOrNull(activeDayIndex)

    /** "{지역} 2박 3일" — 요약 줄 왼쪽. (SPEC §4.10) */
    val title: String
        get() {
            val plan = itinerary?.plan ?: return ""
            val nights = days.size - 1
            val period = if (nights <= 0) "당일치기" else "${nights}박 ${days.size}일"
            return "${plan.race.region} $period".trim()
        }

    /** "{n}곳" — 장소가 붙은 블록 수. 대회장·숙소도 센다. (SPEC §4.10 · §5.7) */
    val placeCount: Int
        get() = ItineraryEdits.countPlaces(days)

    /**
     * S8 연계 카드에 넘길 목표 거리(km). (SPEC §4.10 · §5.1)
     *
     * 산책 블록이 빠지면서 `RECOVERY.walk`의 용도가 이걸로 바뀌었다 — 대조표 A3.
     */
    val courseTargetKm: Double
        get() = itinerary?.plan?.event?.let { Recovery.defaultCourseTargetKm(it) } ?: 0.0

    /** 회복일인가. 일자 탭과 지도 핀 색을 가른다. (SPEC §4.10) */
    fun isRecoveryDay(day: ItineraryDay): Boolean =
        itinerary?.recovery != null && day.off > 0
}
