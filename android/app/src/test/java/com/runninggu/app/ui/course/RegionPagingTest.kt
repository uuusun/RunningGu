package com.runninggu.app.ui.course

import com.runninggu.app.data.model.CourseSummary
import com.runninggu.app.data.model.Difficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 지역별 [더 보기] 상태. (SPEC §4.11-b)
 *
 * 한 번에 20건씩 오므로 목록 개수와 전체 건수가 다른 게 정상이다 —
 * 그 둘을 헷갈리면 "코스 261" 아래에 20개만 그리고 끝낸다.
 */
class RegionPagingTest {

    private fun course(i: Int) = CourseSummary(
        courseId = "c$i",
        courseName = "코스 $i",
        sido = "부산",
        sigun = "해운대구",
        distanceKm = 5.0,
        difficulty = Difficulty.EASY,
        gainM = 10,
        durationMin = 45,
        dataSource = null,
    )

    @Test
    fun `더 받을 게 있고 받는 중이 아니면 누를 수 있다`() {
        val state = RegionCoursesState.Content(
            courses = List(20) { course(it) },
            hasNext = true,
            totalElements = 261,
        )

        assertTrue(state.canLoadMore)
    }

    @Test
    fun `받는 중에는 못 누른다`() {
        val state = RegionCoursesState.Content(
            courses = List(20) { course(it) },
            hasNext = true,
            totalElements = 261,
            loadingMore = true,
        )

        assertFalse(state.canLoadMore)
    }

    @Test
    fun `다 받았으면 못 누른다`() {
        val state = RegionCoursesState.Content(
            courses = List(7) { course(it) },
            hasNext = false,
            totalElements = 7,
        )

        assertFalse(state.canLoadMore)
    }

    @Test
    fun `전체 건수는 받아온 개수와 다르다`() {
        // "{지역} 코스 N" 은 전체 건수다 — 목록 길이로 세면 안 된다 (§4.11-b)
        val state = RegionCoursesState.Content(
            courses = List(20) { course(it) },
            hasNext = true,
            totalElements = 261,
        )

        assertEquals(20, state.courses.size)
        assertEquals(261L, state.totalElements)
    }

    @Test
    fun `이어 붙인 장의 출처도 함께 표시한다`() {
        // 출처는 화면에 보이는 코스 전체 기준이다 — 새 장에 다른 원천이 섞일 수 있다
        val first = listOf("두루누비 걷기길(한국관광공사)")
        val second = listOf("두루누비 걷기길(한국관광공사)", "등산로·숲길(한국등산·트레킹지원센터)")

        val merged = (first + second).distinct()

        assertEquals(2, merged.size)
        assertEquals("두루누비 걷기길(한국관광공사)", merged.first())
    }

    @Test
    fun `다음 장을 못 받아도 이미 받은 목록은 남는다`() {
        // 보이던 코스가 사라지면 안 된다 (§4.11-7 부분 실패와 같은 취지)
        val loaded = RegionCoursesState.Content(
            courses = List(20) { course(it) },
            hasNext = true,
            totalElements = 261,
        )

        val failed = loaded.copy(loadingMore = false, moreMessage = "정보를 불러오지 못했어요.")

        assertEquals(20, failed.courses.size)
        assertEquals("정보를 불러오지 못했어요.", failed.moreMessage)
        assertTrue(failed.canLoadMore)
    }
}
