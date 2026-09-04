package com.runninggu.app.ui.course

import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.model.Difficulty
import com.runninggu.app.data.model.NearbyItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 걷기 스팟을 골랐을 때 왜 저장이 안 되는지 적는가. (§4.11-4 · #269)
 *
 * ## 망가뜨리면 이것만 실패한다
 *
 * `walkSpotPicked` 가 `selectedItem` 종류를 안 가리고 `!= null` 이면
 * `경로를 고르면 안내를 띄우지 않는다` 만 실패한다.
 *
 * **화면이 이 값을 실제로 그리는지는 여기서 못 잡는다** — Compose 자리다.
 * 기기에서 본 것을 PR 에 적었다.
 */
class WalkSpotNoticeTest {

    private val place = NearbyItem.Place(
        name = "서울광장",
        distanceM = 92,
        lat = 37.5663,
        lng = 126.9779,
        category = "도시근린공원",
        address = "서울 중구 세종대로 110",
        placeUrl = null,
    )

    private val route = NearbyItem.Route(
        routeId = "T_CRS_1",
        name = "남산 둘레길",
        distanceM = 120,
        lat = 37.5512,
        lng = 126.9882,
        dataSource = CourseDataSource.API_GPX,
        difficulty = Difficulty.EASY,
        routeKm = 5.2,
        durationMin = 47,
        gainM = 40,
        elevationProfileM = listOf(10, 20, 30),
        shortfall = false,
        pathPolyline = "abc",
    )

    @Test
    fun `걷기 스팟을 고르면 안내를 띄운다`() {
        assertTrue(CourseUiState(selectedItem = place).walkSpotPicked)
    }

    // 경로는 저장할 수 있다. 여기에 "저장할 수 없어요" 가 뜨면 정반대를 말하게 된다.
    @Test
    fun `경로를 고르면 안내를 띄우지 않는다`() {
        assertFalse(CourseUiState(selectedItem = route).walkSpotPicked)
    }

    // 아무것도 안 고른 첫 화면에서는 할 말이 없다.
    @Test
    fun `아무것도 안 골랐으면 안내가 없다`() {
        assertFalse(CourseUiState(selectedItem = null).walkSpotPicked)
    }

    // 결정문(#269)에 글자 그대로 적힌 문구다. 바뀌면 결정과 화면이 갈린다.
    @Test
    fun `결정문의 문구를 그대로 쓴다`() {
        assertTrue(WALK_SPOT_NOT_SAVABLE == "걷기 스팟은 저장할 수 없어요. 지도에서 위치만 확인해 주세요.")
    }
}
