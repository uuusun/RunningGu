package com.runninggu.app.ui.wizard

import com.runninggu.app.domain.BlockCategory
import com.runninggu.app.domain.BlockType
import com.runninggu.app.domain.ItineraryBlock
import com.runninggu.app.domain.ItineraryDay
import com.runninggu.app.domain.Poi
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * `POST /api/itineraries/generate` 응답. (API 명세 §5-1 · SPEC 결정-41)
 *
 * 동선 생성은 **서버 단일 주체**다. 앱은 이 DTO 를 받아 표시하고 저장 전 USER 블록만 편집한다.
 *
 * TODO(AP-14): `data/remote` 로 옮기고 Retrofit 응답 타입으로 쓴다. 지금 `ui/wizard` 에 둔 것은
 *  `data/` 패키지 생성이 AP-14(앱 코어 담당) 범위라서다 — 화면 개발을 막지 않으려는 임시 위치다.
 */
@Serializable
data class GenerateItineraryResponse(
    val title: String,
    val event: String,
    val recovery: RecoveryDto? = null,
    val days: List<DayDto> = emptyList(),
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

/** 화면이 쓰는 결과 묶음. 서버 응답에서 필요한 것만 추린다. */
data class ItineraryResult(
    val title: String,
    val days: List<ItineraryDay>,
    val recovery: RecoveryDto?,
    /** 일자별 회복일 플래그. 서버가 판정한 값을 그대로 들고 있는다. (API 명세 §5-1) */
    val recoveryFlags: List<Boolean>,
)

// ── DTO → 화면 모델 ────────────────────────────────────────────

fun GenerateItineraryResponse.toResult(): ItineraryResult = ItineraryResult(
    title = title,
    days = days.map { it.toDomain() },
    recovery = recovery,
    recoveryFlags = days.map { it.recovery },
)

/**
 * 블록 id 는 서버 응답에 없어서 여기서 만든다.
 *
 * §6.3 이 요구하는 "편집 간 유지되는 안정적 id" 는 **한 응답 안에서** 지켜지면 된다 —
 * 저장 전 로컬 편집만 하고, 저장 요청에는 서버가 자기 id 를 새로 붙인다(API 명세 §5-2).
 */
private fun DayDto.toDomain(): ItineraryDay = ItineraryDay(
    date = LocalDate.parse(date),
    off = dayIndex,
    label = dayLabel,
    dateLabel = LocalDate.parse(date).let { "%02d.%02d".format(it.monthValue, it.dayOfMonth) },
    note = note,
    blocks = blocks.mapIndexed { index, block -> block.toDomain(dayIndex, index) },
)

private fun BlockDto.toDomain(dayIndex: Int, blockIndex: Int): ItineraryBlock {
    val type = if (blockType == "RACE") BlockType.RACE else BlockType.USER
    return ItineraryBlock(
        id = "blk_${dayIndex}_$blockIndex",
        time = startTime,
        title = title,
        catKey = categoryOf(category),
        place = placeName?.let {
            Poi(name = it, lat = lat ?: 0.0, lng = lng ?: 0.0, addr = address.orEmpty())
        },
        desc = description,
        blockType = type,
        // 서버 값을 그대로 믿지 않고 종류로 다시 계산한다 — 둘이 어긋나면 잠금이 풀린다.
        systemManaged = type == BlockType.RACE,
    )
}

/** 서버 Enum(대문자)을 화면 분류로. 모르는 값은 관광지로 떨어뜨린다. (API 명세 부록 C) */
private fun categoryOf(raw: String): BlockCategory =
    BlockCategory.entries.firstOrNull { it.name == raw } ?: BlockCategory.TOUR
