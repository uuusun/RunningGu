package com.runninggu.app.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.DisplayMetrics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt

/**
 * 번호 핀을 비트맵으로 그린다. (SPEC §3-8)
 *
 * 카카오맵 라벨은 비트맵이나 drawable 리소스만 받는다(`LabelStyle.from`). 번호가 1~N 로
 * 계속 달라져서 리소스로 미리 만들어 둘 수 없어 직접 그린다.
 *
 * **활성 핀은 크게 + 흰 테두리**로 그린다 — 목업의 `.pin.active` 와 같은 강조다.
 */
internal object PinBitmap {

    private const val BASE_DP = 28f
    private const val ACTIVE_SCALE = 1.28f
    private const val RING_DP = 2.5f

    /**
     * 가장 큰 핀(활성)의 한 변 길이(dp).
     *
     * 카메라 여백이 이 값의 절반보다 작으면 **핀이 지도 가장자리에서 잘린다** — 라벨은
     * 좌표를 중심에 두고 그려지므로 절반이 바깥으로 솟는다(#162). [cameraFitPaddingPx] 가
     * 이걸 쓴다.
     */
    const val MAX_SIZE_DP = BASE_DP * ACTIVE_SCALE + RING_DP * 2

    /**
     * @param order 핀 안에 찍을 번호. 1부터.
     * @param accent 핀 바탕색. 회복일은 주황이 넘어온다(§3-8 범례).
     */
    fun of(metrics: DisplayMetrics, order: Int, accent: Color, active: Boolean): Bitmap {
        val density = metrics.density
        val diameter = BASE_DP * density * if (active) ACTIVE_SCALE else 1f
        val ring = if (active) RING_DP * density else 0f
        val size = (diameter + ring * 2).roundToInt().coerceAtLeast(1)

        val bitmap = createBitmap(size)
        val canvas = Canvas(bitmap)
        val center = size / 2f

        if (active) {
            // 흰 테두리를 먼저 깔아야 배경 위에서 핀이 떠 보인다
            canvas.drawCircle(center, center, center, fill(Color.White.toArgb()))
        }
        canvas.drawCircle(center, center, diameter / 2f, fill(accent.toArgb()))

        val text = order.toString()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.White.toArgb()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = diameter * 0.5f
            textAlign = Paint.Align.CENTER
        }
        // baseline 을 그냥 center 로 두면 글자가 아래로 치우친다
        val baseline = center - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, center, baseline, textPaint)

        return bitmap
    }

    private fun createBitmap(size: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    private fun fill(argb: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb
        style = Paint.Style.FILL
    }
}
