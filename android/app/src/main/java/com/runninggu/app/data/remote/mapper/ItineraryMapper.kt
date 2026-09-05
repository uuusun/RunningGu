package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.model.HotelSnapshot
import com.runninggu.app.data.model.ItineraryRequestSnapshot
import com.runninggu.app.data.model.ItineraryResult
import com.runninggu.app.data.model.RecoveryNote
import com.runninggu.app.data.model.ContestSnapshot
import com.runninggu.app.data.model.SavedItinerary
import com.runninggu.app.data.model.SavedItineraryDetail
import com.runninggu.app.data.remote.dto.BlockDto
import com.runninggu.app.data.remote.dto.DayBlocksDto
import com.runninggu.app.data.remote.dto.ContestSnapshotDto
import com.runninggu.app.data.remote.dto.DayDto
import com.runninggu.app.data.remote.dto.GenerateItineraryResponse
import com.runninggu.app.data.remote.dto.HotelDto
import com.runninggu.app.data.remote.dto.ItineraryDetailDto
import com.runninggu.app.data.remote.dto.ItinerarySummaryDto
import com.runninggu.app.data.remote.dto.RecoveryDto
import com.runninggu.app.data.remote.dto.SaveItineraryRequestDto
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
    // 상세(§5-5)에만 온다. 저장 후 편집 API 가 `/days/{dayId}/...` 라 버리면 안 된다 (#202 리뷰)
    serverId = id,
)

private fun BlockDto.toDomain(dayIndex: Int, blockIndex: Int): ItineraryBlock {
    val type = if (blockType == "RACE") BlockType.RACE else BlockType.USER
    return ItineraryBlock(
        // 저장 동선을 복원한 것이면 서버 id 가 진짜다(§5-5). 만들어 낸 id 로 편집하면
        // 서버가 어느 블록인지 못 찾는다. 생성 응답에는 id 가 없어 그때만 만든다.
        id = id?.toString() ?: "blk_${dayIndex}_$blockIndex",
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

// ── 저장 후 편집 (§5-7 ~ §5-10) ────────────────────────────────

/**
 * 편집 응답의 블록 하나. (§5-8 · §5-10)
 *
 * **생성 응답용 [toDomain] 과 갈라 둔다.** 그쪽은 id 가 없을 때 `blk_0_1` 같은 값을
 * 만들어 주는데, 편집 경로에서 그러면 **다음 요청이 그 가짜 id 로 나간다.** 저장된
 * 동선에서 온 블록은 서버 id 가 반드시 있으므로(§5-5), 없으면 계약이 깨진 것이라
 * 조용히 메우지 않고 올린다 — `MeMapper` 의 모르는 `loginProvider` 와 같은 판단이다.
 */
fun BlockDto.toEditedBlock(): ItineraryBlock {
    val serverId = id ?: throw IllegalArgumentException(
        "저장 후 편집 응답에 블록 id 가 없다 (§5-8 · §5-10)",
    )
    val type = if (blockType == "RACE") BlockType.RACE else BlockType.USER
    return ItineraryBlock(
        id = serverId.toString(),
        time = startTime,
        title = title,
        catKey = categoryOf(category),
        place = placeName?.let {
            Poi(name = it, lat = lat ?: 0.0, lng = lng ?: 0.0, addr = address.orEmpty())
        },
        desc = description,
        blockType = type,
        // 생성 매퍼와 같은 이유로 종류에서 다시 계산한다 — 둘이 어긋나면 잠금이 풀린다
        systemManaged = type == BlockType.RACE,
    )
}

/**
 * 순서 변경 응답. **그 일자의 전체 블록**이 `orderNo` 오름차순으로 온다 (§5-10).
 *
 * 서버가 RACE 를 제자리에 끼워 돌려주므로 앱이 다시 합치지 않는다. 정렬도 다시 하지
 * 않는다 — 서버가 정한 순서가 곧 계약이고, 앱이 또 정렬하면 규칙이 두 곳이 된다.
 */
fun DayBlocksDto.toDomain(): List<ItineraryBlock> = blocks.map { it.toEditedBlock() }

/** 서버 Enum(대문자)을 화면 분류로. 모르는 값은 관광지로 떨어뜨린다. (API 명세 부록 C) */
private fun categoryOf(raw: String): BlockCategory =
    BlockCategory.entries.firstOrNull { it.name == raw } ?: BlockCategory.TOUR

/**
 * 목록 항목 → 카드. (API 명세 §5-4 · SPEC §4.13)
 *
 * **표시 문자열은 여기서 만든다.** 화면이 날짜를 조립하면 같은 규칙이 여러 곳에 흩어지고,
 * KST 해석이 매퍼 밖으로 새는 자리가 된다(AGENTS 2장-4).
 *
 * `id` 는 문자열로 바꾼다 — 화면·내비게이션 키가 문자열이다(#52 리뷰). 삭제는 서버가 준
 * `Long` 이 필요해서 호출부가 다시 파싱한다.
 */
fun ItinerarySummaryDto.toSavedItinerary(): SavedItinerary = SavedItinerary(
    id = id.toString(),
    title = title,
    raceName = contestName,
    // 서버 enum(`HALF`·`K10`)을 그대로 두면 카드에 계약 값이 그대로 보인다(#181 리뷰).
    // 모르는 값은 **버리지 않고 그대로** 둔다 — 빈 칸보다 낯선 글자가 낫고, 서버가 종목을
    // 늘렸다는 사실이 화면에 드러난다.
    event = eventTypeOf(event)?.label ?: event,
    recoveryLabel = recovery?.label,
    period = "%s~%s".format(startDate.toPeriodLabel(), endDate.toPeriodLabel()),
    placeCount = placeCount,
    needsRegeneration = needsRegeneration,
    active = active,
)

/** 카드의 기간 표기 `MM.DD`. (SPEC §4.13) */
private fun LocalDate.toPeriodLabel(): String = "%02d.%02d".format(monthValue, dayOfMonth)

// ── 화면 모델 → DTO ───────────────────────────────────────────

/**
 * 편집을 마친 동선 → `POST /api/itineraries` 요청. (API 명세 §5-2 🔒)
 *
 * **조건을 요청값으로 다시 조립하지 않는다.** 생성 응답이 준 [ItineraryResult.request]
 * snapshot 을 그대로 되돌려 보낸다 — 서버가 정규화했을 수 있어서, 되비추는 쪽이 계약에
 * 맞는다(#66 리뷰). 여기서 다시 만들면 저장본이 생성본과 미묘하게 달라진다.
 *
 * 회복일 플래그는 [ItineraryResult.recoveryFlags] 에 일자와 **같은 순서**로 들어 있다.
 * 편집은 일자 안의 블록만 건드리고 일자를 더하거나 지우거나 섞지 않으므로(SPEC §6.3),
 * 자리로 맞춰도 어긋나지 않는다.
 */
fun ItineraryResult.toSaveRequest(): SaveItineraryRequestDto = SaveItineraryRequestDto(
    title = title,
    event = request.event,
    contestId = request.contestId,
    themes = request.themes,
    startDate = request.startDate,
    endDate = request.endDate,
    hotel = request.hotel?.let { HotelDto(it.name, it.lat, it.lng) },
    recovery = recovery?.let { RecoveryDto(it.label, it.note) },
    days = days.mapIndexed { index, day ->
        day.toDto(recovery = recoveryFlags.getOrElse(index) { false })
    },
)

private fun ItineraryDay.toDto(recovery: Boolean): DayDto = DayDto(
    dayIndex = off,
    date = date.toString(),
    dayLabel = label,
    recovery = recovery,
    note = note,
    blocks = blocks.map { it.toDto() },
)

/**
 * 블록 → DTO.
 *
 * `id` 는 싣지 않는다. 새로 저장하면 서버가 자기 id 를 붙이고(§5-2), 앱이 만든
 * `blk_0_1` 같은 문자열은 서버 계약에 없는 값이다.
 */
private fun ItineraryBlock.toDto(): BlockDto = BlockDto(
    startTime = time,
    title = title,
    category = catKey.name,
    placeName = place?.name,
    address = place?.addr,
    lat = place?.lat,
    lng = place?.lng,
    description = desc,
    blockType = blockType.name,
    systemManaged = systemManaged,
)

/**
 * `GET /api/itineraries/{id}` → 복원 모델. (API 명세 §5-5)
 *
 * snapshot 트리와 최신 [ContestSnapshot] 을 **따로** 담는다. 서버가 RACE 를 최신 canonical
 * 로 덮어쓰지 않으므로 앱도 덮어쓰지 않는다 — 사용자가 저장한 일정이 말없이 바뀌면 안 된다.
 */
fun ItineraryDetailDto.toDetail(): SavedItineraryDetail = SavedItineraryDetail(
    id = id,
    result = ItineraryResult(
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
    ),
    region = region,
    needsRegeneration = needsRegeneration,
    contest = contest.toSnapshot(),
)

private fun ContestSnapshotDto.toSnapshot(): ContestSnapshot = ContestSnapshot(
    name = name,
    region = region,
    place = place,
    contestDate = contestDate,
    startTime = startTime,
    lat = lat,
    lng = lng,
    active = active,
)
