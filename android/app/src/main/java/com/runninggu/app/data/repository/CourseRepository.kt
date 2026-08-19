package com.runninggu.app.data.repository

import com.runninggu.app.data.model.CourseRegion
import com.runninggu.app.data.model.CourseSummary
import com.runninggu.app.data.model.NearbyCourses
import com.runninggu.app.data.remote.CourseApi
import com.runninggu.app.data.remote.apiCall
import com.runninggu.app.data.remote.mapper.toNearbyCourses
import com.runninggu.app.data.remote.mapper.toRegions
import com.runninggu.app.data.remote.mapper.toSummary

/**
 * 러닝코스 조회 창구. (API 명세 §6 · SPEC §4.11)
 *
 * 화면은 이 인터페이스만 본다. 내 주변은 **한 번의 호출**로 경로와 장소를 함께 받는다.
 */
interface CourseRepository {

    /**
     * 내 주변 경로·장소. (§6-1)
     *
     * 결과가 비어 있어도 실패가 아니다 — 화면은 Empty 로 그린다.
     * 원천 실패로 표시할 게 하나도 없으면 서버가 `503 COURSE_SOURCES_UNAVAILABLE` 을 주고
     * `ApiException.Http` 로 올라온다.
     */
    suspend fun near(
        lat: Double,
        lng: Double,
        targetKm: Double = CourseApi.DEFAULT_TARGET_KM,
        radiusKm: Double = CourseApi.NEAR_RADIUS_KM,
        size: Int = CourseApi.NEAR_SIZE,
    ): NearbyCourses

    /** 지역별 목록. 큐레이션만. (§6-2) */
    suspend fun byRegion(region: String?, page: Int = 0, size: Int = DEFAULT_PAGE_SIZE): CoursePage

    /** 지역 칩. (§6-3) */
    suspend fun regions(): List<CourseRegion>

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
    }
}

data class CoursePage(
    val courses: List<CourseSummary> = emptyList(),
    val hasNext: Boolean = false,
)

/** 서버 구현. */
class RemoteCourseRepository(private val api: CourseApi) : CourseRepository {

    override suspend fun near(
        lat: Double,
        lng: Double,
        targetKm: Double,
        radiusKm: Double,
        size: Int,
    ): NearbyCourses = apiCall {
        api.near(
            lat = lat,
            lng = lng,
            // 슬라이더가 0.5 단위라 계약 범위를 벗어난 값이 나가지 않게 여기서 한 번 막는다
            targetKm = targetKm.coerceIn(CourseApi.MIN_TARGET_KM, CourseApi.MAX_TARGET_KM),
            radiusKm = radiusKm,
            size = size,
        ).toNearbyCourses()
    }

    override suspend fun byRegion(region: String?, page: Int, size: Int): CoursePage = apiCall {
        val dto = api.byRegion(region = region?.takeIf { it.isNotBlank() }, page = page, size = size)
        CoursePage(
            courses = dto.content.map { it.toSummary() },
            hasNext = dto.page.hasNext,
        )
    }

    override suspend fun regions(): List<CourseRegion> = apiCall { api.regions().toRegions() }
}
