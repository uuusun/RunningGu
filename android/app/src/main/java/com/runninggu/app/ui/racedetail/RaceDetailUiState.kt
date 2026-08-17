package com.runninggu.app.ui.racedetail

import com.runninggu.app.ui.model.NearbyFestival
import com.runninggu.app.ui.model.RaceSummary

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

    /** 축제 섹션을 그릴 수 있는가 — 대회 좌표가 있어야 서버가 반경 계산을 한다. */
    val showFestivalSection: Boolean
        get() = phase == Phase.LOADED
}
