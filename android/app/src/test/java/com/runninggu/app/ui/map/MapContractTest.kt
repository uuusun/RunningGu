package com.runninggu.app.ui.map

import com.runninggu.app.domain.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 지도 카메라 규칙. (SPEC §3-8)
 *
 * 규칙은 둘뿐이다 — **구성이 바뀌면 전체 맞춤, 활성만 바뀌면 그 좌표로 이동.**
 * 여기가 깨지면 S7 에서 카드를 넘길 때마다 지도가 전체로 줌아웃돼 따라갈 수 없게 된다.
 */
class MapContractTest {

    private fun pin(id: String, order: Int, lat: Double, lng: Double, recovery: Boolean = false) =
        MapMarker(id = id, order = order, lat = lat, lng = lng, recovery = recovery)

    private val threePins = listOf(
        pin("a", 1, 37.5665, 126.9780),
        pin("b", 2, 37.5700, 126.9820),
        pin("c", 3, 37.5600, 126.9700),
    )

    @Test
    fun `처음 그릴 때는 전부 담기게 맞춘다`() {
        val scene = MapScene(pins = threePins)

        val command = cameraCommandFor(previous = null, next = scene)

        assertTrue(command is CameraCommand.FitBounds)
        assertEquals(3, (command as CameraCommand.FitBounds).points.size)
    }

    @Test
    fun `핀 구성이 바뀌면 다시 전체로 맞춘다`() {
        val before = MapScene(pins = threePins)
        val after = MapScene(pins = threePins.dropLast(1))

        assertTrue(cameraCommandFor(before, after) is CameraCommand.FitBounds)
    }

    @Test
    fun `활성 핀만 바뀌면 그 좌표로 이동만 한다`() {
        // 카드를 스크롤할 때마다 줌아웃되면 어디를 보고 있었는지 잃는다
        val before = MapScene(pins = threePins, activePinId = "a")
        val after = MapScene(pins = threePins, activePinId = "b")

        val command = cameraCommandFor(before, after)

        assertTrue(command is CameraCommand.MoveTo)
        assertEquals(LatLng(37.5700, 126.9820), (command as CameraCommand.MoveTo).point)
    }

    @Test
    fun `아무것도 안 바뀌면 카메라를 건드리지 않는다`() {
        // 사용자가 손으로 옮겨 둔 화면을 이유 없이 되돌리지 않는다
        val scene = MapScene(pins = threePins, activePinId = "a")

        assertEquals(CameraCommand.None, cameraCommandFor(scene, scene))
    }

    @Test
    fun `그릴 게 없으면 카메라를 건드리지 않는다`() {
        assertEquals(CameraCommand.None, cameraCommandFor(null, MapScene()))
    }

    @Test
    fun `경로가 있으면 핀이 아니라 경로를 담는다`() {
        // 러닝코스(S8)는 핀 없이 경로만 그린다 — 목업 MapView 와 같은 판단이다
        val route = listOf(
            LatLng(37.50, 126.90),
            LatLng(37.51, 126.91),
            LatLng(37.52, 126.92),
            LatLng(37.53, 126.93),
        )
        val scene = MapScene(pins = threePins, route = route)

        val command = cameraCommandFor(null, scene)

        assertTrue(command is CameraCommand.FitBounds)
        assertEquals(route, (command as CameraCommand.FitBounds).points)
    }

    @Test
    fun `점이 하나뿐인 경로는 선이 아니라서 핀을 기준으로 삼는다`() {
        val scene = MapScene(pins = threePins, route = listOf(LatLng(37.50, 126.90)))

        val command = cameraCommandFor(null, scene)

        assertEquals(3, (command as CameraCommand.FitBounds).points.size)
    }

    @Test
    fun `경로가 바뀌면 다시 전체로 맞춘다`() {
        val before = MapScene(route = listOf(LatLng(37.50, 126.90), LatLng(37.51, 126.91)))
        val after = MapScene(route = listOf(LatLng(37.50, 126.90), LatLng(37.55, 126.95)))

        assertTrue(cameraCommandFor(before, after) is CameraCommand.FitBounds)
    }

    @Test
    fun `없는 핀이 활성으로 지정돼도 넘어간다`() {
        // 목록과 활성 id 가 한 프레임 어긋날 수 있다. 그때 튕기면 안 된다
        val before = MapScene(pins = threePins, activePinId = "a")
        val after = MapScene(pins = threePins, activePinId = "없는핀")

        assertEquals(CameraCommand.None, cameraCommandFor(before, after))
    }

    @Test
    fun `핀 좌표만 바뀌어도 카메라를 다시 맞춘다`() {
        // 예전에는 id 만 비교해서, 같은 항목이 다른 좌표로 갱신돼도 카메라가 그대로였다
        val before = MapScene(pins = threePins)
        val after = MapScene(pins = threePins.map { it.copy(lat = it.lat + 0.05) })

        assertTrue(cameraCommandFor(before, after) is CameraCommand.FitBounds)
    }

    @Test
    fun `경로의 가운데만 바뀌어도 카메라를 다시 맞춘다`() {
        // 같은 지점을 도는 다른 코스다. 개수와 양 끝점이 같아 예전에는 못 잡았다 (#88 리뷰)
        val start = LatLng(37.50, 126.90)
        val end = LatLng(37.53, 126.93)
        val before = MapScene(route = listOf(start, LatLng(37.51, 126.91), end))
        val after = MapScene(route = listOf(start, LatLng(37.58, 126.99), end))

        assertTrue(cameraCommandFor(before, after) is CameraCommand.FitBounds)
    }

    @Test
    fun `핀을 잇는 설정이면 핀 좌표열이 선이 된다`() {
        // S7 동선의 "항목을 잇는 폴리라인" (SPEC §3-8)
        val scene = MapScene(pins = threePins, connectPins = true)

        assertEquals(3, scene.pinPath.size)
        assertEquals(LatLng(37.5665, 126.9780), scene.pinPath.first())
    }

    @Test
    fun `잇지 않기로 하면 선을 그리지 않는다`() {
        // 흩어진 장소 목록은 이어 봐야 의미가 없다
        val scene = MapScene(pins = threePins, connectPins = false)

        assertTrue(scene.pinPath.isEmpty())
    }

    @Test
    fun `핀이 하나면 선이 되지 않는다`() {
        val scene = MapScene(pins = threePins.take(1), connectPins = true)

        assertTrue(scene.pinPath.isEmpty())
    }

    @Test
    fun `잇기 설정이 바뀌면 카메라를 다시 맞춘다`() {
        val before = MapScene(pins = threePins, connectPins = true)
        val after = MapScene(pins = threePins, connectPins = false)

        assertTrue(cameraCommandFor(before, after) is CameraCommand.FitBounds)
    }
}
