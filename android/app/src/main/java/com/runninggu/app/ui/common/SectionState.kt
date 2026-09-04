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

    /**
     * 내용이 있다.
     *
     * [origin] 은 **이 값이 어디서 왔는지**다. 기본은 서버라 기존 호출부는 그대로다.
     * 오프라인에서 캐시로 그린 영역만 [DataOrigin.LocalCache] 를 달고, 화면은 그때
     * "언제 것" 인지를 함께 그린다(SPEC §6.1 캐시 출처 표기 · 이슈 #276).
     */
    data class Content<T>(
        val value: T,
        val origin: DataOrigin = DataOrigin.Server,
    ) : SectionState<T>

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

/**
 * 영역에 그려진 값의 출처. (SPEC §6.1 · 매핑표 `LOCAL_CACHE` · 이슈 #276)
 *
 * **왜 화면까지 올리나** — 캐시로 그린 목록은 지금 서버가 말하는 것과 다를 수 있다.
 * 출처를 안 올리면 화면은 그 사실을 모른 채 낡은 목록을 최신인 것처럼 그리고, 사용자는
 * 무엇이 최신인지 알 방법이 없다. 폴백이 값어치가 있으려면 "언제 것" 이 같이 가야 한다.
 */
sealed interface DataOrigin {

    /** 방금 서버에서 받았다. 기본값이다. */
    data object Server : DataOrigin

    /**
     * 마지막으로 성공한 응답을 기기에서 되살렸다.
     *
     * @param cachedAt 앱이 그 응답을 저장한 시각(UTC). 화면은 이것을 KST 로 옮겨 보여준다(§6.6).
     */
    data class LocalCache(val cachedAt: java.time.Instant) : DataOrigin
}

/** 캐시로 그린 것이면 저장 시각, 아니면 null. 화면이 분기 없이 읽을 때 쓴다. */
val <T> SectionState<T>.cachedAt: java.time.Instant?
    get() = ((this as? SectionState.Content)?.origin as? DataOrigin.LocalCache)?.cachedAt
