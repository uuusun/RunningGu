package com.runninggu.app.data.repository

import com.runninggu.app.data.model.CourseRegion
import com.runninggu.app.data.model.CuratedCourseDetail
import com.runninggu.app.data.model.CourseSummary
import com.runninggu.app.data.model.CourseTargetKm
import com.runninggu.app.data.model.NearbyCourses
import com.runninggu.app.data.remote.CourseApi
import com.runninggu.app.data.remote.apiCall
import com.runninggu.app.data.remote.mapper.toNearbyCourses
import com.runninggu.app.data.remote.mapper.toDomain
import com.runninggu.app.data.remote.mapper.toRegions
import com.runninggu.app.data.remote.mapper.toSummary

/**
 * 러닝코스 조회 창구. (API 명세 §6 · SPEC §4.11)
 *
 * 화면은 이 인터페이스만 본다. 출발지 주변은 **한 번의 호출**로 경로와 장소를 함께 받는다.
 */
interface CourseRepository {

    /**
     * 출발지 주변 경로·장소. (§6-1)
     *
     * 결과가 비어 있어도 실패가 아니다 — 화면은 Empty 로 그린다.
     * 원천 실패로 표시할 게 하나도 없으면 서버가 `503 COURSE_SOURCES_UNAVAILABLE` 을 주고
     * `ApiException.Http` 로 올라온다.
     */
    suspend fun near(
        lat: Double,
        lng: Double,
        targetKm: Double = CourseTargetKm.DEFAULT,
        radiusKm: Double = CourseApi.NEAR_RADIUS_KM,
        size: Int = CourseApi.NEAR_SIZE,
    ): NearbyCourses

    /** 지역별 목록. 큐레이션만. (§6-2) */
    suspend fun byRegion(region: String?, page: Int = 0, size: Int = DEFAULT_PAGE_SIZE): CoursePage

    /** 지역 칩. (§6-3) */
    suspend fun regions(): List<CourseRegion>

    /**
     * 큐레이션 코스 상세. (`GET /api/courses/{courseId}` · #280)
     *
     * 지역별 목록이 좌표를 안 주기 때문에 필요하다 — 목록만으로는 코스를 눌러도
     * 어디인지 알 수 없다. 없는 id 는 `404 COURSE_NOT_FOUND` 로 온다.
     */
    suspend fun detail(courseId: String): CuratedCourseDetail

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
    }
}

data class CoursePage(
    val courses: List<CourseSummary> = emptyList(),
    val hasNext: Boolean = false,
    /**
     * 조건에 맞는 전체 코스 수. **이 페이지의 개수가 아니다.**
     *
     * 지역별 화면이 "{지역} 코스 N" 을 그린다(SPEC §4.11-b) — 20건씩 끊어 받으므로
     * `courses.size` 로 세면 21건 이상인 지역에서 N 이 틀어진다.
     */
    val totalElements: Long = 0,
    /**
     * 목록 하단 출처 한 줄. (SPEC §4.11-b · 결정-44)
     *
     * 공공누리·ODbL 출처표시 의무라 **문구를 변형하지 않고 그대로** 표시한다.
     */
    val attributions: List<String> = emptyList(),
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
            targetKm = targetKm.coerceIn(CourseTargetKm.MIN, CourseTargetKm.MAX),
            radiusKm = radiusKm,
            size = size,
        ).toNearbyCourses()
    }

    override suspend fun byRegion(region: String?, page: Int, size: Int): CoursePage = apiCall {
        val dto = api.byRegion(region = region?.takeIf { it.isNotBlank() }, page = page, size = size)
        CoursePage(
            courses = dto.content.map { it.toSummary() },
            hasNext = dto.page.hasNext,
            totalElements = dto.page.totalElements,
            attributions = dto.attributions,
        )
    }

    override suspend fun regions(): List<CourseRegion> = apiCall { api.regions().toRegions() }

    override suspend fun detail(courseId: String): CuratedCourseDetail =
        apiCall { api.detail(courseId).toDomain() }
}
