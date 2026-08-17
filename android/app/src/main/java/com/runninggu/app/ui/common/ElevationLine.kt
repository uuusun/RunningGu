package com.runninggu.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.runninggu.app.ui.theme.Blue
import com.runninggu.app.ui.theme.BlueSoft
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * 대회 카드의 코스 고도 스트립. (목업 v2 `.elev`)
 *
 * ```
 * .elev .line { stroke: var(--blue); stroke-width: 2 }
 * .elev .area { fill: var(--blue-soft); opacity: .9 }
 * .elev.closed .line { stroke:#C9CBD1 }  .elev.closed .area { fill:#F1F1F2 }
 * ```
 *
 * 코스 고도 API가 아직 없으므로 [seed]로 결정적인 능선을 만든다 — 같은 대회는 항상 같은
 * 모양이라 목록을 다시 그려도 흔들리지 않는다.
 *
 * TODO(AP-12): 두루누비 GPX 고도 배열이 붙으면 [profile]로 실제 값을 넘긴다.
 */
@Composable
fun ElevationLine(
    seed: Int,
    closed: Boolean,
    modifier: Modifier = Modifier,
    profile: List<Float>? = null,
) {
    val lineColor = if (closed) ClosedLine else Blue
    val areaColor = if (closed) ClosedArea else BlueSoft.copy(alpha = 0.9f)

    Canvas(modifier = modifier) {
        val points = profile ?: generateProfile(seed, SAMPLE_COUNT)
        if (points.size < 2) return@Canvas

        val stepX = size.width / (points.size - 1)
        // 위아래로 여백을 조금 남겨 선이 잘리지 않게 한다.
        val top = size.height * 0.12f
        val usable = size.height * 0.76f
        fun yAt(v: Float) = top + usable * (1f - v)

        val linePath = Path().apply {
            moveTo(0f, yAt(points[0]))
            points.forEachIndexed { i, v -> if (i > 0) lineTo(i * stepX, yAt(v)) }
        }
        val areaPath = Path().apply {
            addPath(linePath)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }

        drawPath(areaPath, areaColor)
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private const val SAMPLE_COUNT = 26
private val ClosedLine = Color(0xFFC9CBD1)
private val ClosedArea = Color(0xFFF1F1F2)

/**
 * 시드에서 0~1 사이 고도 배열을 만든다. 주기가 다른 사인파 3개를 겹쳐
 * 실제 코스 프로파일처럼 완만한 오르내림을 만든다.
 */
private fun generateProfile(seed: Int, count: Int): List<Float> {
    val a = 0.7f + fract(seed * 0.37f) * 1.6f
    val b = 1.9f + fract(seed * 0.71f) * 2.2f
    val c = 4.1f + fract(seed * 0.13f) * 3.0f
    val phase = fract(seed * 0.53f) * 6.28f

    val raw = (0 until count).map { i ->
        val t = i / (count - 1f)
        sin(t * a * 6.28f + phase) * 0.5f +
            sin(t * b * 6.28f + phase * 1.7f) * 0.3f +
            sin(t * c * 6.28f + phase * 2.3f) * 0.2f
    }
    val min = raw.min()
    val max = raw.max()
    val span = (max - min).takeIf { abs(it) > 1e-4f } ?: 1f
    return raw.map { (it - min) / span }
}

private fun fract(v: Float): Float = v - floor(v)
