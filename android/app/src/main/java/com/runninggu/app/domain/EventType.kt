package com.runninggu.app.domain

/**
 * 표준 종목 4종. (SPEC §5.4)
 *
 * 선언 순서가 곧 표시 순서다 — 풀 > 하프 > 10K > 5K.
 */
enum class EventType(val label: String) {
    FULL("풀"),
    HALF("하프"),
    TEN_K("10K"),
    FIVE_K("5K"),
    ;

    companion object {
        /** 라벨('풀'·'하프'·'10K'·'5K')로 찾는다. 못 찾으면 null. */
        fun fromLabel(label: String?): EventType? = entries.firstOrNull { it.label == label }
    }
}

/**
 * 원천의 다양한 표기를 표준 종목으로 정규화한다. (SPEC §5.4)
 *
 * `풀|full|42 → 풀` · `하프|half|21 → 하프` · `10k → 10K` · 그 외 `5K`.
 */
fun stdEvent(raw: String?): EventType {
    val s = raw.orEmpty().lowercase().replace(" ", "")
    return when {
        s.contains("풀") || s.contains("full") || s.contains("42") -> EventType.FULL
        s.contains("하프") || s.contains("half") || s.contains("21") -> EventType.HALF
        s.contains("10k") || Regex("""(^|[^0-9])10([^0-9]|$)""").containsMatchIn(s) -> EventType.TEN_K
        else -> EventType.FIVE_K
    }
}

/** 여러 표기를 표준 종목 목록으로. 중복을 없애고 풀 > 하프 > 10K > 5K 순으로 돌려준다. */
fun stdEvents(raw: List<String>?): List<EventType> {
    val set = raw.orEmpty().map(::stdEvent).toSet()
    return EventType.entries.filter { it in set }
}

/*
 * 크롤 레코드에서 종목을 뽑는 규칙(`eventsFromRace` · `EventFlags` · `stdEventKm` ·
 * `stdEventToken`)은 **앱에 두지 않는다.** (SPEC §5.4 · 결정-39 · PR #90)
 *
 * 정규화·중복 병합의 주인은 Python 데이터 파이프라인이다. 백엔드도 같은 알고리즘을 Java 로
 * 중복 구현하지 않는데, 앱에까지 두면 같은 규칙이 세 벌이 되어 갈라졌을 때 어느 쪽이 맞는지
 * 알 수 없다 — 실제로 두 벌이던 시절에 앱만 낡아 무주 풀코스가 종목 하나로 줄어 있었다(#61).
 *
 * 앱에 들어오는 대회는 서버 API 든 `assets/races.json` 이든 **이미 표준화된 종목 배열**을
 * 갖고 온다. 그 표기를 [EventType] 으로 바꾸는 [stdEvent]·[stdEvents] 는 남아 있다.
 */
