package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.model.CourseRegion
import com.runninggu.app.data.model.CourseSource
import com.runninggu.app.data.model.CourseSummary
import com.runninggu.app.data.model.Difficulty
import com.runninggu.app.data.model.NearbyCourses
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.data.remote.dto.CourseDto
import com.runninggu.app.data.remote.dto.CourseRegionsDto
import com.runninggu.app.data.remote.dto.CoursesNearDto
import com.runninggu.app.data.remote.dto.NearItemDto

/**
 * 코스 API DTO → 앱 모델. (API 명세 §6)
 *
 * 모르는 enum 은 **항목을 버리지 않고 null 로 둔다.** 난이도 배지 하나 때문에
 * 뛸 수 있는 코스가 목록에서 사라지면 안 된다.
 */
fun CoursesNearDto.toNearbyCourses(): NearbyCourses = NearbyCourses(
    // 서버가 이미 distanceM 오름차순으로 섞어 줬다 — 다시 정렬하지 않는다(§6-1)
    items = items.map { it.toModel() },
    degradedSources = degradedSources.mapNotNull(::courseSourceOf),
    attributions = attributions,
)

private fun NearItemDto.toModel(): NearbyItem = when (this) {
    is NearItemDto.Route -> NearbyItem.Route(
        routeId = routeId,
        name = name,
        distanceM = distanceM,
        lat = lat,
        lng = lng,
        dataSource = dataSourceOf(dataSource),
        difficulty = difficultyOf(difficulty),
        routeKm = routeKm,
        durationMin = durationMin,
        gainM = gainM,
        elevationProfileM = elevationProfileM,
        shortfall = shortfall,
        pathPolyline = pathPolyline,
        sourceCourseId = sourceCourseId,
        sido = sido,
        sigun = sigun,
        fullDistanceKm = fullDistanceKm,
    )

    is NearItemDto.Place -> NearbyItem.Place(
        name = name,
        distanceM = distanceM,
        lat = lat,
        lng = lng,
        category = category,
        address = address,
        placeUrl = placeUrl,
    )
}

fun CourseDto.toSummary(): CourseSummary = CourseSummary(
    courseId = courseId,
    courseName = courseName,
    sido = sido,
    sigun = sigun,
    distanceKm = distanceKm,
    difficulty = difficultyOf(difficulty),
    gainM = gainM,
    durationMin = durationMin,
    dataSource = dataSourceOf(dataSource),
)

/** 코스 수 내림차순은 서버가 정해 준다 — 앱은 순서를 지킨다(§6-3). */
fun CourseRegionsDto.toRegions(): List<CourseRegion> =
    items.map { CourseRegion(it.region, it.count) }

private fun difficultyOf(raw: String?): Difficulty? =
    Difficulty.entries.firstOrNull { it.name == raw }

private fun dataSourceOf(raw: String?): CourseDataSource? =
    CourseDataSource.entries.firstOrNull { it.name == raw }

private fun courseSourceOf(raw: String): CourseSource? =
    CourseSource.entries.firstOrNull { it.name == raw }
