package com.runninggu.app.data.repository

import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.model.Difficulty
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.data.remote.SavedCourseApi
import com.runninggu.app.data.remote.dto.PageDto
import com.runninggu.app.data.remote.dto.SaveCourseRequestDto
import com.runninggu.app.data.remote.dto.SaveCourseResponseDto
import com.runninggu.app.data.remote.dto.SavedCourseDetailDto
import com.runninggu.app.data.remote.dto.SavedCourseDto
import kotlinx.coroutines.runBlocking
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 저장 코스 창구. (API 명세 §7-A) */
class RemoteSavedCourseRepositoryTest {

    private class FakeApi(
        private val response: SaveCourseResponseDto = SaveCourseResponseDto(1, created = true),
    ) : SavedCourseApi {
        var saved: SaveCourseRequestDto? = null
        var deleted: Long? = null

        override suspend fun save(body: SaveCourseRequestDto): SaveCourseResponseDto {
            saved = body
            return response
        }

        override suspend fun list(page: Int?, size: Int?) = PageDto(
            content = listOf(
                SavedCourseDto(
                    id = 7,
                    courseName = "해파랑길 1코스",
                    distanceKm = 17.8,
                    durationMin = 162,
                    gainM = 312,
                    savedAt = Instant.parse("2026-08-19T15:30:00Z"),
                ),
            ),
            page = PageDto.PageMeta(number = 0, size = 20, totalElements = 3, hasNext = false),
        )

        override suspend fun detail(id: Long) = SavedCourseDetailDto(
            id = id,
            courseName = "c",
            distanceKm = 5.0,
            durationMin = 45,
            gainM = 10,
            // 상세에는 항상 온다 (§7-A). 여의도 근처 3점 — 지도가 그릴 수 있는 최소 형태다
            pathPolyline = "{b`dFgeueW{DgG{DiG",
            savedAt = Instant.parse("2026-08-19T15:30:00Z"),
        )

        override suspend fun delete(id: Long) {
            deleted = id
        }
    }

    private fun route(polyline: String? = "경로") = NearbyItem.Route(
        routeId = "osm:1",
        name = "코스",
        distanceM = 10,
        lat = 37.5,
        lng = 127.0,
        dataSource = CourseDataSource.OSM_GENERATED,
        difficulty = Difficulty.EASY,
        routeKm = 5.0,
        durationMin = 45,
        gainM = 20,
        elevationProfileM = emptyList(),
        shortfall = false,
        pathPolyline = polyline,
    )

    @Test
    fun `같은 경로를 다시 저장하면 기존 id 가 온다`() = runBlocking {
        // 서버가 fingerprint 로 판정한다 — 200 + created=false (§7-A)
        val api = FakeApi(SaveCourseResponseDto(id = 42, created = false))

        val result = RemoteSavedCourseRepository(api).save(route())

        assertEquals(42L, result?.id)
        assertFalse(result!!.created)
    }

    @Test
    fun `경로가 없으면 서버를 부르지 않는다`() = runBlocking {
        val api = FakeApi()

        val result = RemoteSavedCourseRepository(api).save(route(polyline = null))

        assertNull(result)
        assertNull(api.saved)
    }

    @Test
    fun `목록은 전체 건수와 다음 장 여부를 함께 준다`() = runBlocking {
        val page = RemoteSavedCourseRepository(FakeApi()).list()

        assertEquals(1, page.courses.size)
        assertEquals(3L, page.totalElements)
        assertFalse(page.hasNext)
        assertEquals("해파랑길 1코스", page.courses.single().courseName)
    }

    @Test
    fun `삭제는 id 를 그대로 보낸다`() = runBlocking {
        val api = FakeApi()

        RemoteSavedCourseRepository(api).delete(9)

        assertEquals(9L, api.deleted)
        assertTrue(true)
    }
}
