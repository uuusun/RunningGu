package com.runninggu.app.ui.racedetail

import com.runninggu.app.ui.model.NearbyFestival
import com.runninggu.app.ui.model.RaceSummary
import com.runninggu.app.ui.model.hasLocation

/**
 * S3 대회 상세의 UI 계약. (SPEC §4.6 · §3-5)
 *
 * 대회 정보와 인근 축제는 **서버 호출이 서로 다르다**(API 명세 §3-4 / §3-5).
 * 축제 조회가 실패해도 대회 정보는 정상으로 보여야 하므로 상태를 따로 둔다 —
 * 하나로 묶으면 축제 502 하나에 화면 전체가 오류로 넘어간다.
 */
data class RaceDetailUiState(
    val phase: Phase = Phase.LOADING,
    val race: RaceSummary? = null,
    val errorMessage: String? = null,
    val festivalPhase: Phase = Phase.LOADING,
    val festivals: List<NearbyFestival> = emptyList(),
    val isFavorite: Boolean = false,
) {
    /**
     * [NOT_FOUND]는 `404 CONTEST_NOT_FOUND`(API 명세 §3-4) 전용이다.
     * 재시도해도 소용없으므로 [ERROR]와 달리 [다시 시도]를 주지 않는다.
     */
    enum class Phase { LOADING, LOADED, ERROR, NOT_FOUND }

    /**
     * 축제 섹션을 그릴 수 있는가 — 대회 좌표가 있어야 서버가 반경 계산을 한다.
     *
     * **비활성 대회에서는 그리지 않는다.** 명세가 `GET /contests/{id}/festivals` 를
     * 아예 호출하지 말라고 한다(§3-4 · 결정-46) — 원천이 사라진 대회의 주변 축제를
     * 보여주는 것은 아직 열리는 대회처럼 읽힌다.
     */
    val showFestivalSection: Boolean
        get() = phase == Phase.LOADED && race?.active != false

    /** 원천에서 사라진 대회인가. 안내를 띄우고 동선 CTA 를 막는다. (결정-46) */
    val isInactive: Boolean
        get() = phase == Phase.LOADED && race?.active == false

    /**
     * 동선 만들기를 누를 수 있는가. (결정-46)
     *
     * 비활성 대회는 명세가 CTA 를 막으라고 한다 — 원천이 사라진 대회로 여행 동선을
     * 짜 봐야 날짜·장소가 맞는지 알 수 없다.
     *
     * **좌표가 없는 대회도 막는다** (SPEC §4.6). S6 숙소와 S7 후보가 대회장 좌표로
     * POI 를 조회하므로, 좌표 없이 위저드에 들어가면 조회할 기준이 없다. 좌표 전용
     * 안내 UX 는 P1 이라 P0 에서는 CTA 비활성으로 둔다.
     *
     * canonical id 가 없는 대회(번들·오프라인)도 결국 여기서 막아야 하지만, 지금은
     * 화면이 서버 데이터가 아니라 샘플을 그려서 판단 근거가 없다. AP-14 가 화면을
     * `Contest` 로 옮길 때 `serverId != null` 을 조건에 더한다 (#66 리뷰).
     * 그때까지는 `ResultViewModel` 의 오류 안내가 안전망이다.
     */
    val canStartWizard: Boolean
        get() = phase == Phase.LOADED && race?.active == true && race.hasLocation
}
