package app.scanfit

import android.net.Uri
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.model.DocType
import app.scanfit.core.model.FlowTarget
import app.scanfit.feature.checker.CheckerRoute
import app.scanfit.feature.custom.CustomRoute
import app.scanfit.feature.exams.DocActions
import app.scanfit.feature.exams.ExamRoute
import app.scanfit.feature.exams.ExamViewModel
import app.scanfit.feature.flowink.InkFlowRoute
import app.scanfit.feature.flowink.InkFlowViewModel
import app.scanfit.feature.flowphoto.PhotoFlowRoute
import app.scanfit.feature.flowphoto.PhotoFlowViewModel
import app.scanfit.feature.home.HomeRoute
import app.scanfit.feature.kit.KitRoute
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
        val flowFromKit = destination?.route?.startsWith("$EXAM_ROUTE/") == true &&
            navController.previousBackStackEntry?.destination?.route == Tab.KIT.route
        val inCustom = destination?.route?.let { it.startsWith(CUSTOM_ROUTE) || it == CHECKER_ROUTE } == true
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected =
                            destination?.hierarchy?.any { it.route == tab.route } == true ||
                                (tab == Tab.KIT && flowFromKit) ||
                                (tab == Tab.TOOLS && inCustom) ||
                                (
                                    tab == Tab.HOME && !flowFromKit &&
                                        destination?.route?.startsWith("$EXAM_ROUTE/") == true
                                    ),
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
                    HomeRoute(
                        onOpenExam = { id -> navController.navigate("$EXAM_ROUTE/$id") },
                        onCustomSize = { navController.navigate(CUSTOM_ROUTE) { launchSingleTop = true } },
                        onChecker = { navController.navigate(CHECKER_ROUTE) { launchSingleTop = true } },
                    )
                }
                composable(CHECKER_ROUTE) { CheckerRoute(onBack = { navController.popBackStack() }) }
                composable(CUSTOM_ROUTE) {
                    CustomRoute(
                        onBack = { navController.popBackStack() },
                        onContinue = { size ->
                            val flow = if (size.doc in PHOTO_FLOW_TYPES) PHOTO_ROUTE else INK_ROUTE
                            val route = "$CUSTOM_ROUTE/$flow?${FlowTarget.CUSTOM}=${Uri.encode(size.text)}"
                            navController.navigate(route) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable(
                    "$CUSTOM_ROUTE/$PHOTO_ROUTE?${FlowTarget.CUSTOM}={${FlowTarget.CUSTOM}}",
                    arguments = listOf(customArgument()),
                ) {
                    PhotoFlowRoute(onBack = { navController.popBackStack() }, onDone = { navController.popBackStack() })
                }
                composable(
                    "$CUSTOM_ROUTE/$INK_ROUTE?${FlowTarget.CUSTOM}={${FlowTarget.CUSTOM}}",
                    arguments = listOf(customArgument()),
                ) {
                    InkFlowRoute(onBack = { navController.popBackStack() }, onDone = { navController.popBackStack() })
                }
                composable("$EXAM_ROUTE/{${ExamViewModel.EXAM_ID}}") {
                    ExamRoute(
                        onBack = { navController.popBackStack() },
                        docActions =
                        DocActions(
                            canOpen = { it in PHOTO_FLOW_TYPES || it in INK_FLOW_TYPES },
                            onOpen = { examId, type ->
                                // Single top: a double tap must not stack two flows (Done would land on the second).
                                val flow = if (type in PHOTO_FLOW_TYPES) PHOTO_ROUTE else INK_ROUTE
                                val route = "$EXAM_ROUTE/$examId/$flow/${type.name.lowercase()}"
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
                composable(
                    "$EXAM_ROUTE/{${InkFlowViewModel.EXAM_ID}}/$INK_ROUTE/{${InkFlowViewModel.DOC_TYPE}}",
                ) {
                    InkFlowRoute(
                        onBack = { navController.popBackStack() },
                        onDone = { navController.popBackStack() },
                    )
                }
                composable(Tab.KIT.route) {
                    KitRoute(onOpen = { examId, type ->
                        val flow = if (type in PHOTO_FLOW_TYPES) PHOTO_ROUTE else INK_ROUTE
                        navController.navigate("$EXAM_ROUTE/$examId/$flow/${type.name.lowercase()}") {
                            launchSingleTop = true
                        }
                    })
                }
                composable(Tab.TOOLS.route) {
                    ToolsScreen(
                        onCustomSize = { navController.navigate(CUSTOM_ROUTE) { launchSingleTop = true } },
                        onChecker = { navController.navigate(CHECKER_ROUTE) { launchSingleTop = true } },
                    )
                }
                composable(Tab.SETTINGS.route) { SettingsRoute() }
            }
        }
    }
}

/** `exam/<id>`: the exam checklist, opened from Home. */
private const val EXAM_ROUTE = "exam"

/** `checker`: the pre-submit Checker (ALGORITHMS 12). */
private const val CHECKER_ROUTE = "checker"

/** `custom`: the Custom size form; `custom/<photo|ink>?custom=<text>`: a flow for that size (ALGORITHMS 11). */
private const val CUSTOM_ROUTE = "custom"

private fun customArgument() = navArgument(FlowTarget.CUSTOM) {
    type = NavType.StringType
    nullable = true
}

/** `exam/<id>/photo/<docType>`: the photo flow for one of the exam's photo slots. */
private const val PHOTO_ROUTE = "photo"

/** Document types the photo flow handles (ALGORITHMS 1.2: the kinds that are cropped, not padded). */
private val PHOTO_FLOW_TYPES = setOf(DocType.PHOTO, DocType.POSTCARD_PHOTO)

/** `exam/<id>/ink/<docType>`: the ink flow for a signature, thumb, fingers or declaration slot. */
private const val INK_ROUTE = "ink"

/** Document types the ink flow handles (ALGORITHMS 3 / 9.5: cleaned up, padded to aspect). */
private val INK_FLOW_TYPES =
    setOf(
        DocType.SIGNATURE,
        DocType.TRIPLE_SIGNATURE,
        DocType.LEFT_THUMB,
        DocType.THUMB_IMPRESSION,
        DocType.LEFT_HAND_FINGERS_THUMB,
        DocType.RIGHT_HAND_FINGERS_THUMB,
        DocType.HANDWRITTEN_DECLARATION,
    )
