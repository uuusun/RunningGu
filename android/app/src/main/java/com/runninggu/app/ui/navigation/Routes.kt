package com.runninggu.app.ui.navigation

import android.net.Uri

/**
 * 내비게이션 route 상수. (SPEC §2.2 내비게이션 그래프)
 *
 * 웹 목업(reference-web)의 화면 키를 그대로 쓴다 — home/calendar/courses/my.
 * 아직 만들지 않은 화면(raceDetail·wizard·result·auth)은 해당 작업에서 추가한다.
 */
object Routes {
    /**
     * auth 그래프(A1~A3). 탭바 없는 별도 그래프다 (SPEC §2.2).
     *
     * 로그인 성공·게스트 둘러보기·가입 완료는 이 그래프를 백스택에서 지우고 [ARG_RETURN_TO]
     * 로 나간다 — 앱 시작이면 `home`, 게스트가 마이에서 들어왔으면 그 화면으로 돌아간다(D-27).
     * 어느 쪽이든 auth 는 스택에서 사라져 뒤로가기가 로그인으로 되돌아가지 않는다.
     *
     * 인자를 그래프에 단 이유는 위저드와 같다 — 자식 화면(A2·A3)도 그래프 entry 에서 읽는다.
     */
    const val ARG_RETURN_TO = "returnTo"
    const val AUTH_GRAPH_PATTERN = "auth?$ARG_RETURN_TO={$ARG_RETURN_TO}"

    fun authGraph(returnTo: String = HOME): String = "auth?$ARG_RETURN_TO=${Uri.encode(returnTo)}"

    /** A1 로그인. (SPEC §4.1) */
    const val LOGIN = "login"

    /** A2 회원가입. (SPEC §4.2) */
    const val SIGNUP = "signup"

    /** A3 비밀번호 찾기. (SPEC §4.3) */
    const val RESET = "reset"

    /** 계정 관리 — 마이 설정에서 여는 별도 화면. (SPEC §4.13 · D-22) */
    const val ACCOUNT = "account"

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
     * S8-D 코스 상세. **조회 종류마다 route 를 나눈다** (matrix D-20).
     *
     * 예전 계획인 `courseDetail/{type}/{id}` 를 폐기한 이유는, 세 종류가 **id 의 의미도
     * 데이터 출처도 다르기** 때문이다. `type` 을 문자열로 받으면 화면이 그걸 다시 갈라야 하고,
     * 잘못된 조합(`near` 인데 id 가 있다)이 컴파일에 안 걸린다.
     *
     * `near` 는 **route 에 id 가 없다.** 목록에서 고른 항목의 snapshot 을 그래프 상태로
     * 넘긴다 — OSM 생성 경로는 서버에 저장돼 있지 않아 다시 조회할 id 자체가 없다.
     */
    const val ARG_SAVED_COURSE_ID = "savedCourseId"
    const val COURSE_DETAIL_SAVED_PATTERN = "courseDetail/saved/{$ARG_SAVED_COURSE_ID}"

    fun courseDetailSaved(savedCourseId: Long): String = "courseDetail/saved/$savedCourseId"

    /**
     * 위저드 그래프(S4~S7). (SPEC §2.2)
     *
     * 그래프 자체에 route를 주는 이유는 ViewModel 스코프 때문이다 — 이 route로
     * back stack entry를 찾아 ViewModel을 만들면 S4~S7이 같은 인스턴스를 공유한다
     * (SPEC §2.4 위저드 공유 상태).
     */
    const val WIZARD_GRAPH_PATTERN = "wizard/{$ARG_RACE_ID}"

    /** S4 일정 선택. (SPEC §4.7) */
    const val PLAN = "plan"

    /** S5 종목·취향. (SPEC §4.8) */
    const val PREFS = "prefs"

    /** S6 숙소 선택. (SPEC §4.9) */
    const val STAY = "stay"

    /** S7 추천 동선 결과. (SPEC §4.10) */
    const val RESULT = "result"

    fun wizard(raceId: String): String = "wizard/${Uri.encode(raceId)}"

    /** 선택 인자를 포함한 캘린더 route 패턴. */
    const val CALENDAR_PATTERN = "$CALENDAR?$ARG_QUERY={$ARG_QUERY}"

    /** 검색어를 실어 캘린더로 이동할 때 쓰는 route. */
    fun calendarWithQuery(query: String): String =
        "$CALENDAR?$ARG_QUERY=${Uri.encode(query)}"

    /** "calendar?q={q}" 같은 패턴에서 인자를 떼어낸 기본 route. */
    fun baseOf(route: String?): String? = route?.substringBefore('?')
}
