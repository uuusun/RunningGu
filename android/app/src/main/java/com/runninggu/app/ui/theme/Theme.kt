package com.runninggu.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 목업 v2 토큰을 Material 3 색 슬롯에 배치한 스킴.
 *
 * 배치 의도:
 * - primary       브랜드 파랑 — CTA·활성 탭·D-day·강조 텍스트
 * - primaryContainer  파랑 soft — 선택된 칩·기간 하이라이트·featured 카드 바탕
 * - tertiary      라임 — "접수중" 같은 긍정 상태 배지 (목업 .chip-open)
 * - background    page(웜 그레이) — 카드가 떠 보이게 하는 앱 바탕
 * - surface       흰색 — 카드·시트·앱바
 * - surfaceVariant fill — 입력창·세그먼트·썸네일 자리
 * - error         빨강 — 오류 문구와 찜 하트
 */
private val RunningGuColorScheme = lightColorScheme(
    primary = Blue,
    onPrimary = Surface,
    primaryContainer = BlueSoft,
    onPrimaryContainer = BlueInk,

    secondary = Ink2,
    onSecondary = Surface,
    secondaryContainer = Fill2,
    onSecondaryContainer = Ink,

    tertiary = Lime,
    onTertiary = Ink,
    tertiaryContainer = Lime,
    onTertiaryContainer = Ink,

    // 앱 배경은 흰색이다. 목업의 --page(웜그레이)는 폰 목업들이 놓인 데스크톱 캔버스
    // 색이지 앱 화면 색이 아니다 — .phone{background:var(--surface)}가 실제 앱 바탕이다.
    background = Surface,
    onBackground = Ink,

    surface = Surface,
    onSurface = Ink,
    surfaceVariant = Fill,
    onSurfaceVariant = Ink3,

    // 면 계열을 전부 명시한다. 비워두면 Material이 primary를 섞어 톤을 만들어내서
    // 탭바·카드가 라벤더로 물든다 — 목업은 흰색/웜그레이 중립이다.
    surfaceContainerLowest = Surface,
    surfaceContainerLow = Surface,
    surfaceContainer = Surface,
    surfaceContainerHigh = Fill,
    surfaceContainerHighest = Fill2,
    surfaceBright = Surface,
    surfaceDim = Fill,
    inverseSurface = Ink,
    inverseOnSurface = Surface,
    // 고도(elevation)에 따라 primary를 덧입히는 기능도 끈다.
    surfaceTint = Surface,

    outline = Line,
    outlineVariant = Line2,

    error = SundayRed,
    onError = Surface,
    errorContainer = ErrorSoft,
    onErrorContainer = SundayRed,

    scrim = Deep,
)

/**
 * 앱 테마. (SPEC §3-1 · AP-06)
 *
 * **다이내믹 컬러는 쓰지 않는다.** 켜두면 Android 12+ 기기가 사용자 배경화면에서 뽑은 색으로
 * 덮어써서, 기기마다 다른 색이 나오고 목업의 브랜드 파랑이 사라진다.
 *
 * 다크 테마도 두지 않는다 — 목업 v2가 라이트 단일 레지스터이고 SPEC에도 요구가 없다.
 * (지도의 '몰입 다크'는 화면 단위 처리이지 앱 테마가 아니다.)
 */
@Composable
fun RunningGuTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = RunningGuColorScheme,
        typography = Typography,
        shapes = RunningGuShapes,
        content = content,
    )
}
