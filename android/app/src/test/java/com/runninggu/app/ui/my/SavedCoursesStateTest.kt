package com.runninggu.app.ui.my

import com.runninggu.app.data.model.SavedCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 마이 [러닝코스] 세그먼트 상태. (SPEC §4.13 · §3-5 · #107 리뷰)
 *
 * 두 가지를 못 박는다.
 *
 * 1. **로딩·빈·오류가 서로 다른 상태다.** 앞서 목록만 들고 있어서 조회 중이거나 서버가
 *    실패해도 "저장한 코스가 없어요" 가 떴다 — 다시 시도해야 할 상황인지 알 수 없었다
 * 2. **전체 건수는 받아온 개수와 다르다.** 한 번에 20건씩 오므로 `courses.size` 로 세면
 *    21번째부터 없는 것처럼 보인다
 *
 * S8 지역별의 `RegionPagingTest` 와 같은 규칙을 본다 — 두 화면이 갈라지면 안 된다.
 */
class SavedCoursesStateTest {

    private fun course(i: Int) = SavedCourse(
        id = i.toLong(),
        courseName = "코스 $i",
        distanceKm = 5.0,
        durationMin = 45,
        gainM = 10,
        difficulty = null,
        dataSource = null,
        region = "부산",
        savedAt = LocalDate.of(2026, 8, 21),
    )

    @Test
    fun `더 받을 게 있고 받는 중이 아니면 누를 수 있다`() {
        val state = SavedCoursesState.Content(
            courses = List(20) { course(it) },
            hasNext = true,
            totalElements = 47,
        )

        assertTrue(state.canLoadMore)
    }

    @Test
    fun `받는 중에는 못 누른다`() {
        val state = SavedCoursesState.Content(
            courses = List(20) { course(it) },
            hasNext = true,
            totalElements = 47,
            loadingMore = true,
        )

        assertFalse(state.canLoadMore)
    }

    @Test
    fun `다 받았으면 못 누른다`() {
        val state = SavedCoursesState.Content(
            courses = List(7) { course(it) },
            hasNext = false,
            totalElements = 7,
        )

        assertFalse(state.canLoadMore)
    }

    @Test
    fun `전체 건수는 받아온 개수와 다르다`() {
        // 한 번에 20건씩 온다 — 목록 길이로 세면 21번째부터 없는 것처럼 보인다 (§0-4)
        val state = SavedCoursesState.Content(
            courses = List(20) { course(it) },
            hasNext = true,
            totalElements = 47,
        )

        assertEquals(20, state.courses.size)
        assertEquals(47L, state.totalElements)
    }

    @Test
    fun `다음 장을 못 받아도 이미 받은 목록은 남는다`() {
        // 보이던 게 사라지면 안 된다 — 20건이라도 보이는 게 빈 화면보다 낫다
        val loaded = SavedCoursesState.Content(
            courses = List(20) { course(it) },
            hasNext = true,
            totalElements = 47,
        )

        val failed = loaded.copy(loadingMore = false, moreMessage = "더 불러오지 못했어요.")

        assertEquals(20, failed.courses.size)
        assertEquals("더 불러오지 못했어요.", failed.moreMessage)
        // 아직 남아 있으니 다시 누를 수 있다
        assertTrue(failed.canLoadMore)
    }

    @Test
    fun `조회 중과 0건과 실패가 서로 다른 상태다`() {
        // 뭉뚱그리면 "없는 것" 과 "못 불러온 것" 이 같아 보인다 (SPEC §3-5)
        val loading: SavedCoursesState = SavedCoursesState.Loading
        val empty: SavedCoursesState = SavedCoursesState.Empty
        val error: SavedCoursesState = SavedCoursesState.Error("저장한 코스를 불러오지 못했어요.")

        assertNotEquals(loading, empty)
        assertNotEquals(empty, error)
        assertNotEquals(loading, error)
    }

    @Test
    fun `기본값은 0건이 아니라 조회 중이다`() {
        // 화면이 열리자마자 "저장한 코스가 없어요" 를 보이면 안 된다
        assertEquals(SavedCoursesState.Loading, MyUiState().courses)
    }
}
