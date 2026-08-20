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
