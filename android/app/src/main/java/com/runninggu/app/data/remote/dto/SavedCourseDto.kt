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
    /**
     * 선택 🔧(§7-A). 고도 정보가 없어 난이도를 못 낸 경로도 저장할 수 있어야 한다 —
     * 필수로 두면 그런 코스는 [저장] 자체가 막힌다.
     */
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
    /**
     * 신규 저장이면 true, 같은 경로가 이미 있으면 false. **항상 온다** — 기본값을 두면
     * 서버가 빠뜨렸을 때 "새로 저장했어요" 로 조용히 처리된다(#76 리뷰).
     */
    val created: Boolean,
)

/**
 * 저장 코스 목록 항목. **`pathPolyline` 이 없다** — 목록이 LOB 를 안 읽는다(§7-A).
 *
 * 필수 필드에 기본값을 두지 않는다. 두면 서버가 다른 이름으로 주거나(예: `savedAt` 대신
 * `createdAt`) 아예 빠뜨려도 **`null`·`0` 으로 조용히 통과해 계약 불일치가 숨는다**
 * (#76 리뷰). 빠지면 역직렬화에서 바로 터지는 편이 낫다.
 */
@Serializable
data class SavedCourseDto(
    val id: Long,
    val courseName: String,
    val distanceKm: Double,
    val durationMin: Int,
    val gainM: Int,
    @Contextual val savedAt: Instant,
    /** 원본 등급이 없으면 null — 배지를 그리지 않는다. */
    val difficulty: String? = null,
    val dataSource: String? = null,
    /** 큐레이션만. `OSM_GENERATED` 는 null. */
    val region: String? = null,
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
    val distanceKm: Double,
    val durationMin: Int,
    val gainM: Int,
    @Contextual val savedAt: Instant,
    val difficulty: String? = null,
    val dataSource: String? = null,
    val region: String? = null,
    /** 고도가 없으면 `[]`. 최대 100개로 축약돼 있다. */
    val elevationProfileM: List<Int> = emptyList(),
    val pathPolyline: String? = null,
    /** 저장 시점 snapshot. 출처가 없으면 `[]` (결정-44). */
    val attributions: List<String> = emptyList(),
)
