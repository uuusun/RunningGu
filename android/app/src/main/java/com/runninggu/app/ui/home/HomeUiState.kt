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
    /** 마감 임박 대회 — 접수중 ∧ regEnd 임박순 상위 4건. (SPEC §4.4-3 🔒 · 결정-28) */
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

    /**
     * 히어로 배경에 돌려 보여줄 축제 사진들. (SPEC §4.4 히어로 배경 · 이슈 #247 후속)
     *
     * 축제 추천 가운데 **사진이 있는 항목 전부**다 — 따로 조회하지 않는다. 진행 중인 축제를
     * 앞에 둔다: 지금 열리는 곳이 먼저 걸리는 것이 "이번 달" 목록의 뜻에 맞다. 그 안에서는
     * 서버가 준 순서를 지킨다(안정 정렬). 한 장만 고정으로 깔았더니 열 때마다 같은 사진이라
     * 배경이 바뀌는 줄을 몰랐다(2026-09-14) — 히어로가 이 목록을 무작위 장부터 돌린다.
     *
     * 축제가 로딩·빈·오류거나 사진 있는 항목이 없으면 빈 목록이고 히어로는 지형 그림을 그린다.
     * 대회 `imageUrl` 을 쓰지 않는 이유는 [HomeHero] KDoc — 공식 홈페이지 스크린샷이다.
     */
    val heroPhotos: List<FestivalSummary>
        get() = festivals.valueOrNull.orEmpty()
            .filter { it.imageUrl != null }
            .sortedByDescending { it.isOngoing }
}
