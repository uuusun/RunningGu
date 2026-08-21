package com.runninggu.app.ui.home

import com.runninggu.app.ui.common.SectionState
import com.runninggu.app.ui.common.valueOrNull
import com.runninggu.app.ui.model.FestivalSummary
import com.runninggu.app.ui.model.RaceSummary

/**
 * S1 홈의 UI 계약. (SPEC §4.4 · AGENTS 2장-5)
 *
 * **영역마다 상태를 따로 갖는다.** 마감 임박과 축제는 서로 다른 API 를 부르고 원천도 다르다.
 *
 * | 영역 | API | 원천 |
 * |---|---|---|
 * | 마감 임박 | `GET /api/contests/closing-soon` | 우리 DB (canonical) |
 * | 축제 | `GET /api/festivals` | KTO 프록시 — `502`·`504` 가 실제로 난다 |
 *
 * 하나의 sealed 상태로 묶으면 **KTO 가 죽었을 때 멀쩡한 대회 목록까지 가려집니다.**
 * 그래서 화면 전체 `phase` 없이 영역 상태 둘만 둔다 — 층위 구분은 [SectionState] 참고.
 */
data class HomeUiState(
    /** 마감 임박 대회 — 접수중 ∧ regEnd 임박순 상위 6건. (SPEC §4.4-3) */
    val closingSoon: SectionState<List<RaceSummary>> = SectionState.Loading,
    /** 축제·지역 관광 추천. (SPEC §4.4-4) */
    val festivals: SectionState<List<FestivalSummary>> = SectionState.Loading,
) {
    /**
     * 히어로에 세우는 대표 대회. (SPEC §4.4-2)
     *
     * 마감 임박 첫 항목이다 — 따로 조회하지 않는다. 마감 임박이 로딩·빈·오류면 null 이고
     * 히어로는 대회 없이 로고·검색만 그린다.
     */
    val featured: RaceSummary?
        get() = closingSoon.valueOrNull?.firstOrNull()
}
