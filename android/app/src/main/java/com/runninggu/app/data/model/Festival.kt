package com.runninggu.app.data.model

import java.time.LocalDate

/**
 * 홈 축제 섹션 한 건. (API 명세 §4-1 · SPEC §4.4)
 *
 * [inProgress] 는 **서버 판정을 그대로 쓴다.** 앱이 오늘 날짜로 다시 계산하면 서버와
 * 기준이 갈린다 — 배지 하나 때문에 두 벌 규칙을 만들 이유가 없다.
 */
data class Festival(
    val contentId: String,
    val name: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val region: String,
    val imageUrl: String?,
    val inProgress: Boolean,
)
