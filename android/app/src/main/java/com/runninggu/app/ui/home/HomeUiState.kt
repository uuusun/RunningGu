package com.runninggu.app.ui.home

import com.runninggu.app.ui.model.FestivalSummary
import com.runninggu.app.ui.model.RaceSummary

/**
 * S1 홈의 UI 계약. (SPEC §4.4 · §3-5)
 *
 * 화면(UI)과 데이터 연결부의 인터페이스다. 지금은 ViewModel이 임시 데이터를 채우지만,
 * 실제 Repository(AP-14)가 붙어도 이 타입은 그대로 두고 ViewModel 내부만 교체한다.
 */
sealed interface HomeUiState {

    /** 불러오는 중. 조회 중 중복 실행을 막는다. */
    data object Loading : HomeUiState

    /**
     * 네트워크·서버·외부 API 오류. 정상 빈 결과(Empty)와 구분한다 — 오류를
     * 빈 상태나 무한 Loading으로 강등하지 않는다. (SPEC §3-5)
     */
    data class Error(val message: String) : HomeUiState

    data class Content(
        /** 히어로에 세우는 대표 대회. 없으면 히어로 영역을 접는다. */
        val featured: RaceSummary?,
        /** 마감 임박 대회 — 접수중 ∧ regEnd 임박순 상위 6건. (SPEC §4.4-3) */
        val closingSoon: List<RaceSummary>,
        /** 축제·지역 관광 추천. (SPEC §4.4-4) */
        val festivals: List<FestivalSummary>,
    ) : HomeUiState {
        /** 정상 조회했지만 보여줄 게 하나도 없는 상태. */
        val isEmpty: Boolean
            get() = featured == null && closingSoon.isEmpty() && festivals.isEmpty()
    }
}
