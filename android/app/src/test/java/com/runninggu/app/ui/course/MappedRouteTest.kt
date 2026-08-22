package com.runninggu.app.ui.course

import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.model.Difficulty
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.domain.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S8 지도가 어느 경로를 따라가는가. (SPEC §4.11-4 · §3-8)
 *
 * 고른 것이 있으면 그것, 없으면 **첫 코스**다. 조회 직후 `selectedRouteId` 가 null 인데
 * (`CourseViewModel` 이 새 조회마다 지운다) 그때 비워 두면 목록을 한 번 탭하기 전까지
 * 빈 회색 판이 놓인다.
 */
class MappedRouteTest {

    @Test
    fun `고른 코스가 있으면 그것을 그린다`() {
        val state = contentState(selectedRouteId = "r2")

        assertEquals("r2", state.mappedRoute?.routeId)
    }

    @Test
    fun `고르기 전에는 첫 코스를 그린다`() {
        val state = contentState(selectedRouteId = null)

        assertEquals("r1", state.mappedRoute?.routeId)
    }

    @Test
    fun `사라진 id 가 남아 있어도 첫 코스로 떨어진다`() {
        // 조건을 바꿔 다시 조회하면 목록이 갈린다. 그때 지도가 비면 안 된다.
        val state = contentState(selectedRouteId = "지워진-코스")

        assertEquals("r1", state.mappedRoute?.routeId)
    }

    @Test
    fun `걷기 스팟만 있으면 그릴 것이 없다`() {
        // 서울 반경 8km 의 실제 상황이다 — 코스 0건, 걷기 스팟만 (SPEC §4.11 📌)
        val state = CourseUiState(
            nearby = NearbyState.Content(items = listOf(place()), attributions = emptyList()),
        )

        assertNull(state.mappedRoute)
    }

    @Test
    fun `조회 전에는 그릴 것이 없다`() {
        assertNull(CourseUiState().mappedRoute)
    }

    private fun contentState(selectedRouteId: String?) = CourseUiState(
        nearby = NearbyState.Content(
            items = listOf(route("r1"), place(), route("r2")),
            attributions = emptyList(),
        ),
        selectedRouteId = selectedRouteId,
    )

    private fun route(id: String) = NearbyItem.Route(
        routeId = id,
        name = "내 주변 5km 평지 러닝코스",
        distanceM = 12,
        lat = 37.5,
        lng = 126.9,
        dataSource = CourseDataSource.OSM_GENERATED,
        difficulty = Difficulty.EASY,
        routeKm = 5.0,
        durationMin = 45,
        gainM = 38,
        elevationProfileM = listOf(1, 2, 3),
        shortfall = false,
        pathPolyline = "x",
        path = listOf(LatLng(37.5, 126.9), LatLng(37.51, 126.91)),
    )

    private fun place() = NearbyItem.Place(
        name = "여의도공원",
        distanceM = 650,
        lat = 37.502,
        lng = 126.902,
        category = "공원",
        address = "서울 영등포구 여의공원로 68",
        placeUrl = null,
    )
}
