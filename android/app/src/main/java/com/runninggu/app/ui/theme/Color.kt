package com.runninggu.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 런닝구 디자인 토큰. 출처는 목업 v2 (`docs/mockup-design/런닝구-목업-v2.html`)의 CSS 변수다.
 *
 * 이름·값을 목업과 1:1로 맞춰뒀다 — 목업이 바뀌면 여기만 고치면 화면 전체가 따라간다.
 * Material 색 슬롯 배치는 [RunningGuTheme] 참고.
 */

// 브랜드 — 파랑
val Blue = Color(0xFF2B5CFF)        // --blue
val BlueInk = Color(0xFF1E40D8)     // --blue-ink
val BlueSoft = Color(0xFFEEF2FF)    // --blue-soft

// 강조 — 라임(접수중 칩·주요 CTA 변형)
val Lime = Color(0xFFC9F23C)        // --lime
val LimeDeep = Color(0xFFB3DC28)    // --lime-deep

// 회복·주의
val Orange = Color(0xFFFF6A2B)      // --orange

// 잉크(텍스트) 스케일 — 진한 순
val Ink = Color(0xFF15161B)         // --ink
val Ink2 = Color(0xFF54565E)        // --ink2
val Ink3 = Color(0xFF71747C)        // --ink3
val Ink4 = Color(0xFF86878E)        // --ink4
val Ink5 = Color(0xFF9A9CA2)        // --ink5

// 면·선
val Surface = Color(0xFFFFFFFF)     // --surface
val Page = Color(0xFFEDECE8)        // --page  (앱 배경 — 카드가 떠 보이게 하는 웜 그레이)
val Fill = Color(0xFFF2F2F0)        // --fill  (입력창·세그먼트 바탕)
val Fill2 = Color(0xFFE9EBEF)       // --fill2
val Line = Color(0xFFE4E4E6)        // --line
val Line2 = Color(0xFFECECEE)       // --line2
val Line3 = Color(0xFFF0F0F0)       // --line3

// 몰입(다크) 레지스터 — 지도·기록 화면용. 아직 미사용.
val Deep = Color(0xFF0C1024)        // --deep

// 요일·상태
val SundayRed = Color(0xFFE5484D)
val SaturdayBlue = Blue

/** 오류 문구 바탕. 목업에 대응 토큰이 없어 SundayRed에서 옅게 파생했다. */
val ErrorSoft = Color(0xFFFFECED)

/**
 * 카테고리 태그 색. (SPEC §3-7 · 목업 --cat-*)
 * 동선 블록·POI 태그에서 쓴다 — S5·S7 작업에서 연결한다.
 */
object CategoryColors {
    val TourBg = Color(0xFFEAF1FF); val TourFg = Color(0xFF2B5CFF)
    val FoodBg = Color(0xFFFFF3CC); val FoodFg = Color(0xFFA66A00)
    val CafeBg = Color(0xFFF1E9FF); val CafeFg = Color(0xFF7A4FD0)
    val WellnessBg = Color(0xFFE6F7EE); val WellnessFg = Color(0xFF1B8A5A)
    val NatureBg = Color(0xFFF0FAD8); val NatureFg = Color(0xFF5A8A0A)
    val HistoryBg = Color(0xFFEFEAFE); val HistoryFg = Color(0xFF6B4FBB)
    val LodgingBg = Color(0xFFEAEFF5); val LodgingFg = Color(0xFF3E5573)
    val RaceBg = Color(0xFFEEF2FF); val RaceFg = Color(0xFF2B5CFF)
    val RecoveryBg = Color(0xFFFFF1E9); val RecoveryFg = Color(0xFFC2410C)
}
