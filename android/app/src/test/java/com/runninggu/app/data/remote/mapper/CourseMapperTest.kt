package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.model.CourseSource
import com.runninggu.app.data.model.Difficulty
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.dto.CoursePageDto
import com.runninggu.app.data.remote.dto.CourseRegionsDto
import com.runninggu.app.data.remote.dto.CoursesNearDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 러닝코스 API 계약. (API 명세 §6)
 *
 * 명세 §6-1 의 예시 JSON 을 그대로 넣어 고정한다. 특히 **경로와 장소가 한 목록에 섞여 오는**
 * 구조라, 종류별 전용 필드가 다른 종류에서 생략된다는 계약이 지켜지는지가 핵심이다.
 */
class CourseMapperTest {

    /** 명세 §6-1 응답 예시 그대로. */
    private val nearJson = """
        {
          "items": [
            {
              "kind": "ROUTE",
              "routeId": "osm:2e808bd75c4a",
              "dataSource": "OSM_GENERATED",
              "name": "출발지 주변 5km 평지 러닝코스",
              "distanceM": 12,
              "lat": 37.52461, "lng": 126.92028,
              "difficulty": "EASY",
              "routeKm": 5.02, "durationMin": 46,
              "gainM": 38,
              "elevationProfileM": [12, 14, 17, 15, 19, 18],
              "shortfall": false,
              "pathPolyline": "인코딩된 왕복 경로"
            },
            {
              "kind": "PLACE",
              "name": "여의도공원",
              "distanceM": 650,
              "lat": 37.5264, "lng": 126.9227,
              "category": "공원",
              "address": "서울 영등포구 여의공원로 68",
              "placeUrl": "https://place.map.kakao.com/1"
            }
          ],
          "degradedSources": [],
          "attributions": ["© OpenStreetMap contributors", "카카오 로컬"]
        }
    """.trimIndent()

    /** 여의도 3점을 precision 5 로 인코딩한 값. */
    private val realPolyline = "{b`dFgeueW{DgG{DiG"

    private fun routeJson(polyline: String) = """
        {
          "items": [
            {
              "kind": "ROUTE",
              "routeId": "osm:1",
              "name": "테스트 경로",
              "distanceM": 10,
              "lat": 37.5, "lng": 126.9,
              "routeKm": 5.0, "durationMin": 45, "gainM": 30,
              "shortfall": false,
              "pathPolyline": "$polyline"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `경로 문자열을 풀어 좌표를 함께 준다`() {
        // 와이어 형식을 푸는 것은 매퍼의 일이다 (AGENTS 2장-4 · #129).
        val near = ApiJson.decodeFromString(CoursesNearDto.serializer(), routeJson(realPolyline))
            .toNearbyCourses()
        val route = near.items.first() as NearbyItem.Route

        assertEquals(3, route.path.size)
        assertEquals(37.52510, route.path[0].lat, 1e-5)
        assertEquals(126.92580, route.path[0].lng, 1e-5)
    }

    @Test
    fun `저장에 다시 쓸 원문을 그대로 보관한다`() {
        // 풀었다 다시 묶으면 서버 routeFingerprint 가 달라져 같은 코스가 중복 저장된다.
        val near = ApiJson.decodeFromString(CoursesNearDto.serializer(), routeJson(realPolyline))
            .toNearbyCourses()
        val route = near.items.first() as NearbyItem.Route

        assertEquals(realPolyline, route.pathPolyline)
    }

    @Test
    fun `못 푸는 경로여도 항목을 버리지 않는다`() {
        // 명세 예시의 자리표시자("인코딩된 왕복 경로")처럼 폴리라인이 아닌 값이 올 수 있다.
        // 경로만 못 그리면 되고, 카드까지 사라지면 안 된다.
        val near = ApiJson.decodeFromString(CoursesNearDto.serializer(), routeJson("폴리라인 아님"))
            .toNearbyCourses()
        val route = near.items.first() as NearbyItem.Route

        assertTrue(route.path.isEmpty())
        assertEquals("폴리라인 아님", route.pathPolyline)
    }

    @Test
    fun `경로와 장소가 섞인 목록을 종류대로 읽는다`() {
        val near = ApiJson.decodeFromString(CoursesNearDto.serializer(), nearJson).toNearbyCourses()

        assertEquals(2, near.items.size)

        val route = near.items[0] as NearbyItem.Route
        assertEquals("osm:2e808bd75c4a", route.routeId)
        assertEquals(CourseDataSource.OSM_GENERATED, route.dataSource)
        assertEquals(Difficulty.EASY, route.difficulty)
        assertEquals(5.02, route.routeKm, 1e-9)
        assertEquals(46, route.durationMin)
        assertEquals(listOf(12, 14, 17, 15, 19, 18), route.elevationProfileM)
        // OSM 생성 경로는 원본이 없어 큐레이션 전용 필드가 생략된다
        assertNull(route.sourceCourseId)
        assertNull(route.sido)
        assertNull(route.fullDistanceKm)

        val place = near.items[1] as NearbyItem.Place
        assertEquals("여의도공원", place.name)
        assertEquals("공원", place.category)
    }

    @Test
    fun `서버가 정한 순서를 그대로 지킨다`() {
        // 앱이 다시 정렬하면 서버의 통합 규칙이 무의미해진다 (§6-1 · 결정-27)
        val near = ApiJson.decodeFromString(CoursesNearDto.serializer(), nearJson).toNearbyCourses()

        assertEquals(listOf(12, 650), near.items.map { it.distanceM })
    }

    @Test
    fun `출처 문구는 변형하지 않고 그대로 가져온다`() {
        // 공공누리·ODbL 출처표시 의무 — 한 글자도 바꾸면 안 된다
        val near = ApiJson.decodeFromString(CoursesNearDto.serializer(), nearJson).toNearbyCourses()

        assertEquals(listOf("© OpenStreetMap contributors", "카카오 로컬"), near.attributions)
    }

    @Test
    fun `큐레이션 경로는 원본 코스 정보를 갖는다`() {
        val raw = """
            {"items":[{"kind":"ROUTE","routeId":"dn:T_CRS_1","dataSource":"API_GPX",
                       "name":"남파랑길 2코스","distanceM":420,"lat":35.11,"lng":129.04,
                       "difficulty":"NORMAL","routeKm":5.1,"durationMin":47,"gainM":80,
                       "elevationProfileM":[],"shortfall":false,"pathPolyline":"x",
                       "sourceCourseId":"T_CRS_MNG0000005117","sido":"부산","sigun":"부산 중구",
                       "fullDistanceKm":19.0}]}
        """.trimIndent()

        val route = ApiJson.decodeFromString(CoursesNearDto.serializer(), raw)
            .toNearbyCourses().items.single() as NearbyItem.Route

        assertEquals("T_CRS_MNG0000005117", route.sourceCourseId)
        assertEquals("부산", route.sido)
        assertEquals(19.0, route.fullDistanceKm!!, 1e-9)
        assertTrue(route.elevationProfileM.isEmpty()) // 고도가 없으면 빈 배열
    }

    @Test
    fun `부분 실패는 실패한 원천만 알려준다`() {
        // 항목이 있으면 200 이고 화면은 Content + 비차단 안내다 (§6-1)
        val raw = """
            {"items":[{"kind":"PLACE","name":"여의도공원","distanceM":650,
                       "lat":37.5,"lng":126.9}],
             "degradedSources":["OSM"],
             "attributions":["카카오 로컬"]}
        """.trimIndent()

        val near = ApiJson.decodeFromString(CoursesNearDto.serializer(), raw).toNearbyCourses()

        assertEquals(listOf(CourseSource.OSM), near.degradedSources)
        assertEquals(1, near.items.size)
    }

    @Test
    fun `정상 0건은 빈 결과다`() {
        // 모든 원천이 정상인데 0건 — Empty 이지 Error 가 아니다 (§6-1)
        val near = ApiJson.decodeFromString(
            CoursesNearDto.serializer(),
            """{"items":[],"degradedSources":[],"attributions":[]}""",
        ).toNearbyCourses()

        assertTrue(near.items.isEmpty())
        assertTrue(near.degradedSources.isEmpty())
    }

    @Test
    fun `모르는 enum 이 와도 항목을 버리지 않는다`() {
        // 난이도 배지 하나 때문에 뛸 수 있는 코스가 사라지면 안 된다
        val raw = """
            {"items":[{"kind":"ROUTE","routeId":"r1","name":"n","distanceM":10,
                       "lat":37.5,"lng":126.9,"difficulty":"EXTREME","dataSource":"FUTURE",
                       "routeKm":5.0,"durationMin":45,"gainM":10,
                       "elevationProfileM":[],"shortfall":false}],
             "degradedSources":["UNKNOWN_SOURCE"]}
        """.trimIndent()

        val near = ApiJson.decodeFromString(CoursesNearDto.serializer(), raw).toNearbyCourses()
        val route = near.items.single() as NearbyItem.Route

        assertNull(route.difficulty)
        assertNull(route.dataSource)
        assertTrue(near.degradedSources.isEmpty())
    }

    @Test
    fun `지역별 응답의 출처 문구를 잃지 않는다`() {
        // 최상위 attributions 다 — 공용 PageDto 로 받으면 조용히 버려진다 (§6-2 · 결정-44)
        val raw = """
            {"content":[{"courseId":"durunubi-001","courseName":"해파랑길 1코스",
              "sido":"부산","sigun":"남구","distanceKm":17.8,"difficulty":"NORMAL",
              "gainM":312,"durationMin":162,"dataSource":"API_GPX"}],
             "page":{"number":0,"size":20,"totalElements":27,"hasNext":true},
             "attributions":["두루누비 걷기길(한국관광공사)"]}
        """.trimIndent()

        val dto = ApiJson.decodeFromString(CoursePageDto.serializer(), raw)

        assertEquals(listOf("두루누비 걷기길(한국관광공사)"), dto.attributions)
        assertEquals(27L, dto.page.totalElements)
        assertEquals("해파랑길 1코스", dto.content.single().toSummary().courseName)
    }

    @Test
    fun `지역 칩은 서버 순서를 지킨다`() {
        // 코스 수 내림차순은 서버가 정한다 (§6-3)
        val regions = ApiJson.decodeFromString(
            CourseRegionsDto.serializer(),
            """{"items":[{"region":"부산","count":27},{"region":"강원","count":12}]}""",
        ).toRegions()

        assertEquals(listOf("부산", "강원"), regions.map { it.region })
        assertEquals(27, regions.first().count)
    }
}
