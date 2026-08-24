package com.runninggu.app.ui.map

import com.runninggu.app.domain.LatLng

/**
 * 지도에 그릴 것들. (SPEC §3-8)
 *
 * 화면은 이 세 가지만 넘긴다 — 카카오 SDK 타입은 [RunningGuMap] 안에서만 쓴다. 그래야
 * SDK 를 바꾸거나 못 띄울 때 화면 코드가 안 흔들린다.
 */
data class MapScene(
    /** 좌표가 있는 항목만. 순서대로 번호 핀이 되고 서로 이어진다. */
    val pins: List<MapMarker> = emptyList(),
    /**
     * 코스 경로선. **핀과 독립적으로 그린다** — 러닝코스(S8)는 핀 없이 경로만 그린다(§3-8).
     * 2점 미만이면 선이 아니라서 그리지 않는다.
     */
    val route: List<LatLng> = emptyList(),
    /** 지금 보고 있는 핀. 확대·강조되고 카메라가 따라간다. */
    val activePinId: String? = null,
    /** 핀을 선으로 이을지. 동선(S7)은 잇고, 흩어진 장소 목록은 안 잇는다. */
    val connectPins: Boolean = true,
) {
    /**
     * 카메라가 담아야 할 선. 핀을 잇는 설정이면 핀 좌표열이 곧 선이다(§3-8).
     *
     * S7 동선이 이걸로 그려진다 — "좌표 있는 항목만 번호 핀 + **항목을 잇는 폴리라인**".
     * 흩어진 장소 목록은 [connectPins] 를 꺼서 선을 안 그린다.
     */
    internal val pinPath: List<LatLng>
        get() = if (connectPins && pins.size >= MIN_ROUTE_POINTS) {
            pins.map { LatLng(it.lat, it.lng) }
        } else {
            emptyList()
        }

    /** 카메라가 담아야 할 좌표. 경로가 있으면 경로가 기준이다(§3-8 · 목업 MapView). */
    internal val cameraTargets: List<LatLng>
        get() = if (route.size >= MIN_ROUTE_POINTS) route else pins.map { LatLng(it.lat, it.lng) }
}

/**
 * 방문 순서 번호 핀. (SPEC §3-8)
 *
 * `domain.MapPin` 과 **다른 타입이다.** 저쪽은 동선 도메인의 핀(`BlockCategory` 를 안다)이고
 * 이쪽은 지도에 그리는 것만 안다 — `ui/map` 이 동선 개념을 알면 S8 러닝코스(동선이 없는
 * 화면)까지 동선 타입에 묶인다. 같은 파일에서 둘을 쓰는 화면이 셋이라 이름을 갈랐다(#88 리뷰).
 *
 * @param order 1부터. 핀 안에 그대로 찍힌다.
 * @param recovery 회복일인가. 액센트가 파랑 대신 주황이 된다(§3-8 범례).
 */
data class MapMarker(
    val id: String,
    val order: Int,
    val lat: Double,
    val lng: Double,
    val recovery: Boolean = false,
)

/** 카메라를 어떻게 움직일지. */
sealed interface CameraCommand {
    /** 전부 담기게 맞춘다. 핀·경로 구성이 바뀌었을 때다. */
    data class FitBounds(val points: List<LatLng>) : CameraCommand

    /** 그 좌표로 이동만. 활성 핀만 바뀌었을 때다. */
    data class MoveTo(val point: LatLng) : CameraCommand

    /** 건드리지 않는다. 사용자가 옮겨 둔 화면을 이유 없이 되돌리지 않는다. */
    data object None : CameraCommand
}

/**
 * 이번 상태 변화에 카메라를 어떻게 할지 정한다. (SPEC §3-8)
 *
 * 규칙은 둘뿐이다 — **구성이 바뀌면 전체 맞춤, 활성만 바뀌면 그 좌표로 이동.**
 * SDK 를 안 쓰는 순수 함수라 단위 테스트로 고정한다.
 *
 * @param previous 직전 장면. 처음 그릴 때는 null 이고, 그때는 항상 전체 맞춤이다.
 */
internal fun cameraCommandFor(previous: MapScene?, next: MapScene): CameraCommand {
    val targets = next.cameraTargets
    if (targets.isEmpty()) return CameraCommand.None

    if (previous == null || previous.layoutDiffersFrom(next)) {
        return CameraCommand.FitBounds(targets)
    }

    val activeId = next.activePinId ?: return CameraCommand.None
    if (activeId == previous.activePinId) return CameraCommand.None

    val pin = next.pins.firstOrNull { it.id == activeId } ?: return CameraCommand.None
    return CameraCommand.MoveTo(LatLng(pin.lat, pin.lng))
}

/**
 * 카메라를 다시 맞춰야 할 만큼 "구성" 이 달라졌는가.
 *
 * **좌표까지 본다.** 예전에는 핀 id 와 경로의 양 끝점만 비교해서, 개수와 양 끝이 같고
 * 가운데만 바뀐 경로(같은 지점을 도는 다른 코스)에 카메라가 그대로 있었다(#88 리뷰).
 *
 * 활성 핀은 **일부러 뺐다.** 카드를 스크롤해 활성만 바뀔 때 지도가 매번 전체로 줌아웃되면
 * 따라가기 어렵다 — 그때는 그 핀으로 이동만 한다(§3-8).
 */
private fun MapScene.layoutDiffersFrom(other: MapScene): Boolean =
    pins != other.pins || route != other.route || connectPins != other.connectPins

/** 선이 되려면 두 점은 있어야 한다. */
internal const val MIN_ROUTE_POINTS = 2

/**
 * `fitMapPoints` 에 줄 여백(px). (SPEC §3-8 · #162)
 *
 * **좌표 기준 여백이라 핀 그림 크기가 저절로 들어가지 않는다.** 라벨은 좌표를 중심에
 * 두고 그려지므로 절반이 바깥으로 솟는데, 여백이 그보다 작으면 가장자리 핀의 위쪽이
 * 잘린다. 경로만 그릴 때는 폴리라인이 위로 솟지 않아 드러나지 않던 자리다.
 *
 * 예전에는 밀도와 무관한 `60px` 상수였다. 그 값은 densityDpi 가 낮은 기기에서만 우연히
 * 맞고, 고밀도 기기에서는 핀 절반(약 [PinBitmap.MAX_SIZE_DP] `/ 2`)이 그보다 커져서
 * **반드시 잘린다.**
 *
 * @param hasPins 핀이 있는 장면인가. 경로만이면 여백만 준다
 */
internal fun cameraFitPaddingPx(density: Float, hasPins: Boolean): Int {
    val base = BASE_FIT_PADDING_DP * density
    val halfPin = if (hasPins) PinBitmap.MAX_SIZE_DP * density / 2f else 0f
    return kotlin.math.ceil(base + halfPin).toInt()
}

/** 지도 가장자리와 내용 사이 최소 숨통. 핀 크기와 별개다. */
private const val BASE_FIT_PADDING_DP = 16f
