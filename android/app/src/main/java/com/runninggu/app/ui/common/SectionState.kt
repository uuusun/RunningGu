package com.runninggu.app.ui.common

/**
 * 화면 **안의 한 영역**이 갖는 상태. (AGENTS 2장-5)
 *
 * 층위를 헷갈리지 않게 경계를 적어 둔다.
 *
 * - **화면 전체 상태는 `phase`** — 캘린더·대회상세·S6 숙소·S7 결과가 쓰는 `Phase` enum 이다.
 *   화면 하나가 통째로 로딩·오류일 때 쓴다
 * - **화면 안 영역은 [SectionState]** — 한 화면에서 서로 다른 API 를 부르는 영역들이
 *   **따로 실패할 수 있을 때** 쓴다
 *
 * 둘은 층위가 달라서 공존해도 모순이 아니다. 홈처럼 영역별 상태만 있고 전체 phase 가
 * 필요 없는 화면도 있다.
 *
 * AGENTS 2장-5 가 하필 홈을 예로 든다 — "영역 단위 부분 실패가 가능하다. **홈의 마감임박과
 * 축제는 따로 실패할 수 있다**". 축제는 KTO 프록시라 `502`·`504` 가 실제로 나는데, 그때
 * 우리 DB 에서 온 마감임박까지 가려지면 안 된다.
 */
sealed interface SectionState<out T> {

    /** 불러오는 중. */
    data object Loading : SectionState<Nothing>

    data class Content<T>(val value: T) : SectionState<T>

    /**
     * 정상 조회했는데 결과가 없다. **[Error] 와 구분한다**(API 명세 §0-3).
     *
     * 뭉뚱그리면 "없는 것" 과 "못 불러온 것" 이 같아 보여서, 사용자가 다시 시도해야 할
     * 상황인지 알 수 없다.
     */
    data object Empty : SectionState<Nothing>

    /** 네트워크·서버·외부 API 오류. 서버가 준 문구가 있으면 그것, 없으면 null. */
    data class Error(val message: String?) : SectionState<Nothing>
}

/** 내용이 있으면 그 값, 아니면 null. 화면이 분기 없이 읽을 때 쓴다. */
val <T> SectionState<T>.valueOrNull: T?
    get() = (this as? SectionState.Content)?.value
