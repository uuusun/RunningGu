package com.runninggu.app.ui.model

import java.time.LocalDate

/**
 * 화면에서 쓰는 대회 요약. 홈(S1)·캘린더(S2)가 공유한다.
 *
 * TODO(AP-04·AP-14): 도메인/데이터 레이어가 들어오면 그쪽 모델로 대체하고,
 * 여기는 UI 전용 매핑만 남긴다.
 */
data class RaceSummary(
    val id: String,
    val name: String,
    val region: String,
    val venue: String,
    val date: LocalDate,
    val startTime: String,
    val regStart: LocalDate?,
    val regEnd: LocalDate?,
    val eventTypes: List<String>,
    val source: String,
    /** 원본 스냅샷을 확인한 날짜. 카드에 "{source} · 확인 MM.DD"로 표기한다. */
    val checked: LocalDate?,
    /** 크롤 스냅샷의 접수 상태 원본. 날짜 정보가 없을 때만 쓴다. */
    val rawRegStatus: String? = null,
)

/** 접수 상태. (SPEC §5.5) */
enum class RegistrationStatus(val label: String) {
    BEFORE("접수전"),
    OPEN("접수중"),
    CLOSED("마감"),
    UNKNOWN("미정"),
}

/**
 * 접수 상태를 **오늘 기준으로 재계산**한다. (SPEC §5.5)
 *
 * 크롤 스냅샷의 상태값은 stale하므로 날짜가 있으면 항상 다시 계산하고,
 * 날짜 정보가 없을 때만 원본 값을, 그것도 없으면 '미정'을 쓴다.
 */
fun RaceSummary.registrationStatus(today: LocalDate = LocalDate.now()): RegistrationStatus = when {
    regEnd != null && regEnd.isBefore(today) -> RegistrationStatus.CLOSED
    regStart != null && today.isBefore(regStart) -> RegistrationStatus.BEFORE
    regStart != null || regEnd != null -> RegistrationStatus.OPEN
    else -> when (rawRegStatus) {
        "접수중" -> RegistrationStatus.OPEN
        "접수전" -> RegistrationStatus.BEFORE
        "마감" -> RegistrationStatus.CLOSED
        else -> RegistrationStatus.UNKNOWN
    }
}

/** 홈 축제 캐러셀 항목. 출처는 한국관광공사 고정 표기. (NFR-7) */
data class FestivalSummary(
    val id: String,
    val name: String,
    val region: String,
    val period: String,
    val isOngoing: Boolean,
)

/** 필터에 쓰는 종목. (SPEC §4.5 · 결정-12) */
val EVENT_TYPES = listOf("5K", "10K", "하프", "풀")

/** 17개 시도. (SPEC §4.5 지역 필터) */
val REGIONS = listOf(
    "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종",
    "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주",
)
