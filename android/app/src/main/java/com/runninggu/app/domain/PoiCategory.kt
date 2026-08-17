package com.runninggu.app.domain

/**
 * 여행 취향 카테고리와 조회 방식. (SPEC §5.3)
 *
 * [code] 가 있으면 카카오 카테고리 검색, 없으면 [keyword] 로 키워드 검색을 한다.
 * **조회는 서버가 한다** — 앱에는 카카오 REST 키가 없다(SPEC §9.4). 여기 있는 건
 * 서버에 무엇을 요청할지 고르기 위한 분류값이다.
 */
enum class PoiCategory(
    val key: String,
    val label: String,
    val code: String? = null,
    val keyword: String? = null,
    /** 위저드 취향 칩에 노출되는가. 숙소는 검색 전용이라 칩에 없다. */
    val selectable: Boolean = true,
) {
    TOUR("tour", "관광지", code = "AT4"),
    FOOD("food", "맛집", code = "FD6"),
    CAFE("cafe", "카페", code = "CE7"),
    WELLNESS("wellness", "힐링·웰니스", keyword = "온천 스파 사우나 찜질방"),
    NATURE("nature", "자연·트레킹", keyword = "둘레길 공원 산책로 수목원"),
    HISTORY("history", "역사·문화", keyword = "박물관 유적지 문화재"),
    LODGING(
        "lodging", "숙소", code = "AD5",
        keyword = "호텔 모텔 게스트하우스 펜션", selectable = false,
    ),
    ;

    companion object {
        /** 위저드 취향 칩에 노출할 카테고리. */
        val selectable: List<PoiCategory> get() = entries.filter { it.selectable }

        /** 취향 칩 기본 선택값. (SPEC §5.3) */
        val DEFAULT_THEMES = listOf(TOUR, FOOD)

        fun fromKey(key: String?): PoiCategory? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 동선 블록에 붙는 표시용 분류. 취향 카테고리에 없는 것들이 섞인다.
 *
 * 대회 블록은 시스템이 관리하므로 사용자가 바꿀 수 없다. (SPEC §5.7)
 */
enum class BlockCategory(val label: String) {
    TOUR("관광지"),
    FOOD("맛집"),
    CAFE("카페"),
    WELLNESS("힐링웰니스"),
    NATURE("자연트레킹"),
    HISTORY("역사문화"),
    LODGING("숙소"),
    RACE("대회"),
    RECOVERY("회복"),
    ;

    companion object {
        fun of(category: PoiCategory): BlockCategory = when (category) {
            PoiCategory.TOUR -> TOUR
            PoiCategory.FOOD -> FOOD
            PoiCategory.CAFE -> CAFE
            PoiCategory.WELLNESS -> WELLNESS
            PoiCategory.NATURE -> NATURE
            PoiCategory.HISTORY -> HISTORY
            PoiCategory.LODGING -> LODGING
        }
    }
}
