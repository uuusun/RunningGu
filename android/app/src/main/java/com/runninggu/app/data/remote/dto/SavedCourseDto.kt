package com.runninggu.app.data.remote.dto

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * 코스 저장 요청. (API 명세 §7-A · 결정-44)
 *
 * **`routeFingerprint` 와 `attributions` 를 보내지 않는다.** 서버가 `pathPolyline` 의
 * geometry 로 fingerprint 를 계산하고, 원천 메타데이터로 출처 문구를 확정한다 —
 * 클라이언트가 보내도 무시한다. 같은 경로를 다시 저장하면 새 행 대신 기존 id 가 온다.
 *
 * `sourceCourseId` · `region` 은 큐레이션 경로에만 있다. `OSM_GENERATED` 는 생략한다.
 */
@Serializable
data class SaveCourseRequestDto(
    val dataSource: String,
    val courseName: String,
    val distanceKm: Double,
    val durationMin: Int,
    val gainM: Int,
    val elevationProfileM: List<Int> = emptyList(),
    /** 진입점 좌표 — 목록에서 "내 위치로부터" 를 재는 기준이다. */
    val entryLat: Double,
    val entryLng: Double,
    /** 서버가 준 문자열을 **그대로** 되돌려보낸다. 앱이 다시 인코딩하지 않는다(이슈 #62). */
    val pathPolyline: String,
    val difficulty: String? = null,
    val sourceCourseId: String? = null,
    val region: String? = null,
)

/**
 * 저장 응답. (§7-A)
 *
 * 신규는 `201 {id, created:true}`, 같은 경로를 다시 저장하면 `200 {id, created:false}` 다 —
 * 화면이 "저장했어요" 와 "이미 저장한 코스예요" 를 가르는 값이다.
 */
@Serializable
data class SaveCourseResponseDto(
    val id: Long,
    val created: Boolean = true,
)

/** 저장 코스 목록 항목. **`pathPolyline` 이 없다** — 목록이 LOB 를 안 읽는다(§7-A). */
@Serializable
data class SavedCourseDto(
    val id: Long,
    val courseName: String,
    val distanceKm: Double = 0.0,
    val durationMin: Int = 0,
    val gainM: Int = 0,
    val difficulty: String? = null,
    val dataSource: String? = null,
    val region: String? = null,
    @Contextual val savedAt: Instant? = null,
)

/**
 * 저장 코스 상세. 목록에 없는 경로·고도·출처가 함께 온다. (§7-A)
 *
 * [attributions] 는 저장 시점 snapshot 이라 외부 문구가 바뀌어도 그대로다(결정-44).
 * 앱은 배열 순서대로 `" · "` 로 이어 붙여 표시한다.
 */
@Serializable
data class SavedCourseDetailDto(
    val id: Long,
    val courseName: String,
    val distanceKm: Double = 0.0,
    val durationMin: Int = 0,
    val gainM: Int = 0,
    val difficulty: String? = null,
    val dataSource: String? = null,
    val region: String? = null,
    val elevationProfileM: List<Int> = emptyList(),
    val pathPolyline: String? = null,
    val attributions: List<String> = emptyList(),
    @Contextual val savedAt: Instant? = null,
)
