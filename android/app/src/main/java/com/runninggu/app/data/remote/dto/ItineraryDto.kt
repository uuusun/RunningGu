package com.runninggu.app.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * `POST /api/itineraries/generate` 요청. (API 명세 §5-1)
 *
 * 날짜는 **KST 비즈니스 날짜** 문자열이다(`YYYY-MM-DD`) — timestamp 가 아니다(AGENTS 2장-4).
 * `hotel` 은 null 을 허용한다. "숙소 없이 추천받기" 면 서버가 대회장 중심으로 슬롯을 채운다
 * (SPEC §4.9).
 */
@Serializable
data class GenerateItineraryRequestDto(
    /** canonical `CONTEST.id`. **숫자다** — 번들 externalId 를 보내면 안 된다(#66 리뷰). */
    val contestId: Long,
    val startDate: String,
    val endDate: String,
    /** `FULL|HALF|TEN_K|FIVE_K` */
    val event: String,
    /** 1개 이상. 0개면 화면이 CTA 를 막는다 (§4.8). */
    val themes: List<String>,
    val hotel: HotelDto? = null,
)

/** 요청·응답 공통 숙소 표기. (§5-1) */
@Serializable
data class HotelDto(val name: String, val lat: Double, val lng: Double)

/**
 * `POST /api/itineraries/generate` 응답. (API 명세 §5-1 · SPEC 결정-41)
 *
 * 동선 생성은 **서버 단일 주체**다. 앱은 이 DTO 를 받아 표시하고 저장 전 USER 블록만 편집한다.
 */
@Serializable
data class GenerateItineraryResponse(
    val title: String,
    val event: String,
    val recovery: RecoveryDto? = null,
    val days: List<DayDto> = emptyList(),
    // §5-2 저장 요청이 이 응답 구조를 그대로 쓴다 — 버리면 저장·재생성 요청을 못 만든다
    val contestId: Long? = null,
    val themes: List<String> = emptyList(),
    val startDate: String? = null,
    val endDate: String? = null,
    val hotel: HotelDto? = null,
)

@Serializable
data class RecoveryDto(val label: String, val note: String)

@Serializable
data class DayDto(
    val dayIndex: Int,
    val date: String,
    val dayLabel: String,
    /** 회복일인가. 일자 탭과 지도 핀 색을 가른다. (API 명세 §5-1) */
    val recovery: Boolean = false,
    val note: String = "",
    val blocks: List<BlockDto> = emptyList(),
)

/**
 * 일정 블록.
 *
 * 외부 POI 조회가 실패하면 서버가 `placeName`·`lat`·`lng` 를 null 로 강등하되 생성은 성공시킨다
 * (API 명세 §5-1 · NFR-3). 그래서 장소 없는 블록이 정상적으로 올 수 있다.
 */
@Serializable
data class BlockDto(
    val startTime: String,
    val title: String,
    val category: String,
    val placeName: String? = null,
    val address: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val description: String = "",
    val blockType: String = "USER",
    val systemManaged: Boolean = false,
)
