package com.runninggu.app.domain

import java.time.LocalDate

/**
 * 동선 엔진 입력. (SPEC §5.6)
 *
 * [event] 는 **필수**다. "미선택이면 하프" 같은 기본값은 §4.8 의 **화면 규칙**이지
 * §5 계산 규칙이 아니므로 S5 가 정해서 넣는다. 엔진이 떠안으면 화면에 보이는 종목과
 * 계산에 쓰인 종목이 어긋날 수 있다.
 */
data class ItineraryPlan(
    val race: RaceInfo,
    val stay: Poi?,
    val event: EventType,
    val themes: List<PoiCategory>,
    val start: LocalDate,
    val end: LocalDate,
)

/** 동선 생성에 필요한 대회 정보만 추린 것. */
data class RaceInfo(
    val id: String,
    val name: String,
    val date: LocalDate,
    val lat: Double,
    val lng: Double,
    val venue: String = "",
    val region: String = "",
    val startTime: String = "",
    val officialUrl: String = "",
)

/**
 * 블록 종류. (SPEC §5.6-7 · §5.7)
 *
 * [RACE] 는 시스템이 관리하므로 사용자가 바꿀 수 없다 — 편집 연산이 전부 거부하고,
 * 서버는 `409 SYSTEM_BLOCK_IMMUTABLE` 을 돌려준다.
 */
enum class BlockType { USER, RACE }

/** 일정 블록 하나. (SPEC §6.3 `block`) */
data class ItineraryBlock(
    /** 편집 간 유지되는 안정적 id. (SPEC §6.3) */
    val id: String,
    val time: String,
    val title: String,
    val catKey: BlockCategory,
    val place: Poi?,
    val desc: String,
    val blockType: BlockType = BlockType.USER,
    val systemManaged: Boolean = false,
)

/** 하루치. (SPEC §6.3 `day`) */
data class ItineraryDay(
    val date: LocalDate,
    /** 대회일 기준 오프셋. `-1` = 전날. */
    val off: Int,
    /** `D-1` · `D-day` · `D+2` */
    val label: String,
    /** `MM.DD 요일` */
    val dateLabel: String,
    val note: String,
    val blocks: List<ItineraryBlock>,
)

/**
 * 생성 결과. (SPEC §5.6)
 *
 * @param sources 카테고리별 POI 출처. 결과 화면이 배지로 노출한다(NFR-2).
 * @param recovery 회복 배지. `noHard` 종목(하프·풀)이 아니면 null.
 */
data class Itinerary(
    val days: List<ItineraryDay>,
    val sources: Map<PoiCategory, PoiSourceKind>,
    val recovery: RecoveryBadge?,
    val plan: ItineraryPlan,
)
