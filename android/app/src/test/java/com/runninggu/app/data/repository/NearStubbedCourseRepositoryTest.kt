package com.runninggu.app.data.repository

import com.runninggu.app.data.model.CourseRegion
import com.runninggu.app.data.model.CourseSummary
import com.runninggu.app.data.model.NearbyCourses
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 서버가 반쪽만 선 동안의 조합. (AP-12 · AP-25)
 *
 * `GET /api/courses`(지역별)·`/regions` 는 #156 으로 섰고 `/courses/near` 는 아직 없다.
 * **어느 쪽으로 가는지가 전부라** 그것만 고정한다 — 잘못 이으면 [내 주변]이 없는
 * 엔드포인트를 불러 오류만 뜨거나, 지역별이 스텁을 보여 서버 데이터가 안 나온다.
 */
class NearStubbedCourseRepositoryTest {

    private val remote = RecordingCourseRepository(regionName = "서버")
    private val stub = RecordingCourseRepository(regionName = "스텁")
    private val repository = NearStubbedCourseRepository(remote = remote, stub = stub)

    @Test
    fun `내 주변은 스텁이 받는다`() = runTest {
        // /courses/near 가 아직 없다. 서버로 보내면 화면이 오류만 보여준다
        repository.near(lat = 37.5, lng = 126.9)

        assertTrue(stub.nearCalled)
        assertTrue("서버에는 near 엔드포인트가 없다", !remote.nearCalled)
    }

    @Test
    fun `지역별은 서버가 받는다`() = runTest {
        val page = repository.byRegion(region = "부산", page = 0, size = 20)

        assertTrue(remote.byRegionCalled)
        assertEquals("서버", page.courses.firstOrNull()?.courseName)
    }

    @Test
    fun `지역 칩도 서버가 받는다`() = runTest {
        val regions = repository.regions()

        assertTrue(remote.regionsCalled)
        assertEquals("서버", regions.single().region)
    }

    @Test
    fun `지역별 인자를 그대로 넘긴다`() = runTest {
        // 페이징이 어긋나면 목록이 겹치거나 빈다
        repository.byRegion(region = "서울", page = 2, size = 50)

        assertEquals("서울", remote.lastRegion)
        assertEquals(2, remote.lastPage)
        assertEquals(50, remote.lastSize)
    }
}

/** 어느 쪽이 불렸는지 적어 두는 가짜. */
private class RecordingCourseRepository(private val regionName: String) : CourseRepository {

    var nearCalled = false
        private set
    var byRegionCalled = false
        private set
    var regionsCalled = false
        private set
    var lastRegion: String? = null
        private set
    var lastPage = -1
        private set
    var lastSize = -1
        private set

    override suspend fun near(
        lat: Double,
        lng: Double,
        targetKm: Double,
        radiusKm: Double,
        size: Int,
    ): NearbyCourses {
        nearCalled = true
        return NearbyCourses()
    }

    override suspend fun byRegion(region: String?, page: Int, size: Int): CoursePage {
        byRegionCalled = true
        lastRegion = region
        lastPage = page
        lastSize = size
        return CoursePage(
            courses = listOf(
                CourseSummary(
                    courseId = "C1",
                    courseName = regionName,
                    sido = "부산",
                    sigun = "남구",
                    distanceKm = 17.8,
                    difficulty = null,
                    gainM = null,
                    durationMin = null,
                    dataSource = null,
                ),
            ),
        )
    }

    override suspend fun regions(): List<CourseRegion> {
        regionsCalled = true
        return listOf(CourseRegion(region = regionName, count = 1))
    }
}
