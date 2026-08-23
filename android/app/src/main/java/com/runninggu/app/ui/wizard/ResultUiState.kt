package com.runninggu.app.ui.wizard

import com.runninggu.app.data.model.ItineraryResult
import com.runninggu.app.domain.BlockCategory
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.ItineraryDay
import com.runninggu.app.domain.ItineraryEdits
import com.runninggu.app.domain.PoiCategory
import com.runninggu.app.domain.Recovery
import com.runninggu.app.data.model.PoiItem

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
    /** 후보 시트. null 이면 닫힌 상태다. (SPEC §4.10) */
    val sheet: CandidateSheetState? = null,
    val errorMessage: String? = null,
    /**
     * 오류에서 [다시 시도] 를 줄 것인가. (SPEC §4.10 · §3-5)
     *
     * **다시 눌러도 소용없는 오류가 있다** — `409 CONTEST_INACTIVE` 는 원천에서 사라진
     * 대회라 재시도로 살아나지 않는다. 버튼을 주면 헛돌고, 사용자는 뭘 더 해야 하는지
     * 모른 채 계속 누른다.
     */
    val canRetry: Boolean = true,
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

/**
 * 후보 시트(교체·추가)의 UI 계약. (SPEC §4.10)
 *
 * - 교체: [replaceBlockId] 블록의 장소·설명·카테고리를 바꾼다. 블록 id·시간은 유지된다.
 * - 추가: 카테고리 칩(취향 6종+숙소)으로 조회를 바꿔 가며 고르고, 13:00 새 블록으로 맨 끝에 붙는다.
 */
data class CandidateSheetState(
    /** null 이면 추가 모드, 값이 있으면 그 블록의 장소 교체 모드다. */
    val replaceBlockId: String? = null,
    val category: PoiCategory = PoiCategory.TOUR,
    val phase: Phase = Phase.LOADING,
    val items: List<PoiItem> = emptyList(),
    /** `LIVE` · `SAMPLE` · `SYNTH` 소스 배지. (NFR-2) */
    val source: String = "",
) {
    enum class Phase { LOADING, CONTENT, EMPTY, ERROR }

    val isReplace: Boolean get() = replaceBlockId != null

    /** "{카테고리} {교체|추가} · 인근" — 시트 헤더. (SPEC §4.10) */
    val title: String
        get() = "${category.label} ${if (isReplace) "교체" else "추가"} · 인근"
}

/**
 * 표시 분류 → 조회 카테고리. [BlockCategory.of] 의 역방향이다.
 *
 * 대회·회복 블록은 조회 카테고리가 없어 null — 화면은 이 값으로 교체 버튼을 숨기고,
 * ViewModel 은 그래도 들어온 요청을 거부한다.
 */
fun BlockCategory.toPoiCategoryOrNull(): PoiCategory? =
    PoiCategory.entries.firstOrNull { BlockCategory.of(it) == this }
