package com.runninggu.app.ui.wizard

import com.runninggu.app.domain.PoiCategory
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/**
 * `GET /api/pois` 응답. (API 명세 §4-2)
 *
 * TODO(AP-14): `data/remote` 로 옮기고 Retrofit 응답 타입으로 쓴다.
 */
@Serializable
data class PoiSearchResponse(
    /** `LIVE` · `SAMPLE` · `SYNTH` — 소스 배지로 노출한다. (SPEC §6.3 · NFR-2) */
    val source: String,
    val items: List<PoiItemDto> = emptyList(),
)

@Serializable
data class PoiItemDto(
    val name: String,
    val category: String,
    val lat: Double,
    val lng: Double,
    val distanceM: Int = 0,
    val description: String = "",
    val address: String = "",
    val url: String = "",
    val imageUrl: String? = null,
)

/** 화면이 쓰는 장소 항목. */
data class PoiItem(
    val name: String,
    val address: String,
    val description: String,
    val lat: Double,
    val lng: Double,
)

fun PoiItemDto.toUi() = PoiItem(
    name = name,
    address = address,
    description = description,
    lat = lat,
    lng = lng,
)

/** 조회 결과 + 소스 배지. */
data class PoiSearchResult(val source: String, val items: List<PoiItem>)

/**
 * 장소 조회 창구. (API 명세 §4-2 · SPEC §9.4)
 *
 * **앱은 카카오·KTO 를 직접 부르지 않는다.** REST 키가 서버에만 있으므로 전부 우리 서버를 거친다.
 *
 * TODO(AP-14): `data/remote` 의 Retrofit 구현으로 교체한다.
 */
interface PoiRepository {
    /**
     * @param query 키워드 검색. 공백 제거 후 2자 이상이어야 한다 — 미만이면 서버가
     *  `400 VALIDATION_FAILED` 를 준다. null 이면 기준점 주변 추천이다.
     */
    suspend fun search(
        category: PoiCategory,
        lat: Double,
        lng: Double,
        query: String? = null,
        size: Int = DEFAULT_SIZE,
    ): PoiSearchResult

    companion object {
        /** 노출 8건. (API 명세 §4-2) */
        const val DEFAULT_SIZE = 8

        /** 키워드 검색 최소 글자 수. */
        const val MIN_QUERY_LENGTH = 2
    }
}

/** 백엔드가 준비되기 전까지 쓰는 스텁. (매핑표 §12) */
object FakePoiRepository : PoiRepository {

    private val LODGING = listOf(
        PoiItem("호텔 여의도 가온", "영등포구 여의대로 24", "여의도 · 대회장 0.8km", 37.522, 126.925),
        PoiItem("스테이 한강뷰", "영등포구 국제금융로 10", "여의도 · 대회장 1.2km", 37.525, 126.928),
        PoiItem("비즈니스 호텔 마포", "마포구 마포대로 100", "공덕 · 대회장 3.4km", 37.541, 126.949),
        PoiItem("게스트하우스 별", "영등포구 영중로 15", "영등포 · 대회장 2.1km", 37.516, 126.907),
        PoiItem("한옥 스테이 서촌", "종로구 자하문로 7", "서촌 · 대회장 6.8km", 37.578, 126.970),
        PoiItem("레지던스 온", "용산구 한강대로 92", "용산 · 대회장 4.5km", 37.530, 126.965),
        PoiItem("리버사이드 리조트", "영등포구 여의동로 330", "여의도 · 대회장 1.6km", 37.528, 126.932),
        PoiItem("캡슐호텔 하루", "마포구 양화로 45", "홍대 · 대회장 5.2km", 37.556, 126.923),
    )

    override suspend fun search(
        category: PoiCategory,
        lat: Double,
        lng: Double,
        query: String?,
        size: Int,
    ): PoiSearchResult {
        delay(NETWORK_DELAY_MS)
        val pool = if (category == PoiCategory.LODGING) LODGING else emptyList()
        val filtered = query?.let { q -> pool.filter { q in it.name || q in it.address } } ?: pool
        return PoiSearchResult(source = "SAMPLE", items = filtered.take(size))
    }

    private const val NETWORK_DELAY_MS = 400L
}
