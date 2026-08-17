package com.runninggu.app.ui.navigation

import android.net.Uri

/**
 * 내비게이션 route 상수. (SPEC §2.2 내비게이션 그래프)
 *
 * 웹 목업(reference-web)의 화면 키를 그대로 쓴다 — home/calendar/courses/my.
 * 아직 만들지 않은 화면(raceDetail·wizard·result·auth)은 해당 작업에서 추가한다.
 */
object Routes {
    const val HOME = "home"
    const val CALENDAR = "calendar"
    const val COURSES = "courses"
    const val MY = "my"

    /** 캘린더 검색어 인자. 홈에서 검색 실행 시 넘어온다. (SPEC §4.4-1) */
    const val ARG_QUERY = "q"

    /** S3 대회 상세. 대회 id를 경로에 실어 넘긴다. (SPEC §2.2 · §4.6) */
    const val ARG_RACE_ID = "raceId"
    const val RACE_DETAIL_PATTERN = "raceDetail/{$ARG_RACE_ID}"

    fun raceDetail(raceId: String): String = "raceDetail/${Uri.encode(raceId)}"

    /**
     * 위저드 그래프(S4~S7). (SPEC §2.2)
     *
     * 그래프 자체에 route를 주는 이유는 ViewModel 스코프 때문이다 — 이 route로
     * back stack entry를 찾아 ViewModel을 만들면 S4~S7이 같은 인스턴스를 공유한다
     * (SPEC §2.4 위저드 공유 상태).
     */
    const val WIZARD_GRAPH_PATTERN = "wizard/{$ARG_RACE_ID}"
    const val PLAN = "plan"

    fun wizard(raceId: String): String = "wizard/${Uri.encode(raceId)}"

    /** 선택 인자를 포함한 캘린더 route 패턴. */
    const val CALENDAR_PATTERN = "$CALENDAR?$ARG_QUERY={$ARG_QUERY}"

    /** 검색어를 실어 캘린더로 이동할 때 쓰는 route. */
    fun calendarWithQuery(query: String): String =
        "$CALENDAR?$ARG_QUERY=${Uri.encode(query)}"

    /** "calendar?q={q}" 같은 패턴에서 인자를 떼어낸 기본 route. */
    fun baseOf(route: String?): String? = route?.substringBefore('?')
}
