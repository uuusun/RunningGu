package com.runninggu.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.ui.graphics.vector.ImageVector
import com.runninggu.app.R

/**
 * 하단 탭 4개. route 이름은 웹 목업의 화면 키를 그대로 승계한다. (SPEC §2.1·§2.2)
 *
 * 탭바는 여기 있는 최상위 화면에서만 노출한다. 상세·위저드·결과(S3~S7)와
 * 인증(A1~A3)에서는 숨긴다.
 */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME(
        route = Routes.HOME,
        labelRes = R.string.tab_home,
        icon = Icons.Filled.Home,
    ),
    CALENDAR(
        route = Routes.CALENDAR,
        labelRes = R.string.tab_calendar,
        icon = Icons.Filled.DateRange,
    ),
    COURSES(
        route = Routes.COURSES,
        labelRes = R.string.tab_courses,
        icon = Icons.Filled.Place,
    ),
    MY(
        route = Routes.MY,
        labelRes = R.string.tab_my,
        icon = Icons.Filled.Person,
    ),
    ;

    companion object {
        /** 인자가 붙은 route("calendar?q=서울")도 해당 탭으로 인식한다. */
        fun fromRoute(route: String?): TopLevelDestination? {
            val base = Routes.baseOf(route) ?: return null
            return entries.firstOrNull { it.route == base }
        }
    }
}
