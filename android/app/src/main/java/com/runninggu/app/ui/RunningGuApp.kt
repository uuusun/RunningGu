package com.runninggu.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.runninggu.app.ui.theme.Ink5
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.runninggu.app.ui.navigation.RunningGuNavHost
import com.runninggu.app.ui.navigation.TopLevelDestination

/**
 * 앱 셸 — 하단 탭바 + 내비게이션 그래프. (SPEC §2 AP-06)
 */
@Composable
fun RunningGuApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = TopLevelDestination.fromRoute(currentRoute)

    Scaffold(
        modifier = modifier.fillMaxSize(),
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
