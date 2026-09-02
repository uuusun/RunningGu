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
    /** 저장 CTA 의 진행·결과. (SPEC §4.10 · §5-2) */
    val save: SaveItineraryState = SaveItineraryState.Idle,
    /**
     * 저장된 동선을 되살린 것이면 그 id. 생성 경로에서는 null 이다. (§5-5 · #213)
     *
     * 화면 모양은 두 경로가 같지만 **무엇으로 채웠는지**는 알아야 한다 — 되살린
     * 동선에는 "대회가 바뀌었다" 안내가 붙을 수 있다.
     */
    val restoredItineraryId: Long? = null,
    /**
     * 저장한 뒤 대회가 바뀌었다. (§5-3 · §5-5)
     *
     * **일정 표시는 저장 시점 snapshot 그대로 둔다.** 최신 대회로 갈아 끼우면 사용자가
     * 저장해 둔 것과 다른 것을 보게 된다 — 바뀐 사실만 알리고 다시 만들지는 사용자가
     * 정한다.
     */
    val needsRegeneration: Boolean = false,
) {
    /**
     * 이 화면이 편집·저장을 여는가. **복원(S7-R)은 P0 에서 닫는다.** (이슈 #213)
     *
     * 저장된 동선을 고치는 것을 `POST /api/itineraries` 로 통째 저장하면 안 된다 —
     * 서버가 저장할 때마다 **현재 canonical 대회로 RACE 블록을 재구성**하기 때문이다(§5-2).
     * USER 장소 하나만 고쳐도 저장 snapshot 의 대회 정보가 말없이 바뀌고, 대회 날짜가
     * 여행 기간 밖으로 옮겨졌으면 편집 저장 자체가 `INVALID_TRAVEL_PERIOD` 로 실패한다.
     *
     * 저장 후 편집은 §5-7~5-10 블록 API 로 가야 한다 — 저장 snapshot 의 RACE 를 지키면서
     * USER 블록만 고치려고 둔 계약이다. 그건 연산 4개마다 낙관적 갱신·롤백 규칙을 정해야
     * 하는 별도 작업이라 이 PR 에 담지 않았다.
     *
     * **화면에 리터럴로 두지 않고 상태에 둔 이유가 테스트다.** Composable 안의 `false` 는
     * 단위 테스트가 못 본다 — 여기 있으면 규칙이 깨졌을 때 테스트가 잡는다.
     *
     * 블록마다 따로 있는 `ItineraryEdits.canEdit`(대회 블록은 못 고친다)와 다른 층이다.
     * 이건 **화면 전체**가 편집을 여는가다.
     */
    val editingEnabled: Boolean get() = restoredItineraryId == null

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

    /**
     * 저장 CTA 를 누를 수 있는가. (SPEC §4.10 · §5-2)
     *
     * **편집 중에도 누를 수 있다.** §4.10 이 편집을 마치라고 요구하지 않고, [편집] 은
     * 목록 모양만 바꾼다 — 저장되는 내용은 어느 모드에서나 같다.
     */
    val canSave: Boolean
        get() = phase == Phase.CONTENT && save !is SaveItineraryState.Saving
}

/**
 * 저장 CTA 의 상태. (SPEC §4.10 · §5-2 · 매핑표 "새 동선 저장")
 *
 * **S8 의 `SaveCourseState` 와 성공 쪽이 다르다.** 코스 저장은 그 자리에 머무르며 문구
 * 한 줄로 끝나지만, 동선 저장은 §4.10 이 **마이[동선]으로 옮기라**고 못 박고 있다. 화면을
 * 떠나므로 성공 문구를 이 화면에 그릴 자리가 없다 — 그래서 [Saved] 가 문구를 들고 나가고
 * 마이가 스낵바로 띄운다.
 *
 * 실패는 반대다. 화면에 남아 다시 누를 수 있어야 하므로 [Failed] 문구를 버튼 아래 둔다.
 */
sealed interface SaveItineraryState {

    /** 아직 안 눌렀거나, 저장한 뒤 내용을 고쳐 이전 결과를 지웠다. */
    data object Idle : SaveItineraryState

    /** 보내는 중. 버튼을 막아 연타로 같은 동선이 두 번 나가지 않게 한다. */
    data object Saving : SaveItineraryState

    /**
     * 게스트가 눌렀다 — 로그인 유도 **모달**을 띄운다.
     * (`docs/screen-api-matrix.md` S7 "새 동선 저장 … 게스트 modal")
     *
     * 로그인하고 돌아와도 **저장이 저절로 일어나지 않는다**(D-27). S8 과 같은 규칙이다.
     */
    data object NeedsLogin : SaveItineraryState

    /**
     * 저장됐다. 화면은 이 [message] 를 들고 마이[동선]으로 옮긴다. (SPEC §4.10)
     *
     * @param replaced 같은 `(대회, 시작일, 종료일)` 동선이 있어 **교체**됐는가(§5-2).
     *  새로 담은 것과 덮어쓴 것은 사용자에게 다른 일이라 문구를 가른다
     */
    data class Saved(val id: Long, val replaced: Boolean, val message: String) : SaveItineraryState

    /** 실패했다. [message] 를 버튼 아래 한 줄로 그리고 화면에 남는다. */
    data class Failed(val message: String) : SaveItineraryState
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
