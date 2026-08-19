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
 * 크롤 스냅샷과 캐시된 응답은 stale 하다 — 수집 시점에 "접수중" 이었어도 지금은 마감일 수 있다.
 * 그래서 날짜로 **단정할 수 있는 것만** 다시 계산하고, 나머지는 [fallback] 에 맡긴다.
 *
 * | 아는 것 | 판정 |
 * |---|---|
 * | 마감일이 지났다 | 마감 |
 * | 시작일이 아직 안 됐다 | 접수전 |
 * | 시작일을 지났다 | 접수중 |
 * | **마감일만 알고 아직 안 지났다** | **판단 불가 → [fallback]** |
 * | 날짜가 없다 | [fallback] |
 *
 * 네 번째 줄이 중요하다. 마감일이 미래라는 것만으로는 접수중이라고 할 수 없다 —
 * 아직 접수 시작 전일 수 있기 때문이다. API 명세 §3-1 의 목록 응답에는 `applyStart` 가
 * 없어서 실제로 이 상황이 생긴다. 이때는 서버가 조회 시점에 파생해 준 값을 그대로 믿는 게 맞다.
 *
 * @param fallback 서버가 내려준 값이나 원본 스냅샷의 상태. 없으면 [RegistrationStatus.UNKNOWN].
 */
fun regStatusOf(
    regStart: LocalDate?,
    regEnd: LocalDate?,
    fallback: RegistrationStatus? = null,
    today: LocalDate = today(),
): RegistrationStatus = when {
    regEnd != null && regEnd.isBefore(today) -> RegistrationStatus.CLOSED
    regStart != null && today.isBefore(regStart) -> RegistrationStatus.BEFORE
    regStart != null -> RegistrationStatus.OPEN
    else -> fallback ?: RegistrationStatus.UNKNOWN
}
