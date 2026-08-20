package com.runninggu.app.data.repository

import com.runninggu.app.data.model.PoiItem
import com.runninggu.app.data.model.PoiSearchResult
import com.runninggu.app.data.remote.PoiApi
import com.runninggu.app.data.remote.apiCall
import com.runninggu.app.data.remote.mapper.toResult
import com.runninggu.app.domain.PoiCategory
import kotlinx.coroutines.delay

/**
 * 장소 조회 창구. (API 명세 §4-2 · SPEC §9.4)
 *
 * **앱은 카카오·KTO 를 직접 부르지 않는다.** REST 키가 서버에만 있으므로 전부 우리 서버를 거친다.
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

/** 서버 구현. (§4-2) */
class RemotePoiRepository(private val api: PoiApi) : PoiRepository {

    override suspend fun search(
        category: PoiCategory,
        lat: Double,
        lng: Double,
        query: String?,
        size: Int,
    ): PoiSearchResult = apiCall {
        api.search(
            category = category.name,
            lat = lat,
            lng = lng,
            // 공백만 있는 검색어는 보내지 않는다 — 서버가 400 을 주기 전에 앱이 거른다
            query = query?.trim()?.takeIf { it.length >= PoiRepository.MIN_QUERY_LENGTH },
            size = size,
        ).toResult()
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

    /** S7 후보 시트 데모용. 여의도(가짜 동선 무대) 주변으로 맞춘 카테고리별 8건. */
    private val CANDIDATES = mapOf(
        PoiCategory.TOUR to listOf(
            PoiItem("여의도한강공원", "영등포구 여의동로 330", "관광명소 · 한강공원", 37.528, 126.934),
            PoiItem("63스퀘어 전망대", "영등포구 63로 50", "관광명소 · 전망대", 37.519, 126.940),
            PoiItem("선유도공원", "영등포구 선유로 343", "관광명소 · 공원", 37.543, 126.900),
            PoiItem("노들섬", "용산구 양녕로 445", "관광명소 · 복합문화공간", 37.517, 126.958),
            PoiItem("세빛섬", "서초구 올림픽대로 683", "관광명소 · 수상시설", 37.512, 126.995),
            PoiItem("여의도공원", "영등포구 여의공원로 68", "관광명소 · 공원", 37.524, 126.924),
            PoiItem("샛강생태공원", "영등포구 여의도동", "관광명소 · 생태공원", 37.517, 126.918),
            PoiItem("서울마리나", "영등포구 여의서로 160", "관광명소 · 마리나", 37.532, 126.917),
        ),
        PoiCategory.FOOD to listOf(
            PoiItem("여의도 진주집", "영등포구 국제금융로 6길", "한식 · 콩국수", 37.520, 126.926),
            PoiItem("정인면옥", "영등포구 여의대방로 379", "한식 · 평양냉면", 37.516, 126.928),
            PoiItem("만강홍", "영등포구 국제금융로 78", "중식 · 딤섬", 37.522, 126.928),
            PoiItem("한우리", "영등포구 여의나루로 42", "한식 · 한정식", 37.526, 126.930),
            PoiItem("스시카나에", "영등포구 여의대로 24", "일식 · 스시", 37.521, 126.925),
            PoiItem("샐러디 여의도점", "영등포구 의사당대로 83", "샐러드 · 가벼운 식사", 37.527, 126.921),
            PoiItem("브레드피트 베이커리", "영등포구 여의동로 213", "베이커리 · 브런치", 37.529, 126.932),
            PoiItem("여의도 왕돈까스", "영등포구 여의대방로 43길", "경양식 · 돈까스", 37.515, 126.925),
        ),
        PoiCategory.CAFE to listOf(
            PoiItem("카페 한강뷰", "영등포구 여의동로 330", "카페 · 리버뷰", 37.528, 126.933),
            PoiItem("아일랜드커피", "영등포구 국제금융로 10", "카페 · 스페셜티", 37.525, 126.927),
            PoiItem("브루잉랩 여의도", "영등포구 의사당대로 127", "카페 · 핸드드립", 37.529, 126.919),
            PoiItem("달빛다방", "영등포구 여의나루로 67", "카페 · 디저트", 37.527, 126.932),
            PoiItem("공원옆카페", "영등포구 여의공원로 101", "카페 · 테라스", 37.523, 126.922),
            PoiItem("모카포트", "영등포구 은행로 30", "카페 · 에스프레소바", 37.520, 126.923),
            PoiItem("티하우스 온", "영등포구 여의대로 108", "찻집 · 전통차", 37.522, 126.930),
            PoiItem("베이커리 러너", "영등포구 여의동로 279", "카페 · 베이글", 37.530, 126.935),
        ),
        PoiCategory.WELLNESS to listOf(
            PoiItem("한강 스파랜드", "영등포구 여의대방로 61", "찜질방 · 스파", 37.517, 126.923),
            PoiItem("리커버리 스튜디오", "영등포구 국제금융로 52", "마사지 · 스트레칭", 37.523, 126.927),
            PoiItem("여의도 사우나", "영등포구 여의나루로 33", "사우나", 37.525, 126.929),
            PoiItem("풋샵 마라토너", "영등포구 은행로 11", "발마사지 · 회복", 37.521, 126.922),
            PoiItem("온천당", "영등포구 여의대방로 383", "온천 · 족욕", 37.515, 126.927),
            PoiItem("스트레칭존", "영등포구 의사당대로 96", "필라테스 · 요가", 37.528, 126.920),
            PoiItem("힐링테라피", "영등포구 여의동로 213", "아로마 테라피", 37.529, 126.931),
            PoiItem("커플스파 리버", "영등포구 여의서로 89", "스파 · 반신욕", 37.531, 126.916),
        ),
        PoiCategory.NATURE to listOf(
            PoiItem("여의도 벚꽃길", "영등포구 여의서로", "산책로 · 윤중로", 37.528, 126.918),
            PoiItem("한강 자전거길 여의도 구간", "영등포구 여의동로", "산책로 · 라이딩", 37.527, 126.936),
            PoiItem("밤섬 전망데크", "영등포구 여의도동", "생태 · 철새조망", 37.533, 126.929),
            PoiItem("안양천 합수부 산책로", "영등포구 양평동", "산책로 · 강변", 37.535, 126.893),
            PoiItem("용왕산 근린공원", "양천구 목동동로 소재", "공원 · 둘레길", 37.538, 126.877),
            PoiItem("선유도 전망정원", "영등포구 선유로 343", "정원 · 전망", 37.544, 126.899),
            PoiItem("여의도공원 생태숲", "영등포구 여의공원로 68", "숲길 · 산책", 37.525, 126.923),
            PoiItem("노량진 수변산책로", "동작구 노량진로", "산책로 · 수변", 37.514, 126.942),
        ),
        PoiCategory.HISTORY to listOf(
            PoiItem("국회의사당 본관 견학", "영등포구 의사당대로 1", "견학 · 근현대", 37.532, 126.914),
            PoiItem("여의도 지하벙커 SeMA", "영등포구 여의대로 지하 76", "전시 · 근현대사", 37.525, 126.925),
            PoiItem("한강역사탐방로", "영등포구 여의동로", "역사 · 탐방로", 37.526, 126.938),
            PoiItem("공군호국관", "영등포구 여의공원로 79", "전시관 · 호국", 37.524, 126.920),
            PoiItem("사육신역사공원", "동작구 노량진로 191", "사적 · 공원", 37.514, 126.947),
            PoiItem("용양봉저정", "동작구 노량진로32길 14-7", "문화재 · 정자", 37.513, 126.952),
            PoiItem("절두산 순교성지", "마포구 토정로 6", "사적 · 성지", 37.544, 126.907),
            PoiItem("양화진외국인선교사묘원", "마포구 양화진길 46", "사적 · 묘원", 37.545, 126.905),
        ),
        PoiCategory.LODGING to LODGING,
    )

    override suspend fun search(
        category: PoiCategory,
        lat: Double,
        lng: Double,
        query: String?,
        size: Int,
    ): PoiSearchResult {
        delay(NETWORK_DELAY_MS)
        val pool = CANDIDATES[category].orEmpty()
        val filtered = query?.let { q -> pool.filter { q in it.name || q in it.address } } ?: pool
        return PoiSearchResult(source = "SAMPLE", items = filtered.take(size))
    }

    private const val NETWORK_DELAY_MS = 400L
}
