package com.runninggu.app.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * 출발지 검색 결과. (API 명세 §4-4 `GET /api/geocode`)
 *
 * **카카오 키워드 검색의 첫 결과 하나만** 온다 — 목록이 아니다(SPEC §4.11-1 ②).
 * 결과가 없으면 본문이 아니라 `404 NO_RESULT` 로 온다.
 */
@Serializable
data class GeocodeDto(
    val name: String = "",
    val address: String = "",
    val lat: Double,
    val lng: Double,
)
