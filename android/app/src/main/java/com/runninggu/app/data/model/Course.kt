package com.runninggu.app.data.model

import com.runninggu.app.domain.LatLng

/**
 * 코스 난이도. (API 명세 부록 C · SPEC §5.8)
 *
 * **서버가 계산한 값을 표시만 한다** — 앱은 다시 계산하지 않는다.
 * 기준이 화면마다 다르다는 점에 주의: 지역별 목록은 원본 코스 전체 등급이고,
 * 출발지 주변은 잘라 만든 왕복 구간의 실제 `gainM/routeKm` 다. **달라도 정상이다.**
 */
enum class Difficulty(val label: String) {
    EASY("평지"),
    NORMAL("완만"),
    HARD("언덕"),
}

/** 경로 원천. (부록 C `CourseDataSource`) */
enum class CourseDataSource {
    /** 두루누비 API 메타 + GPX 경로 */
    API_GPX,

    /** GPX 만 있는 큐레이션 코스 */
    GPX_ONLY,

    /** 요청 시점에 서버가 만든 OSM 순환 경로. 지역별 목록에는 안 나온다 */
    OSM_GENERATED,
}

/**
 * 출발지 주변 목록의 한 항목. (§6-1)
 *
 * **경로와 장소를 한 목록에 거리순으로 섞어** 보여준다(SPEC §4.11-5). 사용자에게는
 * 출처를 노출하지 않고 "따라갈 경로가 있는가" 만 구분한다 — 그게 [Route] 와 [Place] 다.
 */
sealed interface NearbyItem {
    /** 출발지에서 경로 시작점 또는 장소까지의 거리(m). 서버가 이 값으로 정렬해 준다. */
    val distanceM: Int
    val name: String
    val lat: Double
    val lng: Double

    /** 따라갈 경로가 있는 항목. */
    data class Route(
        /** near 응답 안에서만 유효한 불투명 식별자. 저장·중복 판정에 쓰지 않는다. */
        val routeId: String,
        override val name: String,
        override val distanceM: Int,
        override val lat: Double,
        override val lng: Double,
        val dataSource: CourseDataSource?,
        val difficulty: Difficulty?,
        /** 왕복 실거리(km). */
        val routeKm: Double,
        /** 분당 110m 기준. (SPEC §5.8) */
        val durationMin: Int,
        val gainM: Int,
        /** 고도 스트립용. 최대 100개로 균등 축약돼 있고 고도가 없으면 빈 배열이다. */
        val elevationProfileM: List<Int>,
        /** 목표보다 300m 넘게 짧다 — 화면이 "목표보다 짧게 짜였어요" 안내를 붙인다. */
        val shortfall: Boolean,
        /**
         * 인코딩된 왕복 경로 **원문**. (API 명세 §6-1)
         *
         * **저장 요청에는 이 값을 그대로 다시 보낸다**(§7-A). 풀었다 다시 묶으면 서버의
         * `routeFingerprint` 가 달라져 같은 코스가 중복 저장된다. 지도에 그릴 때는
         * 아래 [path] 를 쓴다.
         */
        val pathPolyline: String?,
        /**
         * 지도에 그릴 좌표열. `data/remote` 매퍼가 [pathPolyline] 을 풀어 채운다 (#129).
         *
         * 못 풀었거나 경로가 없으면 비어 있다 — 화면은 "경로 없음" 으로 다룬다.
         */
        val path: List<LatLng> = emptyList(),
        // 큐레이션 경로에만 있다. OSM 생성 경로는 원본이 없어 생략된다
        val sourceCourseId: String? = null,
        val sido: String? = null,
        val sigun: String? = null,
        val fullDistanceKm: Double? = null,
    ) : NearbyItem

    /** 경로가 없는 장소. 탭하면 카카오 장소 페이지로 나간다. */
    data class Place(
        override val name: String,
        override val distanceM: Int,
        override val lat: Double,
        override val lng: Double,
        val category: String?,
        val address: String?,
        val placeUrl: String?,
    ) : NearbyItem
}

/** `/courses/near` 실패한 원천. (§6-1) */
enum class CourseSource { DURUNUBI, OSM, KAKAO }

/**
 * 출발지 주변 조회 결과. (§6-1)
 *
 * [degradedSources] 가 비어 있지 않아도 [items] 가 있으면 **정상 표시 + 비차단 안내**다.
 * 품질 상한을 통과한 경로가 없는 것은 정상 0건이지 실패가 아니다.
 */
data class NearbyCourses(
    val items: List<NearbyItem> = emptyList(),
    val degradedSources: List<CourseSource> = emptyList(),
    /** 공공누리·ODbL 출처표시 의무. **앱은 문자열을 변형하지 않고 그대로 표시한다.** */
    val attributions: List<String> = emptyList(),
)

/** 지역별 목록의 코스. 큐레이션만 나온다. (§6-2) */
data class CourseSummary(
    val courseId: String,
    val courseName: String,
    val sido: String?,
    val sigun: String?,
    val distanceKm: Double,
    val difficulty: Difficulty?,
    val gainM: Int?,
    val durationMin: Int?,
    val dataSource: CourseDataSource?,
)

/** 지역 칩. 코스 수 내림차순. (§6-3 · SPEC §5.8 `courseRegions`) */
data class CourseRegion(val region: String, val count: Int)

/**
 * 목표 거리 계약. 🔒 SPEC §4.11-2 · API 명세 §6-1.
 *
 * 슬라이더(ui)와 쿼리 파라미터(data)가 같은 값을 봐야 한다 — 양쪽에 따로 두면
 * 한쪽만 고쳤을 때 조용히 어긋난다. **여기가 유일한 출처다.**
 */
object CourseTargetKm {
    const val MIN = 1.0
    const val MAX = 21.0

    /** 슬라이더 눈금 단위. */
    const val STEP = 0.5
    const val DEFAULT = 5.0
}
