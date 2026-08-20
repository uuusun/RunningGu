package com.runninggu.app.data.repository

import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.model.CourseRegion
import com.runninggu.app.data.model.CourseSummary
import com.runninggu.app.data.model.Difficulty
import com.runninggu.app.data.model.NearbyCourses
import com.runninggu.app.data.model.NearbyItem

/**
 * 백엔드 `/api/courses` 가 준비되기 전까지 쓰는 스텁. (AP-12 · 매핑표 §12)
 *
 * `FakePoiRepository` 와 같은 방식이다 — 화면이 Repository 로 붙어 있으면 서버가 생겼을 때
 * [RemoteCourseRepository] 로 바꾸기만 하면 되고 화면은 그대로다(AGENTS 4장).
 *
 * **데모용 값이라 실제 좌표·거리와 맞지 않는다.** 서울 반경 8km 에 두루누비 코스가 0건인
 * 실제 상황(SPEC §4.11 📌)을 흉내내려고 OSM 생성 경로 1건 + 걷기 스팟으로 구성했다.
 */
object FakeCourseRepository : CourseRepository {

    override suspend fun near(
        lat: Double,
        lng: Double,
        targetKm: Double,
        radiusKm: Double,
        size: Int,
    ): NearbyCourses {
        val routeKm = targetKm
        return NearbyCourses(
            // 서버가 distanceM 오름차순으로 섞어 준 순서를 흉내낸다
            items = listOf(
                NearbyItem.Route(
                    routeId = "osm:demo-1",
                    name = "내 주변 ${targetKm.toInt()}km 평지 러닝코스",
                    distanceM = 12,
                    lat = lat,
                    lng = lng,
                    dataSource = CourseDataSource.OSM_GENERATED,
                    difficulty = Difficulty.EASY,
                    routeKm = routeKm,
                    durationMin = (routeKm * 1000 / 110).toInt(),
                    gainM = (routeKm * 12).toInt(),
                    elevationProfileM = listOf(12, 14, 17, 15, 19, 18, 16, 13),
                    shortfall = false,
                    pathPolyline = null,
                ),
                NearbyItem.Place(
                    name = "여의도공원",
                    distanceM = 650,
                    lat = lat + 0.002,
                    lng = lng + 0.002,
                    category = "공원",
                    address = "서울 영등포구 여의공원로 68",
                    placeUrl = "https://place.map.kakao.com/",
                ),
                NearbyItem.Place(
                    name = "샛강생태공원",
                    distanceM = 1240,
                    lat = lat - 0.004,
                    lng = lng + 0.001,
                    category = "공원",
                    address = "서울 영등포구 여의동로 3",
                    placeUrl = "https://place.map.kakao.com/",
                ),
            ),
            attributions = listOf("© OpenStreetMap contributors", "카카오 로컬"),
        )
    }

    override suspend fun byRegion(region: String?, page: Int, size: Int): CoursePage {
        val all = DEMO_COURSES.filter { region == null || it.sido == region }
        val sorted = all.sortedBy { it.distanceKm }
        return CoursePage(courses = sorted, hasNext = false, totalElements = sorted.size.toLong())
    }

    override suspend fun regions(): List<CourseRegion> = DEMO_REGIONS

    /** 코스 수 내림차순 — 서버가 정하는 순서를 흉내낸다. (§6-3) */
    private val DEMO_REGIONS = listOf(
        CourseRegion("부산", 27),
        CourseRegion("전남", 24),
        CourseRegion("경남", 19),
        CourseRegion("강원", 12),
    )

    private val DEMO_COURSES = listOf(
        CourseSummary(
            courseId = "T_CRS_MNG0000005117",
            courseName = "남파랑길 2코스",
            sido = "부산",
            sigun = "부산 중구",
            distanceKm = 19.0,
            difficulty = Difficulty.NORMAL,
            gainM = 320,
            durationMin = 173,
            dataSource = CourseDataSource.API_GPX,
        ),
        CourseSummary(
            courseId = "T_CRS_MNG0000005118",
            courseName = "갈맷길 3-1구간",
            sido = "부산",
            sigun = "부산 사하구",
            distanceKm = 8.4,
            difficulty = Difficulty.EASY,
            gainM = 60,
            durationMin = 76,
            dataSource = CourseDataSource.API_GPX,
        ),
        CourseSummary(
            courseId = "T_CRS_MNG0000004411",
            courseName = "해파랑길 39코스",
            sido = "강원",
            sigun = "강릉시",
            distanceKm = 4.7,
            difficulty = Difficulty.EASY,
            gainM = 30,
            durationMin = 43,
            dataSource = CourseDataSource.API_GPX,
        ),
        CourseSummary(
            courseId = "forest-jirisan-1",
            courseName = "지리산둘레길 1코스",
            sido = "경남",
            sigun = "함양군",
            distanceKm = 14.7,
            difficulty = Difficulty.HARD,
            gainM = 780,
            durationMin = 134,
            dataSource = CourseDataSource.GPX_ONLY,
        ),
    )
}
