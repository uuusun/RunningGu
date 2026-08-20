package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.model.HotelSnapshot
import com.runninggu.app.data.model.ItineraryRequestSnapshot
import com.runninggu.app.data.model.ItineraryResult
import com.runninggu.app.data.model.RecoveryNote
import com.runninggu.app.data.remote.dto.BlockDto
import com.runninggu.app.data.remote.dto.DayDto
import com.runninggu.app.data.remote.dto.GenerateItineraryResponse
import com.runninggu.app.domain.BlockCategory
import com.runninggu.app.domain.BlockType
import com.runninggu.app.domain.ItineraryBlock
import com.runninggu.app.domain.ItineraryDay
import com.runninggu.app.domain.Poi
import java.time.LocalDate

// ── DTO → 화면 모델 ────────────────────────────────────────────

fun GenerateItineraryResponse.toResult(): ItineraryResult = ItineraryResult(
    title = title,
    days = days.map { it.toDomain() },
    recovery = recovery?.let { RecoveryNote(it.label, it.note) },
    request = ItineraryRequestSnapshot(
        contestId = contestId,
        event = event,
        themes = themes,
        startDate = startDate,
        endDate = endDate,
        hotel = hotel?.let { HotelSnapshot(it.name, it.lat, it.lng) },
    ),
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
