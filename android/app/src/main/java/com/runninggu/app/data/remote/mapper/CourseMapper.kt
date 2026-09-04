package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.model.CourseRegion
import com.runninggu.app.data.model.CourseSource
import com.runninggu.app.data.model.CuratedCourseDetail
import com.runninggu.app.data.remote.dto.CourseDetailDto
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
        // 와이어 형식을 푸는 것은 매퍼의 일이다 (AGENTS 2장-4 · #129).
        path = pathPolyline?.let { Polyline.decode(it) }.orEmpty(),
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

/** 코스·저장 코스가 함께 쓴다. 모르는 값은 null 로 둔다. */
internal fun difficultyOf(raw: String?): Difficulty? =
    Difficulty.entries.firstOrNull { it.name == raw }

internal fun dataSourceOf(raw: String?): CourseDataSource? =
    CourseDataSource.entries.firstOrNull { it.name == raw }

private fun courseSourceOf(raw: String): CourseSource? =
    CourseSource.entries.firstOrNull { it.name == raw }

/**
 * 큐레이션 코스 상세. (#280 계약)
 *
 * **폴리라인은 원문을 그대로 들고 가면서 좌표도 같이 푼다.** 화면은 좌표를 쓰고, 원문은
 * 서버에 되돌려 보낼 일이 있을 때 쓴다 — 풀었다 다시 묶으면 값이 달라진다(#62 · §7-A).
 *
 * 디코더는 깨진 입력에 예외를 던지지 않고 읽은 만큼만 돌려준다. 코스 상세가 통째로
 * 안 열리는 것보다 낫기 때문이다(NFR-1·3 · #209 와 같은 판단).
 */
fun CourseDetailDto.toDomain(): CuratedCourseDetail = CuratedCourseDetail(
    courseId = courseId,
    courseName = courseName,
    sido = sido,
    sigun = sigun,
    distanceKm = distanceKm,
    difficulty = difficultyOf(difficulty),
    gainM = gainM,
    durationMin = durationMin,
    dataSource = dataSourceOf(dataSource),
    syncedAt = syncedAt,
    pathPolyline = pathPolyline,
    path = Polyline.decode(pathPolyline),
    elevationProfileM = elevationProfileM,
    attributions = attributions,
)
