package com.runninggu.app.domain

import java.time.LocalDate

/**
 * 대회 접수 상태. (SPEC §5.5 · 부록 C `RegStatus`)
 *
 * 서버 DTO 의 `OPEN|BEFORE|CLOSED|UNKNOWN` 과 이름이 같다. 매핑은 `data/remote` 가 한다.
 */
enum class RegistrationStatus(val label: String) {
    BEFORE("접수전"),
    OPEN("접수중"),
    CLOSED("마감"),
    UNKNOWN("미정"),
}

/**
 * 접수 상태를 **오늘(KST) 기준으로 다시 계산한다.** (SPEC §5.5)
 *
 * 크롤 스냅샷의 상태값은 stale 하다 — 수집 시점에 "접수중" 이었어도 지금은 마감일 수 있다.
 * 그래서 날짜가 하나라도 있으면 항상 다시 계산하고, 날짜가 전혀 없을 때만 [fallback] 을 쓴다.
 *
 * @param fallback 원본 스냅샷의 상태. 날짜 정보가 없을 때만 쓴다. 없으면 [RegistrationStatus.UNKNOWN].
 */
fun regStatusOf(
    regStart: LocalDate?,
    regEnd: LocalDate?,
    fallback: RegistrationStatus? = null,
    today: LocalDate = today(),
): RegistrationStatus = when {
    regEnd != null && regEnd.isBefore(today) -> RegistrationStatus.CLOSED
    regStart != null && today.isBefore(regStart) -> RegistrationStatus.BEFORE
    regStart != null || regEnd != null -> RegistrationStatus.OPEN
    else -> fallback ?: RegistrationStatus.UNKNOWN
}
