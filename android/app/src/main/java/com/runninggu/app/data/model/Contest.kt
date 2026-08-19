package com.runninggu.app.data.model

import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.RegistrationStatus
import java.time.LocalDate
import java.time.LocalTime

/**
 * 대회 하나. (SPEC §6.2 canonical Contest 계약)
 *
 * 서버 응답과 번들 assets 이 **둘 다 이 모델로 들어온다.** 원천마다 JSON 모양이 다르므로
 * DTO 는 따로 두고 매퍼가 여기로 모은다 — 화면과 도메인은 출처를 몰라도 된다.
 *
 * [regStatusFallback] 을 그대로 쓰지 말 것. 표시할 때는 `regStatusOf()` 로 오늘 기준
 * 재계산한다(SPEC §5.5) — 번들과 캐시는 낡기 때문이다.
 */
data class Contest(
    val id: String,
    val name: String,
    val region: String,
    val venue: String,
    val date: LocalDate,
    val startTime: LocalTime?,
    val eventTypes: List<EventType>,
    val regStart: LocalDate?,
    val regEnd: LocalDate?,
    /** 원천이 준 접수 상태. 날짜로 단정할 수 없을 때만 쓴다. */
    val regStatusFallback: RegistrationStatus?,
    val organizer: String?,
    val officialUrl: String?,
    val detailUrl: String?,
    val imageUrl: String?,
    val lat: Double?,
    val lng: Double?,
    val category: String?,
    /** 원본 스냅샷을 확인한 날짜. 카드에 "{출처} · 확인 MM.DD" 로 쓴다(A3). */
    val checked: LocalDate?,
    val sources: List<String>,
) {
    /** 좌표가 없으면 지도·인근 축제·동선 생성을 쓸 수 없다. (§6.2) */
    val hasLocation: Boolean get() = lat != null && lng != null
}
