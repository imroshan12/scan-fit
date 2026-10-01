package app.scanfit

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.feature.home.HomeRoute
import app.scanfit.feature.kit.KitScreen
import app.scanfit.feature.settings.SettingsRoute

/**
 * Top-level tabs: Home / My Kit / Tools / Settings (UI_UX section 2).
 * Routes live here because features never depend on each other.
 */
private enum class Tab(
    val route: String,
    val label: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.tab_home, Icons.Filled.Home),
    KIT("kit", R.string.tab_kit, Icons.Filled.AccountBox),
    TOOLS("tools", R.string.tab_tools, Icons.Filled.Build),
    SETTINGS("settings", R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
fun ScanFitApp() {
    ScanFitTheme {
        val navController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        val destination = backStack?.destination
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = destination?.hierarchy?.any { it.route == tab.route } == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(navController, startDestination = Tab.HOME.route, modifier = Modifier.padding(padding)) {
                composable(Tab.HOME.route) { HomeRoute() }
                composable(Tab.KIT.route) { KitScreen() }
                composable(Tab.TOOLS.route) { ToolsScreen() }
                composable(Tab.SETTINGS.route) { SettingsRoute() }
            }
        }
    }
}
