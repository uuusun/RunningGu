package com.runninggu.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.runninggu.app.ui.theme.Deep
import com.runninggu.app.ui.theme.Lime

/**
 * 홈 히어로의 코스 지형 배경. (목업 v2 `.gl-panel`)
 *
 * 목업은 three.js로 지형 메시를 원근 투영하고 그 위에 코스 경로를 리본으로 얹는다.
 * 화면에 실제로 읽히는 것은 ① 위로 갈수록 밝아지는 매끈한 파란 지면,
 * ② 미세한 메시 격자, ③ **화면을 가로지르는 라임 호 2개**(코스 루프의 앞·뒤 구간)와
 * 정점의 노드 점이다. 여기서는 그 결과를 Canvas로 재현한다.
 *
 * 정지 화면에서 3D와 차이가 크지 않아 OpenGL 뷰를 얹지 않았다.
 * 실제 코스 데이터를 굴려야 하면 이 컴포저블만 교체하면 된다.
 */
@Composable
fun HeroTerrain(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawGround()
        drawMeshGrid()
        drawCourseRibbon()
        drawVeil()
    }
}

// three.js 지형의 정점 색 (cLow · cMid · cHi) — 낮은 곳은 어둡고 높은 곳은 밝다.
private val TerrainLow = Color(0xFF111838)
private val TerrainMid = Color(0xFF27367A)
private val TerrainHigh = Color(0xFF5570D6)

/**
 * 지면. 위쪽(먼 곳)이 밝고 아래로 내려올수록 어두워진다 —
 * 목업의 정점 색 + `scene.fog(0x0c1024)`가 합쳐진 결과와 같은 인상.
 */
private fun DrawScope.drawGround() {
    // 상단(상태바·로고·검색창 뒤)은 어둡게 두고, 지면이 시작되는 중간이 가장 밝다.
    drawRect(
        brush = Brush.verticalGradient(
            0f to Deep,
            0.18f to Color(0xFF161D42),
            0.34f to Color(0xFF2A3A80),
            0.52f to TerrainMid,
            0.78f to TerrainLow,
            1f to Deep,
        ),
    )
}

/**
 * 메시 격자. 원근이라 아래로 갈수록 간격이 넓어지고, 세로선은 소실점으로 모인다.
 * 아주 옅게 깔아 질감만 남긴다.
 */
private fun DrawScope.drawMeshGrid() {
    val line = TerrainHigh.copy(alpha = 0.13f)
    val horizon = size.height * 0.16f
    val vanishX = size.width * 0.5f

    // 가로선 — 소실점에서 멀어질수록 간격이 벌어진다.
    for (i in 1..9) {
        val t = i / 9f
        val y = horizon + (size.height - horizon) * (t * t)
        drawLine(
            color = line,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
    }
    // 세로선 — 바닥에서 퍼지고 소실점으로 모인다.
    for (i in -4..4) {
        val bottomX = vanishX + i * size.width * 0.34f
        drawLine(
            color = line,
            start = Offset(vanishX + i * size.width * 0.045f, horizon),
            end = Offset(bottomX, size.height),
            strokeWidth = 1f,
        )
    }
}

/**
 * 코스 루프. 원근으로 보면 앞·뒤 구간이 화면을 가로지르는 호 2개로 보인다.
 * 위쪽 호에는 경로 위의 지점을 뜻하는 노드 점을 찍는다.
 */
private fun DrawScope.drawCourseRibbon() {
    val w = size.width
    val h = size.height
    val stroke = w * 0.024f // 목업 리본 두께(≈9px @390) 비율

    // 먼 쪽 구간 — 검색창 **아래**를 지나야 정점의 노드가 가려지지 않는다.
    val far = Path().apply {
        moveTo(-w * 0.06f, h * 0.50f)
        quadraticTo(w * 0.52f, h * 0.345f, w * 1.06f, h * 0.51f)
    }
    drawPath(far, Lime, style = Stroke(width = stroke, cap = StrokeCap.Round))

    // 노드 — 호의 정점. 제어점이 아니라 **곡선 위의 점**이어야 한다.
    // 2차 베지어의 t=0.5 지점은 0.25·P0 + 0.5·P1 + 0.25·P2 다.
    val node = Offset(
        x = (-0.06f * 0.25f + 0.52f * 0.5f + 1.06f * 0.25f) * w,
        y = (0.50f * 0.25f + 0.345f * 0.5f + 0.51f * 0.25f) * h,
    )
    drawCircle(Lime, radius = stroke * 0.95f, center = node)

    // 가까운 쪽 구간 — 아래쪽에서 veil에 절반쯤 잠긴다.
    val near = Path().apply {
        moveTo(-w * 0.06f, h * 0.98f)
        quadraticTo(w * 0.5f, h * 0.80f, w * 1.06f, h * 0.98f)
    }
    drawPath(near, Lime.copy(alpha = 0.85f), style = Stroke(width = stroke, cap = StrokeCap.Round))
}

/** 하단을 어둡게 덮어 대회 정보·CTA의 가독성을 확보한다. (목업 .gl-veil) */
private fun DrawScope.drawVeil() {
    val veilHeight = size.height * 0.38f
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Deep.copy(alpha = 0f), Deep.copy(alpha = 0.92f)),
            startY = size.height - veilHeight,
            endY = size.height,
        ),
        topLeft = Offset(0f, size.height - veilHeight),
        size = Size(size.width, veilHeight),
    )
}
