package com.runninggu.app.ui.navigation

import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.navigation
import com.runninggu.app.ui.my.MyScreen
import com.runninggu.app.ui.racedetail.RaceDetailScreen
import com.runninggu.app.ui.wizard.PlanScreen
import com.runninggu.app.ui.wizard.PrefsScreen
import com.runninggu.app.ui.wizard.ResultScreen
import com.runninggu.app.ui.wizard.ResultViewModel
import com.runninggu.app.ui.wizard.StayScreen
import com.runninggu.app.ui.wizard.StayViewModel
import com.runninggu.app.ui.wizard.WizardViewModel

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
        // 위저드는 아래 wizardGraph()에서 붙인다.
        composable(Routes.HOME) {
            HomeScreen(
                // 검색 실행 → 캘린더로 이동하며 검색어 전달 (SPEC §4.4-1)
                onSearch = { query ->
                    navController.navigate(Routes.calendarWithQuery(query))
                },
                onOpenCalendar = { navController.navigate(Routes.CALENDAR) },
                onOpenCourses = { navController.navigate(Routes.COURSES) },
                onRaceClick = { raceId -> navController.navigate(Routes.raceDetail(raceId)) },
                onStartWizard = { raceId -> navController.navigate(Routes.wizard(raceId)) },
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
                modifier = Modifier.statusBarsPadding(),
            )
        }
        composable(Routes.COURSES) { CourseScreen(Modifier.statusBarsPadding()) }
        composable(Routes.MY) { MyScreen(Modifier.statusBarsPadding()) }

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
                onStartWizard = { raceId -> navController.navigate(Routes.wizard(raceId)) },
            )
        }

        wizardGraph(navController)
    }
}

/**
 * 위저드 그래프 S4~S7. (SPEC §2.2 · §2.4)
 *
 * 그래프 안의 화면들은 **그래프 back stack entry에 묶인 [WizardViewModel] 하나**를 공유한다.
 * 화면마다 `viewModel()`을 부르면 각자 다른 인스턴스가 생겨서, S5에서 뒤로 왔을 때
 * S4에서 고른 일정이 사라진다. 그래서 그래프 entry를 owner로 넘긴다.
 */
private fun NavGraphBuilder.wizardGraph(navController: NavHostController) {
    navigation(
        route = Routes.WIZARD_GRAPH_PATTERN,
        startDestination = Routes.PLAN,
        arguments = listOf(navArgument(Routes.ARG_RACE_ID) { type = NavType.StringType }),
    ) {
        composable(Routes.PLAN) { entry ->
            // 이 entry가 아니라 그래프 entry를 owner로 써야 공유가 된다.
            val graphEntry = remember(entry) {
                navController.getBackStackEntry(Routes.WIZARD_GRAPH_PATTERN)
            }
            val wizardViewModel: WizardViewModel = viewModel(graphEntry)

            PlanScreen(
                raceId = graphEntry.arguments?.getString(Routes.ARG_RACE_ID).orEmpty(),
                onBack = { navController.popBackStack() },
                onNext = { navController.navigate(Routes.PREFS) },
                viewModel = wizardViewModel,
            )
        }
        composable(Routes.PREFS) { entry ->
            val graphEntry = remember(entry) {
                navController.getBackStackEntry(Routes.WIZARD_GRAPH_PATTERN)
            }
            val wizardViewModel: WizardViewModel = viewModel(graphEntry)

            PrefsScreen(
                onBack = { navController.popBackStack() },
                onNext = { navController.navigate(Routes.STAY) },
                viewModel = wizardViewModel,
            )
        }
        composable(Routes.STAY) { entry ->
            val graphEntry = remember(entry) {
                navController.getBackStackEntry(Routes.WIZARD_GRAPH_PATTERN)
            }
            val wizardViewModel: WizardViewModel = viewModel(graphEntry)
            val stayViewModel: StayViewModel = viewModel(entry)

            StayScreen(
                onBack = { navController.popBackStack() },
                onNext = { navController.navigate(Routes.RESULT) },
                wizardViewModel = wizardViewModel,
                viewModel = stayViewModel,
            )
        }
        composable(Routes.RESULT) { entry ->
            val graphEntry = remember(entry) {
                navController.getBackStackEntry(Routes.WIZARD_GRAPH_PATTERN)
            }
            val wizardViewModel: WizardViewModel = viewModel(graphEntry)
            // 결과 상태는 이 화면만 쓰므로 그래프에 묶지 않는다.
            val resultViewModel: ResultViewModel = viewModel(entry)

            ResultScreen(
                onBack = { navController.popBackStack() },
                // 빈 상태의 [조건 바꾸기] — 입력을 유지한 채 위저드로 돌아간다 (SPEC §4.10).
                onChangeConditions = { navController.popBackStack() },
                // TODO(AP-12): S8에 출발지(숙소)와 목표 거리를 실어 넘긴다 (SPEC §4.10 · §4.11).
                onOpenCourses = { navController.navigate(Routes.COURSES) },
                wizardViewModel = wizardViewModel,
                viewModel = resultViewModel,
            )
        }
    }
}
