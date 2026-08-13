package com.runninggu.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.runninggu.app.ui.calendar.CalendarScreen
import com.runninggu.app.ui.course.CourseScreen
import com.runninggu.app.ui.home.HomeScreen
import com.runninggu.app.ui.my.MyScreen

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
        composable(Routes.HOME) { HomeScreen() }
        composable(Routes.CALENDAR) { CalendarScreen() }
        composable(Routes.COURSES) { CourseScreen() }
        composable(Routes.MY) { MyScreen() }
    }
}
