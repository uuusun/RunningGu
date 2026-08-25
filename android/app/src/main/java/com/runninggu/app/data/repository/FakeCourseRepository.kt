package com.runninggu.app.data.repository

import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.model.CourseRegion
import com.runninggu.app.data.model.CourseSummary
import com.runninggu.app.data.model.Difficulty
import com.runninggu.app.data.model.NearbyCourses
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.domain.LatLng
import kotlin.math.cos
import kotlin.math.sin

/**
 * 데모용 코스 스텁. (AP-12 · 매핑표 §12)
 *
 * **운영 배선에서는 빠졌다** — `/api/courses` 세 갈래가 다 서서 [ServiceLocator] 는
 * [RemoteCourseRepository] 를 준다. 지금 이걸 쓰는 곳은 테스트뿐이다. `FakeAuthRepository`
 * 처럼 남겨 둔다 — 서버 없이 화면만 돌려 볼 때 쓸 데가 있다.
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
                    // 지도에 그릴 것이 있어야 S8 을 눈으로 확인할 수 있다. **원문 대신
                    // 좌표를 바로 채운다** — 여기는 와이어 형식을 거치지 않는 스텁이라
                    // 인코딩할 이유가 없고, 인코더도 앱에 없다(디코더만 있다).
                    path = demoLoop(lat, lng, routeKm),
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

    /** 서버처럼 잘라서 준다 — 그래야 화면의 [더 보기] 를 스텁으로도 눌러 볼 수 있다. */
    override suspend fun byRegion(region: String?, page: Int, size: Int): CoursePage {
        val sorted = DEMO_COURSES
            .filter { region == null || it.sido == region }
            .sortedBy { it.distanceKm }
        val from = (page * size).coerceIn(0, sorted.size)
        val to = (from + size).coerceIn(0, sorted.size)
        return CoursePage(
            courses = sorted.subList(from, to),
            hasNext = to < sorted.size,
            totalElements = sorted.size.toLong(),
        )
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

/**
 * 출발지를 도는 대략적인 순환 경로. **스텁 전용이다.**
 *
 * 서버 `/api/courses/near` 가 서면 실제 경로가 오므로 이 함수는 그때 지워진다. 그때까지
 * S8 지도를 눈으로 확인할 방법이 없어서 둔다 — 도로를 따르지 않고 원을 그릴 뿐이다.
 *
 * 반지름은 둘레가 [routeKm] 가 되게 잡는다(`r = C / 2π`). 위도 1도는 약 111km 이고,
 * 경도는 위도가 올라갈수록 좁아져 `cos(위도)` 로 나눈다.
 */
private fun demoLoop(lat: Double, lng: Double, routeKm: Double): List<LatLng> {
    val radiusKm = routeKm / (2 * Math.PI)
    val dLat = radiusKm / 111.0
    val dLng = dLat / cos(Math.toRadians(lat)).coerceAtLeast(MIN_COS)
    return (0..LOOP_POINTS).map { step ->
        val angle = 2 * Math.PI * step / LOOP_POINTS
        LatLng(lat + dLat * sin(angle), lng + dLng * cos(angle))
    }
}

/** 극지방에서 0 으로 나누는 것을 막는다. 우리 서비스 범위에서는 닿지 않는 값이다. */
private const val MIN_COS = 0.01

/** 원으로 보일 만큼만. 스텁이라 더 촘촘할 이유가 없다. */
private const val LOOP_POINTS = 36
