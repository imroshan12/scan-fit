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
import app.scanfit.core.model.DocType
import app.scanfit.feature.exams.DocActions
import app.scanfit.feature.exams.ExamRoute
import app.scanfit.feature.exams.ExamViewModel
import app.scanfit.feature.flowphoto.PhotoFlowRoute
import app.scanfit.feature.flowphoto.PhotoFlowViewModel
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
                            selected =
                            destination?.hierarchy?.any { it.route == tab.route } == true ||
                                (tab == Tab.HOME && destination?.route?.startsWith("$EXAM_ROUTE/") == true),
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
                composable(Tab.HOME.route) {
                    HomeRoute(onOpenExam = { id -> navController.navigate("$EXAM_ROUTE/$id") })
                }
                composable("$EXAM_ROUTE/{${ExamViewModel.EXAM_ID}}") {
                    ExamRoute(
                        onBack = { navController.popBackStack() },
                        docActions =
                        DocActions(
                            canOpen = { it in PHOTO_FLOW_TYPES },
                            onOpen = { examId, type ->
                                // Single top: a double tap must not stack two flows (Done would land on the second).
                                val route = "$EXAM_ROUTE/$examId/$PHOTO_ROUTE/${type.name.lowercase()}"
                                navController.navigate(route) {
                                    launchSingleTop = true
                                }
                            },
                        ),
                    )
                }
                composable(
                    "$EXAM_ROUTE/{${PhotoFlowViewModel.EXAM_ID}}/$PHOTO_ROUTE/{${PhotoFlowViewModel.DOC_TYPE}}",
                ) {
                    PhotoFlowRoute(
                        onBack = { navController.popBackStack() },
                        onDone = { navController.popBackStack() },
                    )
                }
                composable(Tab.KIT.route) { KitScreen() }
                composable(Tab.TOOLS.route) { ToolsScreen() }
                composable(Tab.SETTINGS.route) { SettingsRoute() }
            }
        }
    }
}

/** `exam/<id>`: the exam checklist, opened from Home. */
private const val EXAM_ROUTE = "exam"

/** `exam/<id>/photo/<docType>`: the photo flow for one of the exam's photo slots. */
private const val PHOTO_ROUTE = "photo"

/** Document types the photo flow handles (ALGORITHMS 1.2: the kinds that are cropped, not padded). */
private val PHOTO_FLOW_TYPES = setOf(DocType.PHOTO, DocType.POSTCARD_PHOTO)
