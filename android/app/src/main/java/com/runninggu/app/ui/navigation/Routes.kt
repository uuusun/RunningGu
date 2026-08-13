package com.runninggu.app.ui.navigation

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
}
