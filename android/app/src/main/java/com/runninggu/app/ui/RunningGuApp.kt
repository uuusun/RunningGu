package com.runninggu.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.ui.navigation.Routes
import com.runninggu.app.ui.theme.Ink5
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.runninggu.app.ui.navigation.RunningGuNavHost
import com.runninggu.app.ui.navigation.TopLevelDestination

/**
 * 앱 셸 — 하단 탭바 + 내비게이션 그래프. (SPEC §2 AP-06 · AP-14)
 *
 * **저장된 세션을 다 읽기 전에는 아무 화면도 고르지 않는다**(SPEC §2.2). 복원 전에는
 * 로그인한 사용자도 세션이 비어 보여서, 그대로 화면을 고르면 로그인 화면이 한 번 번쩍였다가
 * 홈으로 튄다.
 */
@Composable
fun RunningGuApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val restored by SessionStore.restored.collectAsStateWithLifecycle()
    if (!restored) {
        // 스피너를 두지 않는다 — 로컬 파일 한 번 읽는 시간이라, 떴다 사라지는 게 더 어수선하다
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
        return
    }

    /**
     * 시작 지점은 **복원 직후 한 번만** 정한다. 이후 로그아웃으로 세션이 비어도 여기서
     * 다시 계산하면 앱 전체가 갈아엎어진다 — 로그아웃 뒤 이동은 내비게이션이 처리한다(D-27).
     */
    val startDestination = remember {
        if (SessionStore.isLoggedIn) Routes.HOME else Routes.authGraph()
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = TopLevelDestination.fromRoute(currentRoute)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // 상단 인셋을 셸에서 먹지 않는다 — 홈 히어로가 상태바 뒤까지 깔려야 하기 때문이다
        // (목업 .statusbar.on-dark). 상태바 여백은 각 화면이 statusBarsPadding()으로 처리한다.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // 탭바는 최상위 화면에서만 노출한다 (SPEC §2.1).
            if (currentTab != null) {
                RunningGuBottomBar(
                    currentTab = currentTab,
                    onTabSelected = { navController.navigateToTab(it) },
                )
            }
        },
    ) { innerPadding ->
        RunningGuNavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
private fun RunningGuBottomBar(
    currentTab: TopLevelDestination,
    onTabSelected: (TopLevelDestination) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        TopLevelDestination.entries.forEach { tab ->
            val label = stringResource(tab.labelRes)
            NavigationBarItem(
                selected = tab == currentTab,
                onClick = { onTabSelected(tab) },
                icon = { Icon(imageVector = tab.icon, contentDescription = label) },
                label = { Text(label) },
                // 목업 .tabbar — 활성은 파랑 아이콘+라벨, 비활성은 ink5. 알약 배경은 쓰지 않는다.
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = Ink5,
                    unselectedTextColor = Ink5,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}

/**
 * 탭 전환 — 해당 탭 루트로 이동하고 중간 스택은 초기화한다 (SPEC §2.2).
 * 목업의 RESET_TO 계약을 승계하므로 상태를 저장·복원하지 않는다.
 */
private fun NavHostController.navigateToTab(tab: TopLevelDestination) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = false }
        launchSingleTop = true
        restoreState = false
    }
}
