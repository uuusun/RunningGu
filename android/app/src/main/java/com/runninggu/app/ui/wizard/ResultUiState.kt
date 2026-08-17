package com.runninggu.app.ui.wizard

import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.ItineraryDay
import com.runninggu.app.domain.ItineraryEdits
import com.runninggu.app.domain.Recovery

/**
 * S7 동선 결과의 UI 계약. (SPEC §4.10 · §3-5)
 *
 * 동선은 서버가 만든다(결정-41). 이 상태는 `POST /itineraries/generate` 응답을 담고,
 * 저장 전 로컬 편집(§5.7)의 대상이 된다.
 *
 * 생성은 실패할 수 있고 성공해도 0건일 수 있어서 네 상태를 구분한다 — §4.10이 빈 상태
 * ("동선이 아직 없어요" + 조건 바꾸기)와 오류 상태(다시 시도)를 따로 정의한다.
 */
data class ResultUiState(
    val phase: Phase = Phase.LOADING,
    val result: ItineraryResult? = null,
    /** 목표 거리 계산에 쓴다. 요청에 실어 보낸 값을 그대로 들고 있는다. */
    val event: EventType = EventType.HALF,
    /** 지역 이름. 서버 `title`과 합쳐 "{지역} 2박 3일"이 된다. (SPEC §4.10) */
    val region: String = "",
    /** 일자 탭에서 고른 일자. 지도와 타임라인이 이 값을 본다. (SPEC §4.10) */
    val activeDayIndex: Int = 0,
    /** 편집 모드. [편집]↔[완료]로 오간다. (SPEC §4.10) */
    val isEditing: Boolean = false,
    val errorMessage: String? = null,
) {
    enum class Phase { LOADING, CONTENT, EMPTY, ERROR }

    val days: List<ItineraryDay>
        get() = result?.days.orEmpty()

    val activeDay: ItineraryDay?
        get() = days.getOrNull(activeDayIndex)

    /** "{지역} 2박 3일" — 요약 줄 왼쪽. (SPEC §4.10) */
    val title: String
        get() = listOf(region, result?.title.orEmpty()).filter { it.isNotEmpty() }.joinToString(" ")

    /** "{n}곳" — 장소가 붙은 블록 수. 대회장·숙소도 센다. (SPEC §4.10 · §5.7) */
    val placeCount: Int
        get() = ItineraryEdits.countPlaces(days)

    /**
     * S8 연계 카드에 넘길 목표 거리(km). (SPEC §4.10 · §5.1)
     *
     * 산책 블록이 빠지면서 `RECOVERY.walk`의 용도가 이걸로 바뀌었다 — 대조표 A3.
     * 동선 생성이 아니라 **화면 간 전달값**이라 앱이 계산해도 된다.
     */
    val courseTargetKm: Double
        get() = Recovery.defaultCourseTargetKm(event)

    /**
     * 회복일인가. 일자 탭과 지도 핀 색을 가른다. (SPEC §4.10)
     *
     * 서버가 day마다 `recovery` 플래그를 내려준다(API 명세 §5-1) — 앱이 다시 판정하지 않는다.
     */
    fun isRecoveryDay(index: Int): Boolean =
        result?.days?.getOrNull(index) != null && recoveryFlags.getOrElse(index) { false }

    /** 서버가 준 일자별 회복 플래그. */
    val recoveryFlags: List<Boolean>
        get() = result?.recoveryFlags.orEmpty()
}
