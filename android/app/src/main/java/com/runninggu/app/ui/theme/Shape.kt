package com.runninggu.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 모서리 반경. 목업 v2의 `--r-*` 토큰을 Material 3 [Shapes] 슬롯에 맞췄다.
 *
 * | 목업        | 값    | Material 슬롯 | 쓰는 곳                     |
 * |------------|-------|--------------|----------------------------|
 * | --r-xs     | 8px   | extraSmall   | 썸네일·작은 배지             |
 * | --r-sm     | 11px  | small        | 태그                        |
 * | --r-md     | 15px  | medium       | 세그먼트·바텀시트 내부 블록    |
 * | --r-card   | 20px  | large        | 카드                        |
 * | (시트)      | 24px  | extraLarge   | 바텀시트 상단                |
 *
 * 버튼(--r-btn 16px)과 칩(--r-chip 13px)은 Material 컴포넌트가 자체 기본값을 쓰므로
 * 필요한 곳에서 [ButtonRadius]·[ChipRadius]로 직접 지정한다.
 */
val RunningGuShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(15.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** --r-btn 16px — CTA·기본 버튼. */
val ButtonRadius = RoundedCornerShape(16.dp)

/** --r-chip 13px — 상태 칩·배지. */
val ChipRadius = RoundedCornerShape(13.dp)

/** --cta-h 56px — 하단 고정 CTA 높이. */
const val CtaHeightDp = 56
