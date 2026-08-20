package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.model.Contest
import com.runninggu.app.data.remote.dto.ContestDto
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.KST
import com.runninggu.app.domain.RegistrationStatus
import java.time.Instant
import java.time.LocalDate

/**
 * 서버 DTO → 앱 모델. (API 명세 §3 · SPEC §6.2)
 *
 * 번들(`data/local`)과 서버가 **같은 [Contest] 로 모인다** — 화면과 도메인은 출처를 모른다.
 *
 * 모르는 enum 값은 **버리지 않고 무시**한다. 서버가 종목을 추가해도 나머지 종목은 살아야 하고,
 * 접수 상태는 null 이 되어 `regStatusOf()` 가 날짜로 판정한다.
 */
fun ContestDto.toContest(): Contest = Contest(
    // 화면·내비게이션 키는 문자열, 서버 호출용 canonical id 는 따로 보존한다 (#52 리뷰)
    id = id.toString(),
    serverId = id,
    active = active,
    name = name,
    region = region,
    venue = place,
    date = contestDate,
    startTime = startTime,
    eventTypes = events.mapNotNull(::eventTypeOf),
    regStart = applyStart,
    regEnd = applyEnd,
    regStatusFallback = regStatusOf(regStatus),
    organizer = organizer,
    officialUrl = officialUrl,
    detailUrl = null, // 서버 계약에 없다 — 번들에만 있는 값이다
    imageUrl = imageUrl,
    lat = lat,
    lng = lng,
    category = null, // 서버 계약에 없다 (canonical 내부 값)
    checked = checkedAt?.let(::toKstDate),
    sources = sources,
)

/**
 * 서버 enum(`FULL|HALF|K10|K5`) → 도메인. (부록 C)
 *
 * 도메인 [EventType] 은 한국어 라벨(`풀`·`하프`)을 쓰므로 이름이 다르다.
 * 서버가 값을 추가하면 null 이 되고 그 항목만 빠진다.
 */
private fun eventTypeOf(raw: String): EventType? = when (raw) {
    "FULL" -> EventType.FULL
    "HALF" -> EventType.HALF
    "K10" -> EventType.TEN_K
    "K5" -> EventType.FIVE_K
    else -> null
}

/** 서버 enum(`OPEN|BEFORE|CLOSED|UNKNOWN`) → 도메인. 모르면 null 이라 날짜로 판정한다. */
private fun regStatusOf(raw: String?): RegistrationStatus? =
    RegistrationStatus.entries.firstOrNull { it.name == raw }

/**
 * timestamp(UTC `Z`) → **KST 날짜**. (§0-1)
 *
 * `checkedAt` 은 화면에 "확인 MM.DD" 로 나가므로 비즈니스 날짜다.
 * UTC 로 그냥 자르면 한국 시각 오전 9시 이전에 확인한 것이 전날로 보인다.
 */
/** timestamp(UTC)를 KST 비즈니스 날짜로. 대회·저장 코스가 함께 쓴다(AGENTS 2장-4). */
internal fun toKstDate(instant: Instant): LocalDate = instant.atZone(KST).toLocalDate()
