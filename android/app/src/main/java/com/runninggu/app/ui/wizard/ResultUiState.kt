package com.runninggu.app.ui.wizard

import com.runninggu.app.data.model.ItineraryResult
import com.runninggu.app.domain.BlockCategory
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.ItineraryDay
import com.runninggu.app.domain.ItineraryEdits
import com.runninggu.app.domain.PoiCategory
import com.runninggu.app.domain.Recovery
import com.runninggu.app.data.model.PoiItem
import com.runninggu.app.ui.map.MapMarker

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
    /**
     * 지금 활성인 블록. 지도 핀과 타임라인 카드가 이 값으로 짝을 맞춘다. (SPEC §4.10 · §3-8)
     *
     * **일자를 고르면 항상 그 일자의 첫 핀이 활성이다**(§4.10 "일자 탭 → … 첫 핀 활성").
     * 생성 직후에도 마찬가지다 — 첫 화면도 "고른 일자" 라서, 강조된 카드가 없는 상태로
     * 시작하지 않는다.
     *
     * null 이 되는 경우는 **그 일자에 좌표 있는 블록이 하나도 없을 때**뿐이다. 그때는 지도
     * 자리에 안내만 뜬다.
     */
    val activeBlockId: String? = null,
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

    /**
     * 활성 일자의 지도 핀. (SPEC §3-8 · §4.10 · AP-03)
     *
     * **좌표가 있는 블록만** 선다. 서버가 외부 POI 조회에 실패하면 `placeName`·`lat`·`lng`
     * 를 null 로 강등하되 생성은 성공시키므로(API 명세 §5-1 · NFR-3), 장소 없는 블록이
     * 정상적으로 섞여 온다. 그 블록은 지도에 세울 자리가 없다.
     *
     * 번호는 [ItineraryEdits.dayPins] 가 매긴 값을 그대로 쓴다 — 그쪽이 SPEC §5.7 의
     * 파생 규칙이다. **타임라인 카드 번호와 같은 값**이라 좌표 없는 블록이 섞이면
     * `1 · 3` 처럼 중간이 빈다 🔒(2026-08-26 · #208 리뷰 합의).
     *
     * 좌표 있는 것만 1부터 다시 매기면 같은 장소가 카드에서 3, 지도에서 2로 보인다.
     * 번호가 건너뛰는 것은 눈에 보이지만 **어긋나는 것은 안 보인다.** S8 러닝코스도
     * 같은 규칙이라(§4.11-4 · #158) 두 화면이 하나로 읽힌다.
     */
    val mapPins: List<MapMarker>
        get() {
            val recovery = isRecoveryDay(activeDayIndex)
            return ItineraryEdits.dayPins(activeDay).map { pin ->
                MapMarker(
                    id = pin.blockId,
                    order = pin.n,
                    lat = pin.lat,
                    lng = pin.lng,
                    // 회복일은 액센트가 파랑 대신 주황이다 (SPEC §3-8 범례)
                    recovery = recovery,
                )
            }
        }

    /**
     * 지금 보고 있는 핀. 카메라가 여기로 따라간다. (SPEC §3-8)
     *
     * 핀 id 가 곧 블록 id 라 타임라인 카드와 짝을 맞출 수 있다. 일자를 옮기면 그 블록이
     * 이 일자에 없으므로 **null 로 떨어진다** — 남겨 두면 카메라가 어제 자리로 간다.
     */
    val activePinId: String?
        get() = activeBlockId?.takeIf { id -> mapPins.any { it.id == id } }
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
