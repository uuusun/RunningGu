package com.runninggu.app.ui.model

import com.runninggu.app.data.model.Contest
import java.time.format.DateTimeFormatter

/**
 * `data/model` 의 대회를 화면 모델로 옮긴다. (API 명세 §3-1 · AP-14)
 *
 * 화면은 [RaceSummary] 만 알고 [Contest] 를 모른다 — 서버에서 왔는지 번들에서 왔는지도
 * 모른다. 그래서 표기로 바꾸는 일(종목 라벨·원천 라벨·시각 포맷)은 전부 여기서 끝낸다.
 *
 * 반대 방향([RaceSummary] → [Contest]) 은 만들지 않는다. 화면이 만든 값을 서버로 되돌려
 * 보내는 경로가 없기 때문이다 — 서버로 가는 건 [Contest.serverId] 뿐이다(#66 리뷰).
 */
fun Contest.toRaceSummary(): RaceSummary = RaceSummary(
    id = id,
    serverId = serverId,
    name = name,
    region = region,
    venue = venue,
    date = date,
    // 6/153 건이 시작시각이 비어 있다. 없는 걸 "00:00" 으로 지어내면 새벽 출발로 보인다
    startTime = startTime?.format(TIME_FORMAT).orEmpty(),
    regStart = regStart,
    regEnd = regEnd,
    // 선언 순서가 곧 표시 순서다 — 서버가 준 배열을 **재정렬하지 않는다** (SPEC §5.4)
    eventTypes = eventTypes.map { it.label },
    source = sources.joinToString(SOURCE_SEPARATOR) { SOURCE_LABELS[it] ?: it },
    checked = checked,
    regStatusFallback = regStatusFallback,
    organizer = organizer,
    officialUrl = officialUrl,
    active = active,
)

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 병합된 대회는 원천이 여럿이다. 번들이 쓰던 표기와 같게 맞춘다(`RaceBundleDto`). */
private const val SOURCE_SEPARATOR = "·"

/**
 * 원천 토큰 → 카드에 쓰는 한국어 표기. (API 명세 §3-1 `sources[]`)
 *
 * 모르는 토큰은 **버리지 않고 그대로 둔다.** 원천이 늘었을 때 출처가 조용히 사라지는 것보다
 * 낯선 값이 보이는 편이 낫다 — 번들 쪽 `sourceTokensOf` 와 같은 판단이다.
 */
private val SOURCE_LABELS = mapOf(
    "MARATHON_GO" to "마라톤GO",
    "MARATHON_ONLINE" to "마라톤온라인",
)
