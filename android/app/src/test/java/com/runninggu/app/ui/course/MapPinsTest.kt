package com.runninggu.app.ui.course

import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.model.Difficulty
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.domain.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8 지도의 걷기 스팟 번호 핀. (SPEC §4.11-4 · §3-8 · 이슈 #162)
 *
 * 명세가 지도를 두 갈래로 가른다.
 *
 * > 선택 항목이 **경로면 왕복 폴리라인**(경로 bounds), **그 외 번호 핀**(잇지 않음,
 * > 리스트 번호 일치)
 *
 * **"리스트 번호 일치" 가 이 파일의 전부다.** 핀 번호가 목록 번호와 어긋나면 사용자는
 * 지도의 `3` 과 목록의 `3` 을 다른 곳으로 읽는다. 그래서 스팟만 따로 1·2·3 으로 다시
 * 매기지 않고 **목록 순번을 그대로** 쓰며, 그 결과 핀 번호는 중간이 빈다.
 */
class MapPinsTest {

    @Test
    fun `걷기 스팟을 고르면 목록 번호 그대로 핀을 세운다`() {
        // 목록: 1 경로 · 2 스팟 · 3 경로 → 스팟은 2번이다
        val state = contentState(selected = place())

        val pins = state.mapPins

        assertEquals(1, pins.size)
        assertEquals("목록의 2번인데 핀이 다른 숫자면 짝이 깨진다", 2, pins.single().order)
    }

    @Test
    fun `핀 번호는 중간이 빈다`() {
        // 경로가 섞여 있으니 스팟 번호는 이어지지 않는다. 다시 매기면 목록과 어긋난다.
        val state = CourseUiState(
            nearby = NearbyState.Content(
                items = listOf(route("r1"), place("A"), place("B"), route("r2"), place("C")),
                attributions = emptyList(),
            ),
            selectedItem = place("A"),
        )

        assertEquals(listOf(2, 3, 5), state.mapPins.map { it.order })
    }

    @Test
    fun `고른 스팟이 활성 핀이 된다`() {
        // 카메라가 여기로 따라간다 (§3-8)
        val state = contentState(selected = place())

        assertEquals(state.mapPins.single().id, state.activePinId)
    }

    @Test
    fun `경로를 그리는 동안에는 핀을 세우지 않는다`() {
        // 선과 핀을 같이 그리면 §4.11-4 가 가른 두 갈래가 한 화면에 겹친다.
        val state = contentState(selected = route("r2"))

        assertTrue(state.mapPins.isEmpty())
        assertNull(state.activePinId)
    }

    @Test
    fun `고르기 전이라도 기본 경로가 있으면 핀을 세우지 않는다`() {
        // 조회 직후에는 첫 코스를 그린다(MappedRouteTest). 그때 핀까지 세우면 겹친다.
        val state = contentState(selected = null)

        assertEquals("r1", state.mappedRoute?.routeId)
        assertTrue(state.mapPins.isEmpty())
    }

    @Test
    fun `코스가 0건이면 고르기 전에도 핀을 세운다`() {
        // **서울 반경 8km 의 실제 상황이다** — 코스 0건에 걷기 스팟만 나온다
        // (SPEC §4.11 📌 · AGENTS 6장 "걷기 스팟은 폴백이 아니라 수도권의 기본 경험").
        // 고른 뒤에만 핀을 세우면 이 화면은 목록을 탭하기 전까지 늘 비어 있다.
        val state = CourseUiState(
            nearby = NearbyState.Content(
                items = listOf(place("A"), place("B")),
                attributions = emptyList(),
            ),
        )

        assertEquals(listOf(1, 2), state.mapPins.map { it.order })
    }

    @Test
    fun `조회 전에는 핀이 없다`() {
        assertTrue(CourseUiState().mapPins.isEmpty())
        assertNull(CourseUiState().activePinId)
    }

    private fun contentState(selected: NearbyItem?) = CourseUiState(
        nearby = NearbyState.Content(
            items = listOf(route("r1"), place(), route("r2")),
            attributions = emptyList(),
        ),
        selectedItem = selected,
    )

    private fun route(id: String) = NearbyItem.Route(
        routeId = id,
        name = "출발지 주변 5km 평지 러닝코스",
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

    private fun place(name: String = "여의도공원") = NearbyItem.Place(
        name = name,
        distanceM = 650,
        lat = 37.502,
        lng = 126.902,
        category = "공원",
        address = "서울 영등포구 여의공원로 68",
        placeUrl = null,
    )
}
