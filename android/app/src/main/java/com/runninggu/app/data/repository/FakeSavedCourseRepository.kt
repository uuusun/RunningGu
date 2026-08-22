package com.runninggu.app.data.repository

import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.model.Difficulty
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.data.model.SaveCourseResult
import com.runninggu.app.data.model.SavedCourse
import com.runninggu.app.data.model.SavedCourseDetail
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * `/me/courses` 가 서기 전까지 쓰는 스텁. (API 명세 §7-A)
 *
 * 서버가 없어도 S10 목록과 저장 코스 상세를 실제로 눌러 볼 수 있게 한다. 프로세스 메모리에만
 * 남으며, [RemoteSavedCourseRepository] 로 갈아끼우면 이 파일은 안 쓰인다.
 *
 * **출처 문구는 §4.11-5 의 canonical 그대로** 넣었다. 앱이 문구를 만들지 않는다는 계약을
 * 스텁에서도 지켜야, 화면이 "그냥 이어 붙이기만 한다" 는 것을 실제로 확인할 수 있다.
 */
object FakeSavedCourseRepository : SavedCourseRepository {

    private val stored = mutableListOf(
        detail(
            id = 1L,
            name = "여의도 한강 순환 5km",
            distanceKm = 5.0,
            durationMin = 32,
            gainM = 12,
            difficulty = Difficulty.EASY,
            dataSource = CourseDataSource.OSM_GENERATED,
            region = "서울",
            savedDaysAgo = 3,
            // OSM 생성 경로는 카카오 걷기 스팟과 함께 오므로 출처가 둘이다
            attributions = listOf("© OpenStreetMap contributors", "카카오 로컬"),
        ),
        detail(
            id = 2L,
            name = "해파랑길 1코스 구간",
            distanceKm = 7.5,
            durationMin = 51,
            gainM = 88,
            difficulty = Difficulty.NORMAL,
            dataSource = CourseDataSource.API_GPX,
            region = "부산",
            savedDaysAgo = 12,
            attributions = listOf("두루누비 걷기길(한국관광공사)"),
        ),
        // 출처가 빈 경우 — 화면이 "출처 ·" 만 남기지 않는지 확인할 수 있다
        detail(
            id = 3L,
            name = "대관령 숲길 트레일",
            distanceKm = 9.2,
            durationMin = 74,
            gainM = 210,
            difficulty = Difficulty.HARD,
            dataSource = CourseDataSource.GPX_ONLY,
            region = "강원",
            savedDaysAgo = 30,
            attributions = emptyList(),
        ),
    )

    override suspend fun save(route: NearbyItem.Route): SaveCourseResult? = null

    override suspend fun list(page: Int, size: Int): SavedCoursePage {
        delay(NETWORK_DELAY_MS)
        val from = (page * size).coerceIn(0, stored.size)
        val to = (from + size).coerceIn(0, stored.size)
        return SavedCoursePage(
            courses = stored.subList(from, to).map { it.course },
            hasNext = to < stored.size,
            totalElements = stored.size.toLong(),
        )
    }

    override suspend fun detail(id: Long): SavedCourseDetail {
        delay(NETWORK_DELAY_MS)
        return stored.firstOrNull { it.course.id == id }
            ?: throw ApiException.Http(status = 404, code = ApiErrorCode.NOT_FOUND, problem = null)
    }

    override suspend fun delete(id: Long) {
        delay(NETWORK_DELAY_MS)
        stored.removeAll { it.course.id == id }
    }

    private fun detail(
        id: Long,
        name: String,
        distanceKm: Double,
        durationMin: Int,
        gainM: Int,
        difficulty: Difficulty,
        dataSource: CourseDataSource,
        region: String,
        savedDaysAgo: Long,
        attributions: List<String>,
    ) = SavedCourseDetail(
        course = SavedCourse(
            id = id,
            courseName = name,
            distanceKm = distanceKm,
            durationMin = durationMin,
            gainM = gainM,
            difficulty = difficulty,
            dataSource = dataSource,
            region = region,
            savedAt = LocalDate.now().minusDays(savedDaysAgo),
        ),
        elevationProfileM = elevationOf(gainM),
        pathPolyline = "s{~kFmxwdW}A?_@wAaB{@",
        attributions = attributions,
    )

    /** 고도 스트립이 코스마다 달라 보이게만 만든다. 실제 값은 서버가 준다. */
    private fun elevationOf(gainM: Int): List<Int> =
        List(ELEVATION_POINTS) { index ->
            val phase = index.toDouble() / ELEVATION_POINTS
            (gainM * (1 - (phase - 0.5) * (phase - 0.5) * 4)).toInt().coerceAtLeast(0)
        }

    private const val NETWORK_DELAY_MS = 250L
    private const val ELEVATION_POINTS = 24
}
