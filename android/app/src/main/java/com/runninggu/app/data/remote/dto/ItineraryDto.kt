package com.runninggu.app.data.remote.dto

import kotlinx.serialization.Contextual
import java.time.Instant
import java.time.LocalDate
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
    /** `FULL|HALF|K10|K5` — 부록 C 계약 값이다. 앱 enum 이름이 아니다. */
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
    /** 요청과 같은 계약 값 — `FULL|HALF|K10|K5` 다. */
    val event: String,
    val recovery: RecoveryDto? = null,
    val days: List<DayDto> = emptyList(),
    // §5-2 저장 요청이 이 응답 구조를 그대로 쓴다 — 버리면 저장·재생성 요청을 못 만든다.
    // 저장에 필요한 값이라 **필수**다. nullable 로 두면 빠진 응답이 조용히 통과한다(#66 리뷰)
    val contestId: Long,
    val themes: List<String>,
    val startDate: String,
    val endDate: String,
    /** 숙소 없이 추천받은 동선은 null 이다. (§4.9) */
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

/**
 * `GET /api/itineraries` 목록 항목. (API 명세 §5-4)
 *
 * **상세(§5-5)와 다른 모양이다.** 목록은 카드가 쓰는 값만 오고 `days`·`blocks` 는 없다 —
 * 카드 하나를 그리려고 트리 전체를 받지 않는다.
 *
 * [needsRegeneration] 은 저장 snapshot 과 현재 canonical 대회가 다를 때 참이다. 이름만
 * 바뀌거나 [active] 만 달라진 것으로는 참이 되지 않는다(§5-4).
 */
@Serializable
data class ItinerarySummaryDto(
    val id: Long,
    val title: String,
    val contestId: Long,
    val contestName: String,
    val event: String,
    val region: String? = null,
    val recovery: RecoveryDto? = null,
    @Contextual val startDate: LocalDate,
    @Contextual val endDate: LocalDate,
    val placeCount: Int = 0,
    @Contextual val createdAt: Instant? = null,
    /** 대회가 원천에서 사라져도 **목록에서 지우지 않는다**(§5-4). 표시만 흐려진다. */
    val active: Boolean = true,
    val needsRegeneration: Boolean = false,
)

