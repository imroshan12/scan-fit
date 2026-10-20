package app.scanfit.feature.checker

import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.FreeSavesNote
import app.scanfit.core.designsystem.components.NoticeCard
import app.scanfit.core.designsystem.components.NoticeKind
import app.scanfit.core.designsystem.components.ShareEffect
import app.scanfit.core.designsystem.components.labelRes
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.match.CheckerTarget
import app.scanfit.core.match.DocKind

/** The Checker (ALGORITHMS 12, UI_UX §3): wires the pickers, the save destination and back to the view model. */
@Composable
fun CheckerRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CheckerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val picked: (String?) -> Unit = { uri -> if (uri != null) viewModel.onFileSelected(uri) }
    val pickPhoto =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked(it?.toString()) }
    // Files (Downloads, Drive, a USB stick...) through the system document picker: no storage permission.
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked(it?.toString()) }
    val createDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
            viewModel.onSaveDestination(uri?.toString())
        }
    val back = {
        when {
            state.result != null && state.save.state != SaveState.SAVING -> viewModel.onChangeExam()
            else -> onBack()
        }
    }
    BackHandler(onBack = back)
    CheckerScreen(
        state,
        CheckerActions(
            onBack = back,
            onChoosePhoto = {
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onChooseFile = {
                try {
                    openFile.launch(arrayOf("image/*", "application/pdf"))
                } catch (_: ActivityNotFoundException) {
                    // No document picker on this device: the gallery button still works.
                }
            },
            onKind = viewModel::onKind,
            onQuery = viewModel::onQuery,
            onTarget = viewModel::onTarget,
            onChangeExam = viewModel::onChangeExam,
            onFix = viewModel::onFix,
            onConfirmHandwriting = viewModel::onConfirmHandwriting,
            onSave = {
                viewModel.onSave()?.let { name ->
                    try {
                        createDocument.launch(name)
                    } catch (_: ActivityNotFoundException) {
                        viewModel.onSaveDestination(null, launchFailed = true)
                    }
                }
            },
            onShare = viewModel::onShare,
        ),
        modifier,
    )
    ShareEffect(state.shareUri, viewModel::onShareHandled)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CheckerScreen(
    state: CheckerUiState,
    actions: CheckerActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.checker_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack, enabled = state.save.state != SaveState.SAVING) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
        bottomBar = { FixBar(state, actions) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state.opening) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                Column(
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(ScanFitSpacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.lg),
                ) {
                    val file = state.file
                    if (file == null) {
                        Pick(state.openFailed, actions)
                    } else {
                        FileSection(file, actions)
                        KindChoice(state.kind, actions.onKind)
                        if (state.kind != null) TargetSection(state, actions)
                    }
                }
            }
        }
    }
}

@Composable
internal fun Heading(text: String) {
    Text(text, style = ScanFitType.headline, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Pick(
    openFailed: Boolean,
    actions: CheckerActions,
) {
    Text(stringResource(R.string.checker_intro), style = ScanFitType.body)
    if (openFailed) NoticeCard(stringResource(R.string.checker_open_failed), NoticeKind.ERROR)
    Button(
        onClick = actions.onChoosePhoto,
        modifier = Modifier.fillMaxWidth().heightIn(min = ScanFitSpacing.minTouchTarget),
    ) { Text(stringResource(R.string.photo_choose)) }
    FilledTonalButton(
        onClick = actions.onChooseFile,
        modifier = Modifier.fillMaxWidth().heightIn(min = ScanFitSpacing.minTouchTarget),
    ) { Text(stringResource(R.string.photo_choose_file)) }
}

/** The file's facts and its own problems (ALGORITHMS 5): shown before any exam is chosen. */
@Composable
private fun FileSection(
    file: CheckedFile,
    actions: CheckerActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
        Heading(stringResource(R.string.checker_file_heading))
        val dims = file.inspected.width?.let { w -> file.inspected.height?.let { h -> " · $w×$h" } }.orEmpty()
        Text(stringResource(R.string.checker_facts, formatName(file), file.kb) + dims, style = ScanFitType.figure)
        if (file.inspected.issues.isEmpty()) {
            Text(
                stringResource(R.string.checker_no_issues),
                style = ScanFitType.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            file.inspected.issues.forEach { IssueRow(it, file, null) }
        }
        TextButton(onClick = actions.onChooseFile) { Text(stringResource(R.string.checker_choose_another)) }
    }
}

/** What the file is (ALGORITHMS 12): the user picks, the app never guesses. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindChoice(
    selected: DocKind?,
    onKind: (DocKind) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
        Heading(stringResource(R.string.checker_what))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
            DocKind.entries.forEach { kind ->
                FilterChip(
                    selected = kind == selected,
                    onClick = { onKind(kind) },
                    label = { Text(stringResource(kind.labelRes())) },
                    leadingIcon = if (kind == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    modifier = Modifier.heightIn(min = ScanFitSpacing.minTouchTarget),
                )
            }
        }
    }
}

/** Save and Share for a fitted copy; Done after a verified save (Back then returns to the exam list). */
@Composable
private fun FixBar(
    state: CheckerUiState,
    actions: CheckerActions,
) {
    if (state.fix !is FixState.Ready) return
    val saving = state.save.state == SaveState.SAVING
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.padding(ScanFitSpacing.screenMargin)) {
            val label =
                when {
                    saving -> R.string.export_saving
                    state.save.state == SaveState.SAVED -> R.string.common_done
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> R.string.flow_save_downloads
                    else -> R.string.flow_save_files
                }
            Button(
                onClick = if (state.save.state == SaveState.SAVED) actions.onChangeExam else actions.onSave,
                enabled = state.save.state == SaveState.SAVED || state.canSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = ScanFitSpacing.minTouchTarget),
            ) { Text(stringResource(label)) }
            TextButton(
                onClick = actions.onShare,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = ScanFitSpacing.minTouchTarget),
            ) { Text(stringResource(R.string.common_share)) }
            if (state.save.state != SaveState.SAVED) FreeSavesNote()
        }
    }
}
