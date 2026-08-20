package com.runninggu.app.ui.navigation

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.runninggu.app.ui.auth.LoginScreen
import com.runninggu.app.ui.auth.ResetScreen
import com.runninggu.app.ui.auth.SignupScreen
import com.runninggu.app.ui.calendar.CalendarScreen
import com.runninggu.app.ui.course.CourseScreen
import com.runninggu.app.ui.home.HomeScreen
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.navigation
import com.runninggu.app.ui.my.AccountScreen
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
 * root 그래프 — auth(A1~A3) + main(S1~S10). (SPEC §2.2)
 *
 * 시작은 auth 그래프다. 세션이 있으면 `home` 으로 바로 가는 스플래시 게이트는
 * 세션 영속(DataStore)이 붙는 AP-14 연동에서 처리한다 — 지금은 세션 저장이 없어
 * 항상 로그인부터 시작한다(게스트 둘러보기로 즉시 통과 가능).
 */
@Composable
fun RunningGuNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.authGraph(),
        modifier = modifier,
    ) {
        authGraph(navController)
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
                // 게스트가 하트를 누르면 로그인으로 보내고, 끝나면 캘린더로 돌아온다 (D-27).
                onLoginRequest = { navController.navigate(Routes.authGraph(Routes.CALENDAR)) },
                modifier = Modifier.statusBarsPadding(),
            )
        }
        composable(Routes.COURSES) { CourseScreen(Modifier.statusBarsPadding()) }

        // S10 마이 — 로그인 필요(결정-4). 게스트는 화면 안에서 로그인 유도만 본다.
        composable(Routes.MY) {
            MyScreen(
                // 로그인 후 홈이 아니라 마이로 돌아온다 (D-27 "원래 화면 복귀").
                onLoginRequest = { navController.navigate(Routes.authGraph(Routes.MY)) },
                onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                onRaceClick = { raceId -> navController.navigate(Routes.raceDetail(raceId)) },
                onBrowseRaces = { navController.navigate(Routes.CALENDAR) },
                onBrowseCourses = { navController.navigate(Routes.COURSES) },
                modifier = Modifier.statusBarsPadding(),
            )
        }

        // 계정 관리 — 마이 설정에서 여는 별도 화면 (SPEC §4.13 · D-22).
        composable(Routes.ACCOUNT) {
            AccountScreen(
                onBack = { navController.popBackStack() },
                // 로그아웃·탈퇴 → 스택을 비우고 로그인으로 (SPEC §4.13 · 결정-4).
                onSignedOut = {
                    navController.navigate(Routes.authGraph()) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                modifier = Modifier.statusBarsPadding(),
            )
        }

        // S3 대회 상세 — 최상위 화면이 아니므로 탭바는 자동으로 숨는다 (SPEC §2.1).
        composable(
            route = Routes.RACE_DETAIL_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_RACE_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            val raceId = entry.arguments?.getString(Routes.ARG_RACE_ID).orEmpty()
            RaceDetailScreen(
                raceId = raceId,
                onBack = { navController.popBackStack() },
                onStartWizard = { id -> navController.navigate(Routes.wizard(id)) },
                // 로그인 후 이 상세로 되돌아온다 (D-27).
                onLoginRequest = { navController.navigate(Routes.authGraph(Routes.raceDetail(raceId))) },
            )
        }

        wizardGraph(navController)
    }
}

/**
 * auth 그래프 A1~A3. (SPEC §2.2 · §4.1~4.3 · AP-08)
 *
 * 로그인 성공·게스트 둘러보기·가입 완료(자동 로그인)는 전부 auth 그래프를 백스택에서
 * 지우고 `returnTo` 로 나간다 — 앱 시작이면 `home`, 마이에서 들어왔으면 마이로
 * 돌아간다(D-27). 어느 쪽이든 뒤로가기가 로그인으로 되돌아가지 않는다(§2.2).
 */
private fun NavGraphBuilder.authGraph(navController: NavHostController) {
    /** 그래프 인자의 복귀 지점. 자식 화면(A2)도 그래프 entry 에서 같은 값을 읽는다. */
    fun returnTarget(): String =
        navController.getBackStackEntry(Routes.AUTH_GRAPH_PATTERN)
            .arguments?.getString(Routes.ARG_RETURN_TO) ?: Routes.HOME

    fun leaveAuthGraph() {
        navController.navigate(returnTarget()) {
            popUpTo(Routes.AUTH_GRAPH_PATTERN) { inclusive = true }
            // 마이에서 들어왔다 돌아갈 때 같은 화면이 두 장 쌓이지 않게 한다.
            launchSingleTop = true
        }
    }

    navigation(
        route = Routes.AUTH_GRAPH_PATTERN,
        startDestination = Routes.LOGIN,
        arguments = listOf(
            navArgument(Routes.ARG_RETURN_TO) {
                type = NavType.StringType
                defaultValue = Routes.HOME
            },
        ),
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoggedIn = ::leaveAuthGraph,
                onBrowseAsGuest = ::leaveAuthGraph,
                onSignup = { navController.navigate(Routes.SIGNUP) },
                onReset = { navController.navigate(Routes.RESET) },
                modifier = Modifier.statusBarsPadding(),
            )
        }
        composable(Routes.SIGNUP) {
            SignupScreen(
                onBack = { navController.popBackStack() },
                // 가입 완료 = 자동 로그인 (명세 §1-5) → 복귀 지점으로.
                onCompleted = ::leaveAuthGraph,
                modifier = Modifier.statusBarsPadding(),
            )
        }
        composable(Routes.RESET) {
            ResetScreen(
                onBack = { navController.popBackStack() },
                modifier = Modifier.statusBarsPadding(),
            )
        }
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
