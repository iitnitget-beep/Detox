package com.deeproot.app.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.deeproot.app.notifications.AppIntents
import com.deeproot.app.ui.focus.FocusScreen
import com.deeproot.app.ui.onboarding.OnboardingScreen
import com.deeproot.app.ui.permissions.PermissionsScreen
import com.deeproot.app.ui.settings.AllowedAppsScreen
import com.deeproot.app.ui.settings.SettingsScreen
import com.deeproot.app.ui.stats.StatsScreen
import com.deeproot.app.ui.today.TodayScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val TODAY = "today"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val FOCUS = "focus"
    const val PERMISSIONS = "permissions"
    const val ALLOWED_APPS = "allowed_apps"
}

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(Routes.TODAY, "Today", Icons.Filled.CheckCircle, Icons.Outlined.CheckCircle),
    TopLevelDestination(Routes.STATS, "Stats", Icons.Filled.BarChart, Icons.Outlined.BarChart),
    TopLevelDestination(Routes.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

/**
 * @param pendingDestination screen requested by a notification tap ([AppIntents]); consumed via [onDestinationHandled].
 */
@Composable
fun AppNavigation(
    startDestination: String,
    pendingDestination: String?,
    onDestinationHandled: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = topLevelDestinations.any { it.route == currentDestination?.route }

    LaunchedEffect(pendingDestination, currentDestination?.route) {
        val target = pendingDestination ?: return@LaunchedEffect
        val route = currentDestination?.route ?: return@LaunchedEffect
        if (route == Routes.ONBOARDING) return@LaunchedEffect // finish onboarding first
        when (target) {
            AppIntents.DESTINATION_FOCUS -> if (route != Routes.FOCUS) navController.navigate(Routes.FOCUS) { launchSingleTop = true }
            AppIntents.DESTINATION_TODAY -> navController.navigateTopLevel(Routes.TODAY)
        }
        onDestinationHandled()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevelDestinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateTopLevel(destination.route) },
                            icon = {
                                Icon(if (selected) destination.selectedIcon else destination.icon, contentDescription = null)
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onFinished = {
                        navController.navigate(Routes.TODAY) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.TODAY) {
                TodayScreen(
                    onOpenFocus = { navController.navigate(Routes.FOCUS) { launchSingleTop = true } },
                    onOpenPermissions = { navController.navigate(Routes.PERMISSIONS) { launchSingleTop = true } },
                )
            }
            composable(Routes.STATS) { StatsScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenAllowedApps = { navController.navigate(Routes.ALLOWED_APPS) { launchSingleTop = true } },
                    onOpenPermissions = { navController.navigate(Routes.PERMISSIONS) { launchSingleTop = true } },
                )
            }
            composable(Routes.FOCUS) {
                FocusScreen(
                    onBack = {
                        if (!navController.popBackStack()) navController.navigateTopLevel(Routes.TODAY)
                    },
                    onOpenPermissions = { navController.navigate(Routes.PERMISSIONS) { launchSingleTop = true } },
                )
            }
            composable(Routes.PERMISSIONS) { PermissionsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.ALLOWED_APPS) { AllowedAppsScreen(onBack = { navController.popBackStack() }) }
        }
    }
}

fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
