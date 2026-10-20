package app.scanfit

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
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
import app.scanfit.core.data.kit.KitIds
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.model.DocType
import app.scanfit.core.model.FlowTarget
import app.scanfit.feature.checker.CheckerRoute
import app.scanfit.feature.custom.CustomRoute
import app.scanfit.feature.custom.CustomViewModel
import app.scanfit.feature.exams.DocActions
import app.scanfit.feature.exams.ExamRoute
import app.scanfit.feature.exams.ExamViewModel
import app.scanfit.feature.flowink.InkFlowRoute
import app.scanfit.feature.flowink.InkFlowViewModel
import app.scanfit.feature.flowphoto.PhotoFlowRoute
import app.scanfit.feature.flowphoto.PhotoFlowViewModel
import app.scanfit.feature.home.ExamBrowserRoute
import app.scanfit.feature.home.HomeRoute
import app.scanfit.feature.home.HomeTool
import app.scanfit.feature.kit.KitHistoryPreviewRoute
import app.scanfit.feature.kit.KitHistoryPreviewViewModel
import app.scanfit.feature.kit.KitOriginalRoute
import app.scanfit.feature.kit.KitOriginalViewModel
import app.scanfit.feature.kit.KitRoute
import app.scanfit.feature.pdf.PdfMakerRoute
import app.scanfit.feature.pdf.PdfMakerViewModel
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
    SETTINGS("settings", R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
fun ScanFitApp() {
    ScanFitTheme {
        val navController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        val destination = backStack?.destination
        // My Kit owns its own screens and the flows it opens (a prepared file or "Use for exam…").
        val flowFromKit = destination?.route?.startsWith("$EXAM_ROUTE/") == true &&
            navController.previousBackStackEntry?.destination?.route?.startsWith(Tab.KIT.route) == true
        val inKit = destination?.route?.let {
            it.startsWith("$KIT_ORIGINAL_ROUTE/") || it.startsWith("$KIT_HISTORY_ROUTE/")
        } == true
        // Every tool opens from Home (the Tools tab was folded into it, UI_UX §2).
        val inTool =
            destination?.route?.let {
                it.startsWith(CUSTOM_ROUTE) || it == CHECKER_ROUTE || it.startsWith("$PDF_ROUTE/") ||
                    it == EXAMS_ROUTE
            } == true
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected =
                            destination?.hierarchy?.any { it.route == tab.route } == true ||
                                (tab == Tab.KIT && (flowFromKit || inKit)) ||
                                (tab == Tab.HOME && inTool) ||
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
                        onTool = { tool -> navController.navigate(route(tool)) { launchSingleTop = true } },
                    )
                }
                composable(EXAMS_ROUTE) {
                    ExamBrowserRoute(
                        onBack = { navController.popBackStack() },
                        onOpenExam = { id -> navController.navigate("$EXAM_ROUTE/$id") },
                        onCustomSize = { navController.navigate(CUSTOM_ROUTE) { launchSingleTop = true } },
                        onChecker = { navController.navigate(CHECKER_ROUTE) { launchSingleTop = true } },
                    )
                }
                composable(CHECKER_ROUTE) { CheckerRoute(onBack = { navController.popBackStack() }) }
                composable(
                    "$CUSTOM_ROUTE?${CustomViewModel.DOC}={${CustomViewModel.DOC}}",
                    arguments = listOf(optionalArgument(CustomViewModel.DOC)),
                ) {
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
                            // Every other slot type is a PDF slot (the PDF maker says so when it takes none).
                            canOpen = { true },
                            onOpen = { examId, type ->
                                // Single top: a double tap must not stack two flows (Done would land on the second).
                                val flow =
                                    when (type) {
                                        in PHOTO_FLOW_TYPES -> PHOTO_ROUTE
                                        in INK_FLOW_TYPES -> INK_ROUTE
                                        else -> PDF_ROUTE
                                    }
                                val route = "$EXAM_ROUTE/$examId/$flow/${type.name.lowercase()}"
                                navController.navigate(route) {
                                    launchSingleTop = true
                                }
                            },
                        ),
                    )
                }
                composable(
                    "$EXAM_ROUTE/{${PhotoFlowViewModel.EXAM_ID}}/$PHOTO_ROUTE/{${PhotoFlowViewModel.DOC_TYPE}}" +
                        "?${PhotoFlowViewModel.ORIGINAL}={${PhotoFlowViewModel.ORIGINAL}}",
                    arguments = listOf(optionalArgument(PhotoFlowViewModel.ORIGINAL)),
                ) {
                    PhotoFlowRoute(
                        onBack = { navController.popBackStack() },
                        onDone = { navController.popBackStack() },
                    )
                }
                composable(
                    "$EXAM_ROUTE/{${InkFlowViewModel.EXAM_ID}}/$INK_ROUTE/{${InkFlowViewModel.DOC_TYPE}}" +
                        "?${InkFlowViewModel.ORIGINAL}={${InkFlowViewModel.ORIGINAL}}",
                    arguments = listOf(optionalArgument(InkFlowViewModel.ORIGINAL)),
                ) {
                    InkFlowRoute(
                        onBack = { navController.popBackStack() },
                        onDone = { navController.popBackStack() },
                    )
                }
                composable("$EXAM_ROUTE/{${PdfMakerViewModel.EXAM_ID}}/$PDF_ROUTE/{${PdfMakerViewModel.DOC_TYPE}}") {
                    PdfMakerRoute(onBack = { navController.popBackStack() }, onDone = { navController.popBackStack() })
                }
                composable("$PDF_ROUTE/{${PdfMakerViewModel.TOOL}}") {
                    PdfMakerRoute(onBack = { navController.popBackStack() }, onDone = { navController.popBackStack() })
                }
                composable(Tab.KIT.route) {
                    KitRoute(
                        onOpen = { examId, type ->
                            val flow = if (type in PHOTO_FLOW_TYPES) PHOTO_ROUTE else INK_ROUTE
                            navController.navigate("$EXAM_ROUTE/$examId/$flow/${type.name.lowercase()}") {
                                launchSingleTop = true
                            }
                        },
                        onOpenOriginal = { id ->
                            navController.navigate("$KIT_ORIGINAL_ROUTE/$id") { launchSingleTop = true }
                        },
                        onOpenHistory = { id ->
                            navController.navigate("$KIT_HISTORY_ROUTE/$id") { launchSingleTop = true }
                        },
                    )
                }
                composable("$KIT_HISTORY_ROUTE/{${KitHistoryPreviewViewModel.EXPORT_ID}}") {
                    KitHistoryPreviewRoute(onBack = { navController.popBackStack() })
                }
                composable("$KIT_ORIGINAL_ROUTE/{${KitOriginalViewModel.ORIGINAL_ID}}") { entry ->
                    val id = entry.arguments?.getString(KitOriginalViewModel.ORIGINAL_ID).orEmpty()
                    KitOriginalRoute(
                        onBack = { navController.popBackStack() },
                        // "Use for exam…" (ALGORITHMS 13.2): the slot's flow starts from the original; Done comes back.
                        onUse = { examId, type ->
                            val flow = if (type in PHOTO_FLOW_TYPES) PHOTO_ROUTE else INK_ROUTE
                            val slot = "$EXAM_ROUTE/$examId/$flow/${type.name.lowercase()}"
                            navController.navigate("$slot?${KitIds.ROUTE_ARGUMENT}=$id") { launchSingleTop = true }
                        },
                    )
                }
                composable(Tab.SETTINGS.route) { SettingsRoute() }
            }
        }
    }
}

/** `exam/<id>`: the exam checklist, opened from Home. */
private const val EXAM_ROUTE = "exam"

/** `exams`: Exam forms, the exam catalogue opened from Home's first card (UI_UX §3). */
private const val EXAMS_ROUTE = "exams"

/** Where a Home card goes (UI_UX §3 Home). */
private fun route(tool: HomeTool): String = when (tool) {
    HomeTool.EXAMS -> EXAMS_ROUTE
    HomeTool.PHOTO -> "$CUSTOM_ROUTE?${CustomViewModel.DOC}=photo"
    HomeTool.SIGNATURE -> "$CUSTOM_ROUTE?${CustomViewModel.DOC}=signature"
    HomeTool.THUMB -> "$CUSTOM_ROUTE?${CustomViewModel.DOC}=thumb_impression"
    HomeTool.DOCUMENT -> "$CUSTOM_ROUTE?${CustomViewModel.DOC}=handwritten_declaration"
    HomeTool.COMPRESS_PDF -> "$PDF_ROUTE/compress"
    HomeTool.IMAGES_TO_PDF -> "$PDF_ROUTE/images"
    HomeTool.MERGE_PDFS -> "$PDF_ROUTE/merge"
    HomeTool.ID_CARD -> "$PDF_ROUTE/id_card"
    HomeTool.CHECK -> CHECKER_ROUTE
}

/** `checker`: the pre-submit Checker (ALGORITHMS 12). */
private const val CHECKER_ROUTE = "checker"

/** `custom`: the Custom size form; `custom/<photo|ink>?custom=<text>`: a flow for that size (ALGORITHMS 11). */
private const val CUSTOM_ROUTE = "custom"

private fun customArgument() = optionalArgument(FlowTarget.CUSTOM)

private fun optionalArgument(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = true
}

/** `kit/original/<id>`: one My Kit original, with "Use for exam…" (ALGORITHMS 13.2). */
private const val KIT_ORIGINAL_ROUTE = "kit/original"

/** `kit/history/<id>`: a saved file's preview before it is shared again (ALGORITHMS 13.3 previews). */
private const val KIT_HISTORY_ROUTE = "kit/history"

/** `exam/<id>/photo/<docType>`: the photo flow for one of the exam's photo slots. */
private const val PHOTO_ROUTE = "photo"

/** Document types the photo flow handles (ALGORITHMS 1.2: the kinds that are cropped, not padded). */
private val PHOTO_FLOW_TYPES = setOf(DocType.PHOTO, DocType.POSTCARD_PHOTO)

/** `pdf/<tool>`: a PDF tool; `exam/<id>/pdf/<docType>`: the PDF maker for an exam's PDF slot (ALGORITHMS 6.6). */
private const val PDF_ROUTE = "pdf"

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
