package com.runninggu.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.runninggu.app.ui.calendar.CalendarScreen
import com.runninggu.app.ui.course.CourseScreen
import com.runninggu.app.ui.home.HomeScreen
import com.runninggu.app.ui.my.MyScreen
import com.runninggu.app.ui.racedetail.RaceDetailScreen

/**
 * main 그래프. 시작 화면은 홈(S1).
 *
 * auth 그래프(A1~A3)와 raceDetail(S3)·wizard(S4~S6)·result(S7)는
 * 각 화면 작업(AP-08·AP-11)에서 이 NavHost에 붙인다.
 */
@Composable
fun RunningGuNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                // 검색 실행 → 캘린더로 이동하며 검색어 전달 (SPEC §4.4-1)
                onSearch = { query ->
                    navController.navigate(Routes.calendarWithQuery(query))
                },
                onOpenCalendar = { navController.navigate(Routes.CALENDAR) },
                onOpenCourses = { navController.navigate(Routes.COURSES) },
                onRaceClick = { raceId -> navController.navigate(Routes.raceDetail(raceId)) },
                // TODO(AP-11): S4 일정 선택이 생기면 연결한다.
                onStartWizard = {},
            )
        }
        composable(
            route = Routes.CALENDAR_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_QUERY) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            CalendarScreen(
                initialQuery = entry.arguments?.getString(Routes.ARG_QUERY).orEmpty(),
                onRaceClick = { raceId -> navController.navigate(Routes.raceDetail(raceId)) },
            )
        }
        composable(Routes.COURSES) { CourseScreen() }
        composable(Routes.MY) { MyScreen() }

        // S3 대회 상세 — 최상위 화면이 아니므로 탭바는 자동으로 숨는다 (SPEC §2.1).
        composable(
            route = Routes.RACE_DETAIL_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_RACE_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            RaceDetailScreen(
                raceId = entry.arguments?.getString(Routes.ARG_RACE_ID).orEmpty(),
                onBack = { navController.popBackStack() },
                // TODO(AP-11): S4 일정 선택이 생기면 연결한다.
                onStartWizard = {},
            )
        }
    }
}
