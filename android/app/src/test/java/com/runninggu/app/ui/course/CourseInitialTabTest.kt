package com.runninggu.app.ui.course

import com.runninggu.app.data.model.CuratedCourseDetail
import com.runninggu.app.data.model.CourseRegion
import com.runninggu.app.data.model.NearbyCourses
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.data.repository.CoursePage
import com.runninggu.app.data.repository.CourseRepository
import com.runninggu.app.data.repository.FakeGeocodeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * S8 을 어느 탭으로 여는가. (SPEC §4.4-2 · 목업 v2 L967-968)
 *
 * 홈 퀵바의 **지도**와 **코스**는 같은 S8 의 다른 탭이다 — 지도는 지도가 그려지는
 * 출발지 주변, 코스는 지역별 목록이다. 예전에는 둘 다 `onOpenCourses` 하나로 묶여
 * 있어서 **다르게 생긴 버튼 두 개가 같은 화면을 열었다.**
 *
 * 여기서 못 박는 것은 탭 값 하나가 아니라 **첫 조회까지 같이 따라오는가**다.
 * `onTabChange` 를 거치지 않는 진입이라, 목록을 부르지 않으면 목록을 보러 누른 버튼이
 * 빈 화면을 연다.
 */
class CourseInitialTabTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repository: CourseRepository, tab: CourseUiState.Tab) = CourseViewModel(
        repository = repository,
        geocodeRepository = FakeGeocodeRepository,
        savedCourseRepository = NoSaves,
        initialTab = tab,
    )

    @Test
    fun `기본은 출발지 주변이고 지역별 목록을 미리 부르지 않는다`() = runTest(dispatcher) {
        val repository = CountingCourses()

        val vm = viewModel(repository, CourseUiState.Tab.NEARBY)
        advanceUntilIdle()

        assertEquals(CourseUiState.Tab.NEARBY, vm.uiState.value.tab)
        // 안 보는 탭을 미리 부르면 홈에서 [지도] 를 누를 때마다 헛 호출이 하나 나간다
        assertEquals(0, repository.byRegionCalls)
    }

    @Test
    fun `지역별로 열면 그 탭이 펴진 채로 목록을 부른다`() = runTest(dispatcher) {
        val repository = CountingCourses()

        val vm = viewModel(repository, CourseUiState.Tab.BY_REGION)
        advanceUntilIdle()

        assertEquals(CourseUiState.Tab.BY_REGION, vm.uiState.value.tab)
        assertEquals(1, repository.byRegionCalls)
        // 빈 페이지를 주는 가짜라 Empty 로 앉는다. 중요한 건 **Loading 이 아니라는 것** —
        // 안 불렀으면 초기값 Loading 인 채로 멈춰 있다
        assertEquals(RegionCoursesState.Empty, vm.uiState.value.regionCourses)
    }

    @Test
    fun `지역별로 연 뒤 탭을 오가도 다시 부르지 않는다`() = runTest(dispatcher) {
        // `onTabChange` 의 "처음 한 번만" 가드가 init 의 호출을 이미 센 것으로 봐야 한다.
        // 아니면 탭을 옮길 때마다 같은 목록을 다시 받는다
        val repository = CountingCourses()

        val vm = viewModel(repository, CourseUiState.Tab.BY_REGION)
        advanceUntilIdle()
        vm.onTabChange(CourseUiState.Tab.NEARBY)
        vm.onTabChange(CourseUiState.Tab.BY_REGION)
        advanceUntilIdle()

        assertEquals(1, repository.byRegionCalls)
    }
}

/** [byRegion] 이 몇 번 불렸는지만 센다. */
private class CountingCourses : CourseRepository {
    var byRegionCalls = 0
        private set

    override suspend fun near(
        lat: Double,
        lng: Double,
        targetKm: Double,
        radiusKm: Double,
        size: Int,
    ) = NearbyCourses()

    override suspend fun byRegion(region: String?, page: Int, size: Int): CoursePage {
        byRegionCalls++
        return CoursePage()
    }

    override suspend fun regions(): List<CourseRegion> = emptyList()

    // 이 테스트는 상세를 안 쓴다 — 불러야 할 곳이 있으면 그게 버그다 (#280)
    override suspend fun detail(courseId: String): CuratedCourseDetail =
        error("이 테스트는 상세를 부르지 않는다")
}

/** 탭 진입만 보는 테스트라 저장은 부르지 않는다. */
private object NoSaves : com.runninggu.app.data.repository.SavedCourseRepository {
    override suspend fun save(route: NearbyItem.Route) = null
    override suspend fun list(page: Int, size: Int) =
        com.runninggu.app.data.repository.SavedCoursePage()
    override suspend fun detail(id: Long): com.runninggu.app.data.model.SavedCourseDetail =
        throw UnsupportedOperationException("이 테스트는 상세를 부르지 않는다")
    override suspend fun delete(id: Long) = Unit
}
