package com.runninggu.app.ui.course

import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.model.CourseSource
import com.runninggu.app.data.model.CourseSummary
import com.runninggu.app.data.model.Difficulty
import com.runninggu.app.data.model.NearbyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * S8 화면 계약. (SPEC §4.11)
 *
 * 카드 문구와 슬라이더 범위는 명세에 못 박힌 값이라 눈으로만 확인하면 조용히 어긋난다.
 */
class CourseFormatTest {

    private fun route(
        routeKm: Double = 5.0,
        durationMin: Int = 46,
        gainM: Int = 38,
        difficulty: Difficulty? = Difficulty.EASY,
        shortfall: Boolean = false,
        elevation: List<Int> = listOf(1, 2, 3),
    ) = NearbyItem.Route(
        routeId = "r1",
        name = "내 주변 5km 평지 러닝코스",
        distanceM = 12,
        lat = 37.5,
        lng = 126.9,
        dataSource = CourseDataSource.OSM_GENERATED,
        difficulty = difficulty,
        routeKm = routeKm,
        durationMin = durationMin,
        gainM = gainM,
        elevationProfileM = elevation,
        shortfall = shortfall,
        pathPolyline = "x",
    )

    // ── 목표 거리 (§4.11-2) ─────────────────────────────────

    @Test
    fun `목표 거리는 0_5 단위로 맞춰진다`() {
        assertEquals(5.0, snapTargetKm(5.1), 1e-9)
        assertEquals(5.5, snapTargetKm(5.4), 1e-9)
        assertEquals(5.5, snapTargetKm(5.6), 1e-9)
    }

    @Test
    fun `목표 거리는 1에서 21 사이로 잘린다`() {
        assertEquals(1.0, snapTargetKm(0.2), 1e-9)
        assertEquals(21.0, snapTargetKm(30.0), 1e-9)
    }

    @Test
    fun `슬라이더 눈금이 0_5 단위와 맞는다`() {
        // 1~21km 를 0.5 단위로 나누면 41개 값 — 사이 눈금은 39개다
        assertEquals(39, TARGET_SLIDER_STEPS)
    }

    @Test
    fun `거리 표기는 정수면 소수점을 뗀다`() {
        assertEquals("5", formatKm(5.0))
        assertEquals("5.5", formatKm(5.5))
    }

    @Test
    fun `장소 거리는 1km 를 기준으로 단위가 바뀐다`() {
        assertEquals("650m", formatDistance(650))
        assertEquals("1.2km", formatDistance(1240))
    }

    // ── 카드 문구 (§4.11-5) ────────────────────────────────

    @Test
    fun `경로 카드는 거리 시간 난이도 상승을 보여준다`() {
        assertEquals("5km · 약 46분 · 평지 · 상승 38m", nearbySubtitle(route()))
    }

    @Test
    fun `난이도가 없으면 그 자리만 뺀다`() {
        assertEquals("5km · 약 46분 · 상승 38m", nearbySubtitle(route(difficulty = null)))
    }

    @Test
    fun `장소 카드는 카테고리와 거리를 보여준다`() {
        val place = NearbyItem.Place(
            name = "여의도공원",
            distanceM = 650,
            lat = 37.5,
            lng = 126.9,
            category = "공원",
            address = "서울 영등포구",
            placeUrl = null,
        )

        assertEquals("공원 · 650m", nearbySubtitle(place))
    }

    @Test
    fun `지역별 카드는 시군 거리 난이도 시간을 보여준다`() {
        val course = CourseSummary(
            courseId = "c1",
            courseName = "남파랑길 2코스",
            sido = "부산",
            sigun = "부산 중구",
            distanceKm = 19.0,
            difficulty = Difficulty.NORMAL,
            gainM = 320,
            durationMin = 173,
            dataSource = CourseDataSource.API_GPX,
        )

        assertEquals("부산 중구 · 19km · 완만 · 약 173분", courseSubtitle(course))
    }

    // ── 상태 파생 (§4.11-6·7) ──────────────────────────────

    @Test
    fun `경로가 하나도 없으면 안내를 붙인다`() {
        val onlyPlaces = NearbyState.Content(
            items = listOf(
                NearbyItem.Place("여의도공원", 650, 37.5, 126.9, "공원", null, null),
            ),
            attributions = listOf("카카오 로컬"),
        )

        assertTrue(onlyPlaces.hasNoRoute)
    }

    @Test
    fun `경로가 하나라도 있으면 안내를 안 붙인다`() {
        val withRoute = NearbyState.Content(items = listOf(route()), attributions = emptyList())

        assertFalse(withRoute.hasNoRoute)
    }

    @Test
    fun `OSM 만 실패하고 장소가 있으면 전용 문구를 쓴다`() {
        // §4.11-7 에 문구가 못 박혀 있다
        val state = NearbyState.Content(
            items = listOf(NearbyItem.Place("여의도공원", 650, 37.5, 126.9, "공원", null, null)),
            attributions = listOf("카카오 로컬"),
            degradedSources = listOf(CourseSource.OSM),
        )

        assertEquals("자동 경로를 만들지 못해 주변 장소를 보여드려요.", state.degradedMessage)
    }

    @Test
    fun `실패한 원천이 없으면 안내가 없다`() {
        val state = NearbyState.Content(items = listOf(route()), attributions = emptyList())

        assertNull(state.degradedMessage)
    }

    @Test
    fun `거리 표기는 기기 로캘을 타지 않는다`() {
        // 소수점을 "," 로 찍는 로캘에서도 "1.2km" 여야 한다
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.2km", formatDistance(1234))
            assertEquals("980m", formatDistance(980))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `프리셋은 5개다`() {
        // 위치 권한을 거부해도 이걸로 동작해야 한다 (NFR-15 · §4.11-1)
        assertEquals(5, ORIGIN_PRESETS.size)
        assertEquals(
            listOf("부산 해운대", "여수", "강릉", "인천 강화", "서울시청"),
            ORIGIN_PRESETS.map { it.name },
        )
    }
}
