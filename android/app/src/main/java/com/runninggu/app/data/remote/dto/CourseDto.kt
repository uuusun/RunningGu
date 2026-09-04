package com.runninggu.app.data.remote.dto

import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import java.time.Instant

/**
 * `/courses/near` 항목. (API 명세 §6-1)
 *
 * `kind` 로 경로와 장소를 가르고, **다른 종류의 전용 필드는 `null` 이 아니라 아예 생략**된다.
 * 그래서 한 클래스에 다 넣지 않고 sealed 로 갈랐다 — 어느 필드가 어느 종류의 것인지
 * 타입으로 드러나야 화면이 잘못 읽지 않는다.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface NearItemDto {
    val name: String
    val distanceM: Int
    val lat: Double
    val lng: Double

    @Serializable
    @SerialName("ROUTE")
    data class Route(
        val routeId: String,
        override val name: String,
        override val distanceM: Int = 0,
        override val lat: Double,
        override val lng: Double,
        /** `API_GPX|GPX_ONLY|OSM_GENERATED` */
        val dataSource: String? = null,
        /** 출발지 주변은 `EASY|NORMAL` 만 나온다 — 서버가 `HARD` 를 자동 추천에서 제외한다 */
        val difficulty: String? = null,
        val routeKm: Double = 0.0,
        val durationMin: Int = 0,
        val gainM: Int = 0,
        val elevationProfileM: List<Int> = emptyList(),
        val shortfall: Boolean = false,
        val pathPolyline: String? = null,
        // 큐레이션 경로에만 — OSM 생성 경로에서는 생략된다
        val sourceCourseId: String? = null,
        val sido: String? = null,
        val sigun: String? = null,
        val fullDistanceKm: Double? = null,
    ) : NearItemDto

    @Serializable
    @SerialName("PLACE")
    data class Place(
        override val name: String,
        override val distanceM: Int = 0,
        override val lat: Double,
        override val lng: Double,
        val category: String? = null,
        val address: String? = null,
        val placeUrl: String? = null,
    ) : NearItemDto
}

/**
 * 출발지 주변 응답. (§6-1)
 *
 * 앱은 [items] 순서를 **다시 정렬하지 않는다** — 서버가 경로와 장소를 `distanceM` 순으로
 * 이미 섞어 준다.
 */
@Serializable
data class CoursesNearDto(
    val items: List<NearItemDto> = emptyList(),
    /** 호출·동기화 실패로 빠진 원천 `DURUNUBI|OSM|KAKAO`. 상한 미달 0건은 여기 안 들어간다 */
    val degradedSources: List<String> = emptyList(),
    /** 출처표시 문구. 앱은 변형하지 않고 그대로 쓴다 */
    val attributions: List<String> = emptyList(),
)

/** 지역별 목록 항목. 큐레이션만. (§6-2) */
@Serializable
data class CourseDto(
    val courseId: String,
    val courseName: String,
    val sido: String? = null,
    val sigun: String? = null,
    val distanceKm: Double = 0.0,
    /** 여기 값은 **원본 코스 전체 등급**이다 — near 의 구간 등급과 달라도 정상 */
    val difficulty: String? = null,
    val gainM: Int? = null,
    val durationMin: Int? = null,
    val dataSource: String? = null,
    @Contextual val syncedAt: Instant? = null,
)

/**
 * 큐레이션 코스 상세. (`GET /api/courses/{courseId}` · #280 계약)
 *
 * 목록([CourseDto])에 경로가 없어 코스를 눌러도 갈 곳이 없던 것을 푸는 응답이다.
 * 목록 필드에 `pathPolyline` · `elevationProfileM` · `attributions` 셋이 더 붙는다.
 *
 * **`pathPolyline` 은 원본 코스 전체 points 다** — `near` 처럼 목표 거리에 맞춰 자른
 * 왕복이 아니다. 같은 `courseId` 라도 두 응답의 거리·시간·고도가 다른 것이 정상이다.
 */
@Serializable
data class CourseDetailDto(
    val courseId: String,
    val courseName: String,
    val sido: String? = null,
    val sigun: String? = null,
    val distanceKm: Double = 0.0,
    val difficulty: String? = null,
    val gainM: Int? = null,
    val durationMin: Int? = null,
    val dataSource: String? = null,
    @Contextual val syncedAt: Instant? = null,
    val pathPolyline: String = "",
    val elevationProfileM: List<Int> = emptyList(),
    val attributions: List<String> = emptyList(),
)

/**
 * 지역별 목록 응답. (§6-2 · API v2.7)
 *
 * 일반 [PageDto] 와 달리 **최상위에 `attributions` 가 붙는다** — 목록 하단 출처 한 줄에
 * 쓴다(SPEC §4.11-b · 결정-44). 공용 PageDto 로 받으면 이 필드가 조용히 버려진다.
 */
@Serializable
data class CoursePageDto(
    val content: List<CourseDto> = emptyList(),
    val page: PageDto.PageMeta = PageDto.PageMeta(),
    /** 실제 사용된 원천의 **검증 완료 문구**. 앱은 순서·문구를 바꾸지 않는다. */
    val attributions: List<String> = emptyList(),
)

/** 지역 칩. (§6-3) */
@Serializable
data class CourseRegionsDto(
    val items: List<Entry> = emptyList(),
) {
    @Serializable
    data class Entry(val region: String, val count: Int = 0)
}
