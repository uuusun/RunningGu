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
    /**
     * 화면·내비게이션용 키. **서버 호출에 쓰지 않는다.**
     *
     * 번들은 크롤 원천의 `externalId`("roadrun-41543"), 서버는 canonical id 를 문자열로 담는다 —
     * 두 값은 표기 차이가 아니라 **다른 namespace** 다.
     */
    val id: String,
    /**
     * canonical `CONTEST.id`. 번들 항목은 **null** 이다. (API 명세 §3-1 · §7-C)
     *
     * 상세·찜·동선 생성처럼 서버를 부르는 동작은 이 값이 있을 때만 한다. 번들 항목은
     * 서버 데이터로 교체되기 전까지 오프라인 표시용이다 — [id] 를 숫자로 바꿔 보내면
     * `NumberFormatException` 이 난다.
     */
    val serverId: Long? = null,
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
    /**
     * 서비스 중인 대회인가. (결정-46)
     *
     * false 면 화면이 흐리게 그리고 "정보 제공 종료" 를 붙이며, 동선 만들기 CTA 를 막고
     * 인근 축제를 부르지 않는다(§4.6). 번들에는 이 개념이 없어 항상 true 다.
     */
    val active: Boolean = true,
    val sources: List<String>,
) {
    /** 좌표가 없으면 지도·인근 축제·동선 생성을 쓸 수 없다. (§6.2) */
    val hasLocation: Boolean get() = lat != null && lng != null

    /** 서버 상세·찜·동선 생성을 부를 수 있는가. 번들만 있는 대회는 false 다. */
    val isServerBacked: Boolean get() = serverId != null
}
