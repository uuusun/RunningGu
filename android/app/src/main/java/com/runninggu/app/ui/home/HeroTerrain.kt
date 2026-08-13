package com.runninggu.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.runninggu.app.ui.theme.Deep
import com.runninggu.app.ui.theme.Lime
import kotlin.math.floor
import kotlin.math.sin

/**
 * 홈 히어로의 코스 지형 배경. (목업 v2 `.gl-panel`)
 *
 * 목업은 three.js WebGL로 3D 지형을 그리고, WebGL이 막히면 `svgFallback()`으로 능선 4겹 +
 * 경로 리본을 SVG로 그린다. 여기서는 **그 SVG 폴백을 그대로 옮겼다** — 값(노이즈 계수·능선
 * 간격·음영 4색·리본 두께)을 바꾸지 않아야 목업과 같은 그림이 나온다.
 *
 * 3D 자체를 포팅하지 않은 이유: 정지 화면에서 폴백과 차이가 크지 않은데 비해
 * 런타임(OpenGL 뷰 + 생명주기 관리) 비용이 크다. 필요해지면 이 컴포저블만 교체하면 된다.
 */
@Composable
fun HeroTerrain(
    modifier: Modifier = Modifier,
    seed: Int = 1,
    route: List<Pair<Float, Float>> = DEFAULT_ROUTE,
) {
    Canvas(modifier = modifier) {
        drawTerrainBackground()
        drawRidges(seed)
        drawRoute(route)
        drawVeil()
    }
}

/** 목업 기본 경로 — `spec.route` 기본값과 동일하다. */
private val DEFAULT_ROUTE = listOf(
    -0.6f to -0.4f,
    0.2f to -0.5f,
    0.55f to 0.05f,
    0.1f to 0.5f,
    -0.5f to 0.35f,
)

// 목업 SVG 폴백의 좌표계. 실제 크기와 무관하게 이 비율로 계산한 뒤 캔버스에 늘린다.
private const val VB_W = 390f
private const val VB_H = 320f

/** radial-gradient(120% 90% at 50% 0%, --deep-3, --deep-2 46%, --deep) */
private fun DrawScope.drawTerrainBackground() {
    drawRect(
        brush = Brush.radialGradient(
            0f to Color(0xFF1B2450),   // --deep-3
            0.46f to Color(0xFF131A38), // --deep-2
            1f to Deep,                 // --deep
            center = Offset(size.width / 2f, 0f),
            radius = maxOf(size.width * 1.2f, size.height * 0.9f),
        ),
    )
}

/**
 * viewBox 좌표를 캔버스 좌표로 옮긴다.
 *
 * 목업 SVG는 `preserveAspectRatio="xMidYMid slice"` — **비율을 유지한 채 채우고 넘치면 자른다**.
 * 가로·세로를 따로 늘리면(stretch) 경로 리본이 세로로 늘어져 텍스트를 덮는다.
 */
private class ViewBox(width: Float, height: Float) {
    val scale = maxOf(width / VB_W, height / VB_H)
    private val dx = (width - VB_W * scale) / 2f
    private val dy = (height - VB_H * scale) / 2f
    fun map(x: Float, y: Float) = Offset(dx + x * scale, dy + y * scale)
}

/** 능선 4겹. 뒤쪽일수록 밝고 앞쪽으로 올수록 어두워진다. */
private fun DrawScope.drawRidges(seed: Int) {
    val noise = makeNoise(seed)
    val shades = listOf(
        Color(0xFF1B2450),
        Color(0xFF182050),
        Color(0xFF131A38),
        Color(0xFF101736),
    )
    val vb = ViewBox(size.width, size.height)

    for (r in 0 until 4) {
        val path = Path().apply {
            // 아래 변은 캔버스 바닥까지 채워 잘린 영역에 빈틈이 생기지 않게 한다.
            moveTo(0f, size.height)
            var x = 0f
            while (x <= VB_W) {
                val y = VB_H * (0.42f + r * 0.13f) -
                    heightAt(noise, x / VB_W + r * 0.3f, r * 0.5f) * 70f
                val p = vb.map(x, y)
                lineTo(p.x, p.y)
                x += 10f
            }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(path, shades[r])
    }
}

/** 라임 경로 리본 + 시작점 원. */
private fun DrawScope.drawRoute(route: List<Pair<Float, Float>>) {
    // 리본만은 능선과 달리 캔버스 비율에 맞춰 배치한다.
    //
    // 목업 폴백은 (x*.42+.5, z*.3+.55)를 390×320 뷰박스에 놓고 slice로 자르는데,
    // 세로로 긴 히어로(390×472)에서 그대로 하면 좌우가 잘리고 검색창·대회명 위를 지나간다.
    // 3D 버전에서 리본이 놓이는 자리(검색창 아래 ~ 대회 정보 위의 빈 띠)에 맞춰
    // 가로는 폭에 맞추고 세로는 0.35~0.55 구간에 오도록 계수만 조정했다. 경로 모양은 그대로다.
    val points = route.map { (x, z) ->
        Offset(
            x = (x * 0.42f + 0.5f) * size.width,
            y = (z * 0.20f + 0.45f) * size.height,
        )
    }
    if (points.isEmpty()) return
    val sx = size.width / VB_W

    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(
        path = path,
        color = Lime,
        style = Stroke(width = 4f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawCircle(color = Lime, radius = 6f * sx, center = points.first())
}

/** 하단 38%를 어둡게 덮어 텍스트 가독성을 확보한다. (목업 .gl-veil) */
private fun DrawScope.drawVeil() {
    val veilHeight = size.height * 0.38f
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Deep.copy(alpha = 0f), Deep.copy(alpha = 0.92f)),
            startY = size.height - veilHeight,
            endY = size.height,
        ),
        topLeft = Offset(0f, size.height - veilHeight),
        size = androidx.compose.ui.geometry.Size(size.width, veilHeight),
    )
}

/**
 * 목업 `makeNoise` 이식 — 값 노이즈. 시드가 같으면 항상 같은 지형이 나온다.
 * 계수(127.1 · 311.7 · 43758.5453 · 157 · 113)는 목업 그대로다.
 */
private fun makeNoise(seed: Int): (Float, Float) -> Float {
    fun hash(n: Float): Float {
        val s = sin(n * 127.1f + seed * 311.7f) * 43758.5453f
        return s - floor(s)
    }

    fun grid(x: Int, y: Int): Float = hash(x * 157f + y * 113f)

    return { x, y ->
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val xf = x - xi
        val yf = y - yi
        // smoothstep
        val u = xf * xf * (3 - 2 * xf)
        val v = yf * yf * (3 - 2 * yf)
        grid(xi, yi) * (1 - u) * (1 - v) + grid(xi + 1, yi) * u * (1 - v) +
            grid(xi, yi + 1) * (1 - u) * v + grid(xi + 1, yi + 1) * u * v
    }
}

/** 목업 `heightAt` 이식 — 옥타브 3개를 .62/.28/.10으로 합친다. */
private fun heightAt(noise: (Float, Float) -> Float, x: Float, z: Float): Float =
    noise(x * 1.6f + 4, z * 1.6f + 4) * 0.62f +
        noise(x * 3.6f, z * 3.6f) * 0.28f +
        noise(x * 8f, z * 8f) * 0.10f
