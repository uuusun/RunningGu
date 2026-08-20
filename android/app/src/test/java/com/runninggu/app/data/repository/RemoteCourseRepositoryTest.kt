package com.runninggu.app.data.repository

import com.runninggu.app.data.remote.CourseApi
import com.runninggu.app.data.remote.dto.CourseDto
import com.runninggu.app.data.remote.dto.CourseRegionsDto
import com.runninggu.app.data.remote.dto.CoursesNearDto
import com.runninggu.app.data.remote.dto.CoursePageDto
import com.runninggu.app.data.remote.dto.PageDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 지역별 페이지 매핑. (API 명세 §6-2 · SPEC §4.11-b)
 *
 * 화면이 "{지역} 코스 N" 을 그리는데 N 은 **전체 건수**다. 페이지 안의 개수로 세면
 * 20건 넘는 지역에서 조용히 틀어지므로 여기서 고정한다.
 */
class RemoteCourseRepositoryTest {

    @Test
    fun `전체 건수는 페이지 개수가 아니라 totalElements 를 쓴다`() = runBlocking {
        val api = FakeApi(
            CoursePageDto(
                content = List(20) { courseDto("c$it") },
                page = PageDto.PageMeta(number = 0, size = 20, totalElements = 137, hasNext = true),
                attributions = listOf("두루누비 걷기길(한국관광공사)"),
            ),
        )

        val page = RemoteCourseRepository(api).byRegion(region = "경기")

        assertEquals(20, page.courses.size)
        assertEquals(137L, page.totalElements)
        assertTrue(page.hasNext)
        // 출처 문구가 모델까지 살아 온다 (§6-2 · 결정-44)
        assertEquals(listOf("두루누비 걷기길(한국관광공사)"), page.attributions)
    }

    private fun courseDto(id: String) = CourseDto(courseId = id, courseName = id, distanceKm = 5.0)

    private class FakeApi(private val page: CoursePageDto) : CourseApi {
        override suspend fun near(
            lat: Double,
            lng: Double,
            targetKm: Double,
            radiusKm: Double?,
            size: Int?,
        ): CoursesNearDto = CoursesNearDto()

        override suspend fun byRegion(region: String?, page: Int?, size: Int?): CoursePageDto =
            this.page

        override suspend fun regions(): CourseRegionsDto = CourseRegionsDto()
    }
}
