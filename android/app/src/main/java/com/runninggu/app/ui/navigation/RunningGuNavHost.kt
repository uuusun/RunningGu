package com.runninggu.app.ui.navigation

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.runninggu.app.ui.course.CourseDetailScreen
import com.runninggu.app.ui.course.CourseDetailViewModel
import com.runninggu.app.ui.course.CourseScreen
import com.runninggu.app.ui.course.CourseViewModel
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
 * **시작 지점은 셸이 정해서 넘긴다**(`RunningGuApp`). 저장된 세션이 있으면 `home`,
 * 없으면 auth 그래프다(SPEC §2.2). 여기서 세션을 직접 보지 않는 이유는, 세션을 다 읽기
 * 전에 그래프가 만들어지면 로그인 화면이 한 번 번쩍이기 때문이다.
 */
@Composable
fun RunningGuNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = Routes.authGraph(),
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
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
        composable(Routes.COURSES) {
            CourseScreen(
                viewModel = viewModel(factory = CourseViewModel.factory()),
                // 게스트가 코스를 저장하려 하면 로그인으로 보내고, 끝나면 러닝코스로 돌아온다 (D-27)
                onLoginRequest = { navController.navigate(Routes.authGraph(Routes.COURSES)) },
                modifier = Modifier.statusBarsPadding(),
            )
        }

        // S10 마이 — 로그인 필요(결정-4). 게스트는 화면 안에서 로그인 유도만 본다.
        composable(Routes.MY) {
            MyScreen(
                // 로그인 후 홈이 아니라 마이로 돌아온다 (D-27 "원래 화면 복귀").
                onLoginRequest = { navController.navigate(Routes.authGraph(Routes.MY)) },
                onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                onRaceClick = { raceId -> navController.navigate(Routes.raceDetail(raceId)) },
                onCourseClick = { id -> navController.navigate(Routes.courseDetailSaved(id)) },
                onBrowseRaces = { navController.navigate(Routes.CALENDAR) },
                onBrowseCourses = { navController.navigate(Routes.COURSES) },
                modifier = Modifier.statusBarsPadding(),
            )
        }

        // S8-D 저장 코스 상세 — 마이 [러닝코스] 에서만 들어온다 (matrix D-20).
        // near·ran 변형은 각각 AP-12 · P1 이라 route 를 두지 않는다.
        composable(
            route = Routes.COURSE_DETAIL_SAVED_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_SAVED_COURSE_ID) { type = NavType.LongType },
            ),
        ) { entry ->
            val savedCourseId = entry.arguments?.getLong(Routes.ARG_SAVED_COURSE_ID) ?: 0L
            CourseDetailScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel(factory = CourseDetailViewModel.factory(savedCourseId)),
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
            val wizardViewModel: WizardViewModel =
                viewModel(graphEntry, factory = WizardViewModel.factory())

            PlanScreen(
                raceId = graphEntry.arguments?.getString(Routes.ARG_RACE_ID).orEmpty(),
                onBack = { navController.popBackStack() },
                onNext = {
                    // 이 뒤의 상태는 사용자가 고른 것이다 — 복원 판정의 기준이 된다(#192 리뷰).
                    wizardViewModel.onPlanConfirmed()
                    navController.navigate(Routes.PREFS)
                },
                viewModel = wizardViewModel,
            )
        }
        composable(Routes.PREFS) { entry ->
            val graphEntry = remember(entry) {
                navController.getBackStackEntry(Routes.WIZARD_GRAPH_PATTERN)
            }
            val wizardViewModel: WizardViewModel =
                viewModel(graphEntry, factory = WizardViewModel.factory())

            // **프로세스가 죽었다 되살아난 경우다.** (#192 리뷰)
            //
            // 되살아난 것은 대회 하나뿐이고 날짜·종목·취향·숙소는 기본값이다. 그대로 두면
            // S7 이 사용자가 고른 적 없는 조건으로 동선을 만든다 — 조용히 틀린 결과를
            // 주느니 다시 고르게 한다.
            RestartWizardIfUnconfirmed(wizardViewModel, navController)

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
            val wizardViewModel: WizardViewModel =
                viewModel(graphEntry, factory = WizardViewModel.factory())
            val stayViewModel: StayViewModel = viewModel(entry)

            // **프로세스가 죽었다 되살아난 경우다.** (#192 리뷰)
            //
            // 되살아난 것은 대회 하나뿐이고 날짜·종목·취향·숙소는 기본값이다. 그대로 두면
            // S7 이 사용자가 고른 적 없는 조건으로 동선을 만든다 — 조용히 틀린 결과를
            // 주느니 다시 고르게 한다.
            RestartWizardIfUnconfirmed(wizardViewModel, navController)

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
            val wizardViewModel: WizardViewModel =
                viewModel(graphEntry, factory = WizardViewModel.factory())
            // 결과 상태는 이 화면만 쓰므로 그래프에 묶지 않는다.
            val resultViewModel: ResultViewModel = viewModel(entry)

            // **프로세스가 죽었다 되살아난 경우다.** (#192 리뷰)
            //
            // 되살아난 것은 대회 하나뿐이고 날짜·종목·취향·숙소는 기본값이다. 그대로 두면
            // S7 이 사용자가 고른 적 없는 조건으로 동선을 만든다 — 조용히 틀린 결과를
            // 주느니 다시 고르게 한다.
            RestartWizardIfUnconfirmed(wizardViewModel, navController)

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

/**
 * 복원으로 열린 위저드 후반 단계를 S4 로 되돌린다. (#192 리뷰)
 *
 * 프로세스가 죽으면 `WizardViewModel` 도 사라지고, 되살아난 것은 그래프 인자에 담긴
 * **대회 하나뿐**이다. 날짜·종목·취향·숙소는 기본값으로 돌아오는데 시스템은 사용자가 있던
 * S6·S7 로 복원한다 — 그대로 두면 **사용자가 고른 적 없는 조건으로 동선이 만들어진다.**
 *
 * 화면에는 정상으로 보이므로 사용자는 틀린 것을 모른다. 조용히 틀리느니 다시 고르게 한다.
 *
 * > 입력 전체를 저장해 되살리는 쪽이 사용자에게 낫다. 다만 `PoiItem` 까지 담아야 해서
 * > 범위가 커지므로 별도 작업으로 둔다.
 */
@Composable
private fun RestartWizardIfUnconfirmed(
    wizardViewModel: WizardViewModel,
    navController: NavHostController,
) {
    // **첫 합성 시점의 값만 본다.** 이 화면이 열릴 때 확정돼 있었는지가 전부다 —
    // 정상 진입은 S4 의 [다음] 이 `navigate` 보다 먼저 이 값을 세우므로 항상 true 다.
    val confirmedOnEntry = remember { wizardViewModel.uiState.value.planConfirmed }

    LaunchedEffect(Unit) {
        if (!confirmedOnEntry) navController.popBackStack(Routes.PLAN, inclusive = false)
    }
}
