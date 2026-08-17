package com.runninggu.app.ui.model

import com.runninggu.app.domain.today
import java.time.LocalDate
import com.runninggu.app.domain.dDay as domainDDay
import com.runninggu.app.domain.dDayLabel as domainDDayLabel

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
    /**
     * 아래 두 필드는 S3 상세에만 나온다. 카드(S1·S2)는 쓰지 않으므로 없을 수 있다.
     * (API 명세 §3-4 — 상세 응답에서만 내려온다)
     */
    val organizer: String? = null,
    val officialUrl: String? = null,
)

/**
 * 대회일까지 남은 일수. 오늘이면 0, 지났으면 음수. (SPEC §4.6 · KST 기준 §6.6)
 *
 * 계산은 `domain` 이 한다 — 기본값이 KST 기준 오늘이라야 해외에서도 어긋나지 않는다.
 */
fun RaceSummary.dDay(today: LocalDate = today()): Int = domainDDay(date, today)

/** "D-18" · "D-day" · "D+2" 표기. (SPEC §4.6) */
fun RaceSummary.dDayLabel(today: LocalDate = today()): String = domainDDayLabel(date, today)

/**
 * 접수 상태. (SPEC §5.5)
 *
 * TODO(AP-04): §5.5 는 도메인 규칙이므로 `domain/` 으로 옮긴다. 화면 8곳이 import 하고 있어
 *  이번 변경 범위 밖으로 뒀다 — 옮길 때 [registrationStatus] 와 함께 통째로 간다.
 */
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
fun RaceSummary.registrationStatus(today: LocalDate = today()): RegistrationStatus = when {
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

/**
 * S3 대회 인근 축제. (SPEC §4.6 M3 · API 명세 §3-5)
 *
 * 서버가 대회일 ±14일 ∧ 반경 40km로 걸러 거리순 6건을 내려준다 — 앱은 거르지 않고 그대로 그린다.
 */
data class NearbyFestival(
    val contentId: String,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    /** 대회장 기준 직선거리(km). 카드에 "대회장 {d.d}km"로 표기한다. */
    val distanceKm: Double,
    val imageUrl: String?,
    val address: String,
)

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
