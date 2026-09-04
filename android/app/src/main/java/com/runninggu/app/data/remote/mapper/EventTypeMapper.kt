package com.runninggu.app.data.remote.mapper

import com.runninggu.app.domain.EventType

/**
 * 도메인 종목 → 서버 enum. (API 명세 부록 C)
 *
 * **`enum.name` 을 그대로 보내면 안 된다** — 앱은 `TEN_K`·`FIVE_K` 인데 서버 계약은
 * `K10`·`K5` 다. 대회 목록 필터와 동선 생성이 같은 표기를 써야 해서 한 곳에 둔다
 * (#66 리뷰 — 공용화 요청).
 */
fun EventType.toServerName(): String = when (this) {
    EventType.FULL -> "FULL"
    EventType.HALF -> "HALF"
    EventType.TEN_K -> "K10"
    EventType.FIVE_K -> "K5"
}

/**
 * 서버 enum → 도메인 종목. [toServerName] 의 역방향. (API 명세 부록 C)
 *
 * 저장한 동선을 되살릴 때 쓴다 — `ItineraryRequestSnapshot.event` 가 서버 표기(`K10`)
 * 로 들어오는데 화면은 [EventType] 을 든다(#213).
 *
 * **모르는 값은 null 이다.** 서버가 종목을 늘려도 앱이 죽지 않게 두고, 부르는 쪽이
 * 무엇으로 대신할지 정한다.
 */
fun eventTypeFromServerName(name: String?): EventType? = when (name) {
    "FULL" -> EventType.FULL
    "HALF" -> EventType.HALF
    "K10" -> EventType.TEN_K
    "K5" -> EventType.FIVE_K
    else -> null
}
