package com.runninggu.app.ui.map

import android.util.DisplayMetrics
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.runninggu.app.domain.LatLng
import com.runninggu.app.ui.theme.Blue
import com.runninggu.app.ui.theme.Orange
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraAnimation
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.route.RouteLine
import com.kakao.vectormap.route.RouteLineLayer
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles
import com.kakao.vectormap.route.RouteLineStylesSet
import com.kakao.vectormap.LatLng as KakaoLatLng

/**
 * 카카오맵 래퍼. 지도가 필요한 화면은 전부 이걸 쓴다. (SPEC §3-8 · AP-03)
 *
 * **실패해도 화면을 막지 않는다.** SDK 초기화·인증이 실패하면 지도 자리에만 안내 문구를
 * 띄우고 나머지(리스트·버튼)는 그대로 동작한다(NFR-1·3). 웹 목업의 SVG 폴백 지도는
 * 폐기했다 — 실제 좌표를 못 그리는 그림을 보여주느니 못 띄웠다고 말하는 편이 낫다(§3-8).
 *
 * @param scene 그릴 것. 바뀌면 핀·경로를 다시 그리고 카메라를 [cameraCommandFor] 규칙대로 옮긴다
 * @param onPinClick 핀 탭 → 해당 항목 활성화. S7 은 이걸로 카드를 스크롤한다(§3-8)
 */
@Composable
fun RunningGuMap(
    scene: MapScene,
    modifier: Modifier = Modifier,
    onPinClick: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPinClick by rememberUpdatedState(onPinClick)

    /** 지도를 못 띄운 이유. null 이면 정상이다. */
    var failure by remember { mutableStateOf<String?>(null) }
    val painter = remember { ScenePainter() }

    val mapView = remember {
        MapView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    // 지도는 화면이 안 보일 때 렌더링을 멈춰야 한다 — 안 그러면 배터리를 계속 먹는다
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.resume()
                Lifecycle.Event.ON_PAUSE -> mapView.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        mapView.start(
            object : MapLifeCycleCallback() {
                override fun onMapDestroy() {
                    painter.detach()
                }

                override fun onMapError(error: Exception) {
                    // 키 미등록·키 해시 불일치·네트워크가 여기로 온다. 원인을 그대로
                    // 보여줄 수 없으니 문구는 하나로 두고, 로그에도 키를 남기지 않는다
                    painter.detach()
                    failure = MAP_UNAVAILABLE
                }
            },
            object : KakaoMapReadyCallback() {
                override fun onMapReady(map: KakaoMap) {
                    failure = null
                    painter.attach(map) { currentOnPinClick(it) }
                    painter.draw(context.resources.displayMetrics, scene)
                }
            },
        )

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            painter.detach()
            mapView.finish()
        }
    }

    // 장면이 바뀌면 다시 그린다. 지도가 아직 준비 전이면 onMapReady 가 처음 한 번 그린다
    DisposableEffect(scene) {
        painter.draw(context.resources.displayMetrics, scene)
        onDispose { }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        failure?.let { message ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 지도에 실제로 그리는 부분. (SPEC §3-8)
 *
 * Compose 밖에 두는 이유는 SDK 객체(라벨·경로선)를 **직접 지웠다 다시 만들어야** 하기
 * 때문이다. 리컴포지션마다 새로 만들면 이전 것이 지도에 그대로 남는다.
 */
private class ScenePainter {

    private var map: KakaoMap? = null
    private var onPinClick: ((String) -> Unit)? = null

    private var labels: List<Label> = emptyList()
    private var routeLine: RouteLine? = null

    /** 핀을 잇는 선. 코스 경로선과 따로 관리한다 — 한쪽만 있는 화면이 있다(§3-8). */
    private var pinLine: RouteLine? = null

    /** 직전 장면. 카메라를 전체로 맞출지 이동만 할지 가르는 근거다. */
    private var previous: MapScene? = null

    fun attach(map: KakaoMap, onPinClick: (String) -> Unit) {
        this.map = map
        this.onPinClick = onPinClick
        map.setOnLabelClickListener { _, _, label ->
            val id = label.tag as? String
            if (id != null) onPinClick(id)
            // true 를 돌려주면 지도 기본 동작을 막는다 — 핀 탭은 우리가 처리한다
            id != null
        }
    }

    fun detach() {
        map = null
        onPinClick = null
        labels = emptyList()
        routeLine = null
        pinLine = null
        previous = null
    }

    fun draw(metrics: DisplayMetrics, scene: MapScene) {
        val map = this.map ?: return

        drawRoute(map, scene)
        drawPins(map, metrics, scene)
        moveCamera(map, scene)

        previous = scene
    }

    /**
     * 선 두 종류를 그린다. (SPEC §3-8)
     *
     * - **코스 경로선**(`scene.route`) — 핀과 독립이라 핀이 없어도 그린다. S8 러닝코스가 이것만 쓴다
     * - **핀을 잇는 선**(`scene.pinPath`) — S7 동선의 "항목을 잇는 폴리라인". 흩어진 장소
     *   목록은 `connectPins` 를 꺼서 안 그린다
     *
     * 둘을 따로 두는 이유는 한쪽만 있는 화면이 있어서다. 같은 객체로 돌려 쓰면 S7 에서
     * 경로선이 생기는 순간 핀 선이 사라진다.
     */
    private fun drawRoute(map: KakaoMap, scene: MapScene) {
        val layer = map.routeLineManager?.layer ?: return

        routeLine?.let { layer.remove(it) }
        routeLine = drawLine(layer, scene.route)

        pinLine?.let { layer.remove(it) }
        pinLine = drawLine(layer, scene.pinPath)
    }

    private fun drawLine(layer: RouteLineLayer, points: List<LatLng>): RouteLine? {
        if (points.size < MIN_ROUTE_POINTS) return null

        val stylesSet = RouteLineStylesSet.from(
            RouteLineStyles.from(RouteLineStyle.from(ROUTE_WIDTH_DP, Blue.toArgb())),
        )
        val segment = RouteLineSegment.from(points.map { it.toKakao() }, stylesSet.getStyles(0))
        return layer.addRouteLine(RouteLineOptions.from(segment))
    }

    /**
     * 방문 순서 번호 핀.
     *
     * 핀은 매번 전부 지우고 다시 만든다. 활성 핀만 바뀌어도 크기·테두리가 달라져서
     * 어차피 비트맵을 새로 그려야 하기 때문이다.
     */
    private fun drawPins(map: KakaoMap, metrics: DisplayMetrics, scene: MapScene) {
        val layer = map.labelManager?.layer ?: return
        if (labels.isNotEmpty()) {
            layer.remove(*labels.toTypedArray())
            labels = emptyList()
        }
        if (scene.pins.isEmpty()) return

        labels = scene.pins.mapNotNull { pin ->
            val active = pin.id == scene.activePinId
            val accent = if (pin.recovery) Orange else Blue
            val bitmap = PinBitmap.of(metrics, pin.order, accent, active)
            val options = LabelOptions.from(pin.id, KakaoLatLng.from(pin.lat, pin.lng))
                .setStyles(LabelStyles.from(LabelStyle.from(bitmap)))
                .setClickable(true)
                .setTag(pin.id)
                // 활성 핀이 다른 핀 밑에 깔리면 강조한 의미가 없다
                .setRank(if (active) ACTIVE_RANK else BASE_RANK)
            layer.addLabel(options)
        }
    }

    private fun moveCamera(map: KakaoMap, scene: MapScene) {
        when (val command = cameraCommandFor(previous, scene)) {
            is CameraCommand.FitBounds -> map.moveCamera(
                CameraUpdateFactory.fitMapPoints(
                    command.points.map { it.toKakao() }.toTypedArray(),
                    FIT_PADDING_PX,
                ),
            )

            is CameraCommand.MoveTo -> map.moveCamera(
                CameraUpdateFactory.newCenterPosition(command.point.toKakao()),
                CameraAnimation.from(CAMERA_ANIMATION_MS),
            )

            CameraCommand.None -> Unit
        }
    }

    private companion object {
        const val ROUTE_WIDTH_DP = 5f
        const val FIT_PADDING_PX = 60
        const val CAMERA_ANIMATION_MS = 300
        const val BASE_RANK = 0L
        const val ACTIVE_RANK = 10L
    }
}

private fun LatLng.toKakao(): KakaoLatLng = KakaoLatLng.from(lat, lng)

/** 지도만 못 띄운 것이지 화면이 죽은 게 아니다. 할 수 있는 일을 알려준다(NFR-3). */
private const val MAP_UNAVAILABLE = "지도를 불러오지 못했어요.\n목록으로 확인해 주세요."
