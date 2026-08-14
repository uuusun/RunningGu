package com.runninggu.app.domain

/**
 * 걷기 좋은 곳 한 곳. 카카오 로컬 키워드 검색 결과를 도메인 형태로 옮긴 것이다.
 *
 * 조회는 서버가 한다 — 앱에는 카카오 REST 키가 없다(SPEC §9.4).
 *
 * @param categoryName 카카오 `category_name` 원본. `여행 > 관광,명소 > 공원 > 도시근린공원`
 *                     처럼 계층이 `>` 로 이어진다.
 */
data class WalkSpot(
    val name: String,
    val categoryName: String,
    val address: String,
    val distanceM: Int,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val placeUrl: String = "",
) {
    /** 화면에 쓰는 짧은 분류. 계층의 마지막 조각. */
    val category: String get() = categoryName.substringAfterLast('>').trim()
}

/**
 * 걷기 스팟 필터. (SPEC §5.9)
 *
 * 카카오는 "공원"으로 검색하면 공원 안 시설물(방문자센터·게양대·배드민턴장)까지 같이 준다.
 * 그대로 두면 한 공원이 목록을 다 먹는다 — 여의도 실측에서 12칸 중 6칸이 샛강생태공원의
 * 부속 시설이었다. 그래서 카테고리·이름으로 걸러낸 뒤 **같은 공원끼리 묶는다.**
 *
 * 순수 함수라 네트워크 없이 검증된다.
 */
object WalkSpotFilter {

    /** 조회 키워드. 하천·한강공원·생태공원은 러닝에 좋은 지형이라 따로 넣는다. */
    val QUERIES = listOf("공원", "산책로", "둘레길", "하천", "한강공원", "생태공원")

    const val RADIUS_M = 3000
    const val PAGE_SIZE = 15
    const val LIMIT = 12

    /** 걷기 장소로 볼 카테고리. */
    private val INCLUDE_CATEGORY = Regex(
        "공원|관광|명소|산책|둘레|하천|유원지|수목원|숲|생태|휴양|호수|해수욕|해변|등산로|트레킹|자연"
    )

    /** 장소가 아니라 부속 시설·운영 주체인 카테고리. */
    private val EXCLUDE_CATEGORY = Regex("공원시설물|공원관리운영|공공기관|사무소")

    /** 걷는 곳이 아닌 하위 시설. */
    private val EXCLUDE_NAME = Regex(
        "화장실|주차장|주차|테니스|풋살|축구장|야구장|농구장|체육관|관리사무소|매점|안내소|정류장"
    )

    /** 러닝하기엔 너무 작은 동네 시설. */
    private val EXCLUDE_TOO_SMALL = Regex("어린이공원|놀이공원|쌈지공원|소공원")

    /** 이름의 첫 어절. 같은 공원의 하위 지점을 묶는 기준이 된다. */
    private fun rootOf(name: String): String = name.trim().substringBefore(' ')

    /**
     * 원본 검색 결과를 걷기 스팟 목록으로 만든다.
     *
     * 이름+주소가 같으면 중복으로 보고, 첫 어절이 같으면 한 곳으로 묶는다.
     * 묶인 무리의 대표는 **이름이 첫 어절과 정확히 일치하는 항목**이고, 없으면 가장 가까운 항목이다.
     * (그래야 "샛강생태공원 조류관찰대"가 아니라 "샛강생태공원"이 남는다.)
     */
    fun filter(raw: List<WalkSpot>, limit: Int = LIMIT): List<WalkSpot> {
        val kept = LinkedHashMap<String, WalkSpot>()   // 이름+주소 → 스팟
        for (spot in raw) {
            if (!INCLUDE_CATEGORY.containsMatchIn(spot.categoryName)) continue
            if (EXCLUDE_CATEGORY.containsMatchIn(spot.categoryName)) continue
            if (EXCLUDE_NAME.containsMatchIn(spot.name)) continue
            if (EXCLUDE_TOO_SMALL.containsMatchIn(spot.name)) continue
            kept.putIfAbsent(spot.name + spot.address, spot)
        }
        return kept.values
            .groupBy { rootOf(it.name) }
            .map { (root, group) ->
                group.firstOrNull { it.name == root } ?: group.minBy { it.distanceM }
            }
            .sortedBy { it.distanceM }
            .take(limit)
    }
}
