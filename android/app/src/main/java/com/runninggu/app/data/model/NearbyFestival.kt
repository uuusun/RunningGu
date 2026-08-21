package com.runninggu.app.data.model

import java.time.LocalDate

/**
 * 대회 인근 축제 한 건. (API 명세 §3-5 · SPEC §4.6)
 *
 * 홈 축제 섹션([Festival])과 **다른 계약**이다 — 이쪽은 대회장 기준 거리(`distanceKm`)가
 * 있고 조회 기준도 "대회일 ±14일 · 반경 40km · 거리순 6건" 이다. 모델을 합치면
 * 한쪽에만 있는 필드를 다른 쪽이 null 로 들고 다니게 된다.
 *
 * 기간·거리 계산은 **서버가 한다**(§8.3). 앱은 받은 순서와 값을 그대로 쓴다.
 */
data class NearbyFestival(
    val contentId: String,
    val name: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    /** 대회장 기준 직선거리(km). 카드에 "대회장 {d.d}km" 로 쓴다. */
    val distanceKm: Double,
    val imageUrl: String?,
    val address: String,
)
