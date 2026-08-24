package com.runninggu.app.data.repository

import com.runninggu.app.data.model.CourseRegion
import com.runninggu.app.data.model.CourseSummary
import com.runninggu.app.data.model.CourseTargetKm
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
        targetKm: Double = CourseTargetKm.DEFAULT,
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
}

/**
 * [내 주변]만 스텁을 쓰고 나머지는 서버를 보는 조합. (AP-12 · AP-25)
 *
 * **서버가 반쪽만 서 있어서 생기는 한시적인 물건이다.** `GET /api/courses`(지역별)와
 * `/regions` 는 #156 으로 섰지만, `/courses/near` 는 AP-25(OSM 도시 경로 생성)에
 * 묶여 있어 아직 없다. 하나가 없다고 둘 다 스텁으로 두면 이미 선 계약을 놀리게 되고,
 * 반대로 통째로 서버를 보게 하면 [내 주변]이 열자마자 오류만 남는다.
 *
 * `near` 만 [stub] 으로 보내고 나머지는 [remote] 가 받는다.
 *
 * **AP-25 가 서면 이 클래스를 지우고** `ServiceLocator` 가 [RemoteCourseRepository] 를
 * 그대로 주면 된다. 화면은 [CourseRepository] 만 보므로 안 바뀐다(AGENTS 4장).
 */
class NearStubbedCourseRepository(
    private val remote: CourseRepository,
    private val stub: CourseRepository = FakeCourseRepository,
) : CourseRepository {

    override suspend fun near(
        lat: Double,
        lng: Double,
        targetKm: Double,
        radiusKm: Double,
        size: Int,
    ): NearbyCourses = stub.near(lat, lng, targetKm, radiusKm, size)

    override suspend fun byRegion(region: String?, page: Int, size: Int): CoursePage =
        remote.byRegion(region, page, size)

    override suspend fun regions(): List<CourseRegion> = remote.regions()
}

