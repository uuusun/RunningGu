package com.runninggu.app.data.model

import java.time.LocalDate

/**
 * 마이 `[러닝코스]` 목록 항목. (API 명세 §7-A · SPEC §4.13)
 *
 * 목록에는 **경로가 없다** — 상세에서만 온다(§7-A `pathPolyline` 제외 프로젝션).
 * 그래서 목록 카드는 지도를 그리지 않는다.
 */
data class SavedCourse(
    val id: Long,
    val courseName: String,
    val distanceKm: Double,
    val durationMin: Int,
    val gainM: Int,
    val difficulty: Difficulty?,
    val dataSource: CourseDataSource?,
    val region: String?,
    /** 저장한 날. KST 로 접어 카드에 "MM.DD 저장" 으로 쓴다. */
    val savedAt: LocalDate?,
)

/**
 * 저장 코스 상세. 경로 점선과 출처 한 줄을 그린다. (§7-A · 결정-44)
 *
 * [attributions] 는 **저장 시점 snapshot** 이라 외부 문구가 바뀌어도 그대로다. 앱은 순서를
 * 지켜 `" · "` 로 이어 붙이고 문구를 변형하지 않는다.
 */
data class SavedCourseDetail(
    val course: SavedCourse,
    val elevationProfileM: List<Int>,
    val pathPolyline: String?,
    val attributions: List<String>,
)

/**
 * 저장 결과. (§7-A)
 *
 * [created] 가 false 면 같은 경로를 이미 저장해 둔 것이다 — 서버가 새 행을 만들지 않고
 * 기존 id 를 준다. 화면은 "저장했어요" 와 "이미 저장한 코스예요" 를 이 값으로 가른다.
 */
data class SaveCourseResult(val id: Long, val created: Boolean)
