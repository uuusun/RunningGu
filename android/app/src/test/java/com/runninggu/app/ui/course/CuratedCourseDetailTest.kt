package com.runninggu.app.ui.course

import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.repository.CoursePage
import com.runninggu.app.data.model.CourseRegion
import com.runninggu.app.data.model.CuratedCourseDetail
import com.runninggu.app.data.model.Difficulty
import com.runninggu.app.data.model.NearbyCourses
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.CourseRepository
import com.runninggu.app.domain.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * S8-D 큐레이션 코스 상세. (#280)
 *
 * ## 망가뜨리면 이것만 실패한다
 *
 * - `load` 의 같은 id 가드를 빼면 → `같은 코스로 다시 들어와도 두 번 조회하지 않는다` 만 실패
 * - `retry` 에서 `force = true` 를 빼면 → `오류에서 다시 시도하면 그 코스를 다시 조회한다` 만 실패
 * - 실패 때 `detail = null` 을 안 하면 → `조회에 실패하면 내용을 비운다` 만 실패
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CuratedCourseDetailTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeRepo(
        private val detail: CuratedCourseDetail? = null,
        private val failure: Throwable? = null,
    ) : CourseRepository {
        var calls = 0
            private set

        override suspend fun near(
            lat: Double,
            lng: Double,
            targetKm: Double,
            radiusKm: Double,
            size: Int,
        ): NearbyCourses = error("안 쓴다")

        override suspend fun byRegion(region: String?, page: Int, size: Int): CoursePage =
            error("안 쓴다")

        override suspend fun regions(): List<CourseRegion> = error("안 쓴다")

        override suspend fun detail(courseId: String): CuratedCourseDetail {
            calls++
            failure?.let { throw it }
            return detail!!
        }
    }

    private fun detail(courseId: String = "T_CRS_1") = CuratedCourseDetail(
        courseId = courseId,
        courseName = "해파랑길 1코스",
        sido = "부산",
        sigun = "남구",
        distanceKm = 17.8,
        difficulty = Difficulty.NORMAL,
        gainM = 312,
        durationMin = 162,
        dataSource = CourseDataSource.API_GPX,
        syncedAt = null,
        pathPolyline = "abc",
        path = listOf(LatLng(35.1, 129.1), LatLng(35.2, 129.2)),
        elevationProfileM = listOf(10, 20, 30),
        attributions = listOf("두루누비 걷기길(한국관광공사)"),
    )

    @Test
    fun `코스를 열면 내용을 채운다`() = runTest {
        val vm = CuratedCourseDetailViewModel(FakeRepo(detail()))
        vm.load("T_CRS_1")
        advanceUntilIdle()

        assertEquals(CuratedCourseDetailUiState.Phase.CONTENT, vm.uiState.value.phase)
        assertEquals("해파랑길 1코스", vm.uiState.value.detail?.courseName)
    }

    // 회전·재진입으로 LaunchedEffect 가 다시 돈다. 그때마다 부르면 네트워크가 두 번 나간다.
    @Test
    fun `같은 코스로 다시 들어와도 두 번 조회하지 않는다`() = runTest {
        val repo = FakeRepo(detail())
        val vm = CuratedCourseDetailViewModel(repo)
        vm.load("T_CRS_1")
        advanceUntilIdle()
        vm.load("T_CRS_1")
        advanceUntilIdle()

        assertEquals(1, repo.calls)
    }

    @Test
    fun `다른 코스를 열면 다시 조회한다`() = runTest {
        val repo = FakeRepo(detail())
        val vm = CuratedCourseDetailViewModel(repo)
        vm.load("T_CRS_1")
        advanceUntilIdle()
        vm.load("T_CRS_2")
        advanceUntilIdle()

        assertEquals(2, repo.calls)
    }

    // 앞 코스가 남아 있으면 어느 코스의 오류인지 알 수 없다.
    @Test
    fun `조회에 실패하면 내용을 비운다`() = runTest {
        val vm = CuratedCourseDetailViewModel(FakeRepo(failure = ApiException.Network(IOException("끊김"))))
        vm.load("T_CRS_1")
        advanceUntilIdle()

        assertEquals(CuratedCourseDetailUiState.Phase.ERROR, vm.uiState.value.phase)
        assertNull(vm.uiState.value.detail)
    }

    // 같은 id 가드에 막히면 [다시 시도] 가 아무 일도 안 한다 — #257 에서 겪은 자리다.
    @Test
    fun `오류에서 다시 시도하면 그 코스를 다시 조회한다`() = runTest {
        val repo = FakeRepo(failure = ApiException.Network(IOException("끊김")))
        val vm = CuratedCourseDetailViewModel(repo)
        vm.load("T_CRS_1")
        advanceUntilIdle()
        vm.retry()
        advanceUntilIdle()

        assertEquals(2, repo.calls)
    }
}
