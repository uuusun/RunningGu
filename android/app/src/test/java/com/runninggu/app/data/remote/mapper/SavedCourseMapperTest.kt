package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.model.Difficulty
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.dto.SavedCourseDetailDto
import com.runninggu.app.data.remote.dto.SavedCourseDto
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 저장 코스 계약. (API 명세 §7-A · 결정-44 · 이슈 #62)
 *
 * 저장 요청은 **near 응답에서 그대로 만든다.** 값을 다시 조립하면 서버가 geometry 로 계산하는
 * `routeFingerprint` 가 흔들려, 같은 코스가 두 번 저장된다.
 */
class SavedCourseMapperTest {

    private fun route(
        polyline: String? = "인코딩된왕복경로",
        dataSource: CourseDataSource? = CourseDataSource.OSM_GENERATED,
        sourceCourseId: String? = null,
        sido: String? = null,
    ) = NearbyItem.Route(
        routeId = "osm:2e808bd75c4a",
        name = "내 주변 5km 평지 러닝코스",
        distanceM = 12,
        lat = 37.52461,
        lng = 126.92028,
        dataSource = dataSource,
        difficulty = Difficulty.EASY,
        routeKm = 5.02,
        durationMin = 46,
        gainM = 38,
        elevationProfileM = listOf(12, 14, 17),
        shortfall = false,
        pathPolyline = polyline,
        sourceCourseId = sourceCourseId,
        sido = sido,
    )

    @Test
    fun `서버가 준 폴리라인을 그대로 되돌려보낸다`() {
        // 앱이 디코딩해 다시 인코딩하면 반올림 차이로 fingerprint 가 갈린다 (이슈 #62)
        val body = checkNotNull(route().toSaveRequest())

        assertEquals("인코딩된왕복경로", body.pathPolyline)
    }

    @Test
    fun `near 응답 값을 그대로 옮긴다`() {
        val body = checkNotNull(route().toSaveRequest())

        assertEquals("내 주변 5km 평지 러닝코스", body.courseName)
        assertEquals(5.02, body.distanceKm, 1e-9)
        assertEquals(46, body.durationMin)
        assertEquals(38, body.gainM)
        assertEquals(listOf(12, 14, 17), body.elevationProfileM)
        // 진입점은 경로 시작점이다 — 목록에서 거리를 재는 기준이 된다
        assertEquals(37.52461, body.entryLat, 1e-7)
        assertEquals("OSM_GENERATED", body.dataSource)
        assertEquals("EASY", body.difficulty)
    }

    @Test
    fun `OSM 경로는 원본 코스 id 와 지역이 없다`() {
        // 큐레이션에만 있는 값이다 (§7-A)
        val body = checkNotNull(route().toSaveRequest())

        assertNull(body.sourceCourseId)
        assertNull(body.region)
    }

    @Test
    fun `큐레이션 경로는 원본 코스 id 와 지역을 함께 보낸다`() {
        val body = checkNotNull(
            route(
                dataSource = CourseDataSource.API_GPX,
                sourceCourseId = "durunubi-001",
                sido = "부산",
            ).toSaveRequest(),
        )

        assertEquals("durunubi-001", body.sourceCourseId)
        assertEquals("부산", body.region)
    }

    @Test
    fun `경로가 없으면 저장 요청을 만들지 않는다`() {
        // geometry 가 없으면 서버가 fingerprint 를 만들 수 없다
        assertNull(route(polyline = null).toSaveRequest())
        assertNull(route(dataSource = null).toSaveRequest())
    }

    @Test
    fun `상세 응답의 출처와 경로를 읽는다`() {
        val raw = """
            {"id":42,"courseName":"해파랑길 1코스","distanceKm":17.8,"durationMin":162,
             "gainM":312,"difficulty":"NORMAL","dataSource":"API_GPX","region":"부산",
             "elevationProfileM":[10,20],"pathPolyline":"경로",
             "attributions":["두루누비 걷기길(한국관광공사)"],
             "savedAt":"2026-08-19T15:30:00Z"}
        """.trimIndent()

        val detail = ApiJson.decodeFromString(SavedCourseDetailDto.serializer(), raw).toDomain()

        assertEquals(42L, detail.course.id)
        assertEquals(listOf("두루누비 걷기길(한국관광공사)"), detail.attributions)
        assertEquals("경로", detail.pathPolyline)
        // UTC 자정 넘김 — KST 로 접는다 (AGENTS 2장-4)
        assertEquals(LocalDate.of(2026, 8, 20), detail.course.savedAt)
        assertEquals(Difficulty.NORMAL, detail.course.difficulty)
    }

    @Test
    fun `필수 필드가 빠진 목록 응답은 거부한다`() {
        // 기본값을 두면 서버가 savedAt 대신 createdAt 을 주거나 빠뜨려도 null·0 으로
        // 조용히 통과해 계약 불일치가 숨는다 (#76 리뷰)
        val missingSavedAt = """
            {"id":1,"courseName":"c","distanceKm":5.0,"durationMin":45,"gainM":10}
        """.trimIndent()

        assertThrows(SerializationException::class.java) {
            ApiJson.decodeFromString(SavedCourseDto.serializer(), missingSavedAt)
        }
    }

    @Test
    fun `목록 항목을 계약대로 읽는다`() {
        val raw = """
            {"id":42,"courseName":"해파랑길 1코스","distanceKm":17.8,"durationMin":162,
             "gainM":312,"difficulty":"NORMAL","dataSource":"API_GPX","region":"부산",
             "savedAt":"2026-08-19T15:30:00Z"}
        """.trimIndent()

        val course = ApiJson.decodeFromString(SavedCourseDto.serializer(), raw).toDomain()

        assertEquals(42L, course.id)
        assertEquals(LocalDate.of(2026, 8, 20), course.savedAt) // UTC → KST
        assertEquals("부산", course.region)
    }

    @Test
    fun `출처가 없으면 빈 목록이다`() {
        // 서버가 [] 로 준다 — null 분기를 만들지 않는다 (결정-44)
        val raw = """{"id":1,"courseName":"c","distanceKm":5.0,"durationMin":45,"gainM":10,
             "savedAt":"2026-08-19T15:30:00Z"}"""

        val detail = ApiJson.decodeFromString(SavedCourseDetailDto.serializer(), raw).toDomain()

        assertTrue(detail.attributions.isEmpty())
        assertNull(detail.pathPolyline)
    }
}
