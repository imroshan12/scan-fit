package app.scanfit.feature.flowink

import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.NoticeCard
import app.scanfit.core.designsystem.components.NoticeKind
import app.scanfit.core.designsystem.components.labelRes
import app.scanfit.core.designsystem.components.specSummary
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.imaging.CropCorner
import app.scanfit.core.match.DocKind

/** The ink flow's entry point: wires the pickers, the save destination and system back to the Hilt view model. */
@Composable
fun InkFlowRoute(
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InkFlowViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    val picked: (String?) -> Unit = { uri ->
        if (uri != null) viewModel.onImageSelected(uri) else viewModel.onPickCancelled()
    }
    val pickPhoto =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked(it?.toString()) }
    // Files (Downloads, Drive, a USB stick...) through the system document picker: no storage permission either.
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked(it?.toString()) }
    val takePhoto =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            val uri = pendingCapture
            pendingCapture = null
            picked(uri.takeIf { saved })
        }
    val createDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
            viewModel.onSaveDestination(uri?.toString())
        }
    val back = { if (!viewModel.onBack()) onBack() }
    BackHandler(onBack = back)
    InkFlowScreen(
        state = state,
        actions =
        InkActions(
            onBack = back,
            onTakePhoto =
            if (hasCamera) {
                {
                    val uri = viewModel.newCaptureUri()
                    pendingCapture = uri
                    try {
                        takePhoto.launch(uri.toUri())
                    } catch (_: ActivityNotFoundException) {
                        pendingCapture = null
                        viewModel.onPickCancelled()
                    }
                }
            } else {
                null
            },
            onChoosePhoto = {
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onChooseFile = {
                try {
                    openFile.launch(arrayOf("image/*"))
                } catch (_: ActivityNotFoundException) {
                    viewModel.onPickCancelled()
                }
            },
            onMove = viewModel::onMove,
            onResize = viewModel::onResize,
            onRotate = viewModel::onRotate,
            onResetCrop = viewModel::onResetCrop,
            onCropDone = viewModel::onCropDone,
            onCrispBlack = viewModel::onCrispBlack,
            onInkFactor = viewModel::onInkFactor,
            onConfirmHandwriting = viewModel::onConfirmHandwriting,
            onSave = {
                viewModel.onSave()?.let { fileName ->
                    try {
                        createDocument.launch(fileName)
                    } catch (_: ActivityNotFoundException) {
                        viewModel.onSaveDestination(null, launchFailed = true)
                    }
                }
            },
            onDone = onDone,
        ),
        modifier = modifier,
    )
}

/** Everything the screen can ask for; [onTakePhoto] is null on a device without a camera. */
internal class InkActions(
    val onBack: () -> Unit = {},
    val onTakePhoto: (() -> Unit)? = {},
    val onChoosePhoto: () -> Unit = {},
    val onChooseFile: () -> Unit = {},
    val onMove: (Double, Double) -> Unit = { _, _ -> },
    val onResize: (CropCorner, Double, Double) -> Unit = { _, _, _ -> },
    val onRotate: () -> Unit = {},
    val onResetCrop: () -> Unit = {},
    val onCropDone: () -> Unit = {},
    val onCrispBlack: (Boolean) -> Unit = {},
    val onInkFactor: (Double) -> Unit = {},
    val onConfirmHandwriting: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onDone: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InkFlowScreen(
    state: InkUiState,
    actions: InkActions,
    modifier: Modifier = Modifier,
) {
    val slot =
        when (state) {
            is InkUiState.PickSource -> state.slot
            is InkUiState.Opening -> state.slot
            is InkUiState.Crop -> state.slot
            is InkUiState.Review -> state.slot
            else -> null
        }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(slot?.let { stringResource(it.spec.type.labelRes()) }.orEmpty()) },
                navigationIcon = {
                    IconButton(
                        onClick = actions.onBack,
                        enabled = (state as? InkUiState.Review)?.save?.state != SaveState.SAVING,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
        bottomBar = { BottomAction(state, actions) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                InkUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                InkUiState.NotFound -> {
                    Text(
                        stringResource(R.string.exam_not_found),
                        style = ScanFitType.body,
                        modifier = Modifier.align(Alignment.Center).padding(ScanFitSpacing.screenMargin),
                    )
                }

                is InkUiState.PickSource -> PickSourceContent(state, actions)

                is InkUiState.Opening -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is InkUiState.Crop -> InkCropContent(state, actions)

                is InkUiState.Review -> InkReviewContent(state, actions)
            }
        }
    }
}

/** One primary action per screen, pinned to the bottom (UI_UX §1): Continue, Save, then Done once saved. */
@Composable
private fun BottomAction(
    state: InkUiState,
    actions: InkActions,
) {
    val (label, onClick, enabled) =
        when (state) {
            is InkUiState.Crop -> Triple(R.string.common_continue, actions.onCropDone, true)

            is InkUiState.Review -> {
                when (state.save.state) {
                    SaveState.SAVING -> Triple(R.string.export_saving, actions.onSave, false)

                    SaveState.SAVED -> Triple(R.string.common_done, actions.onDone, true)

                    else -> {
                        val downloads = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        val save = if (downloads) R.string.flow_save_downloads else R.string.flow_save_files
                        Triple(save, actions.onSave, state.canSave)
                    }
                }
            }

            else -> return
        }
    Surface(tonalElevation = 2.dp) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(ScanFitSpacing.screenMargin)
                .heightIn(min = ScanFitSpacing.minTouchTarget),
        ) {
            Text(stringResource(label))
        }
    }
}

@Composable
private fun PickSourceContent(
    state: InkUiState.PickSource,
    actions: InkActions,
) {
    ScrollColumn {
        Text(
            stringResource(R.string.ink_source_heading),
            style = ScanFitType.title,
            modifier = Modifier.semantics { heading() },
        )
        Text(specSummary(state.slot.spec), style = ScanFitType.figure)
        if (state.slot.kind == DocKind.DECLARATION) {
            // The text to copy by hand comes from the preset; without it, the user copies it from the notice (§3).
            Text(stringResource(R.string.ink_declaration_heading), style = ScanFitType.headline)
            val text = state.slot.spec.declarationText ?: stringResource(R.string.flow_declaration_copy_from_notice)
            NoticeCard(text, NoticeKind.INFO)
        }
        NoticeCard(stringResource(R.string.flow_tip_paper), NoticeKind.INFO)
        if (state.openFailed) NoticeCard(stringResource(R.string.photo_open_failed), NoticeKind.ERROR)
        val buttonModifier = Modifier.fillMaxWidth().heightIn(min = ScanFitSpacing.minTouchTarget)
        actions.onTakePhoto?.let { take ->
            Button(onClick = take, modifier = buttonModifier) { Text(stringResource(R.string.photo_take)) }
        }
        val chooseLabel = stringResource(if (state.openFailed) R.string.photo_choose_another else R.string.photo_choose)
        if (actions.onTakePhoto == null) {
            Button(onClick = actions.onChoosePhoto, modifier = buttonModifier) { Text(chooseLabel) }
        } else {
            OutlinedButton(onClick = actions.onChoosePhoto, modifier = buttonModifier) { Text(chooseLabel) }
        }
        OutlinedButton(onClick = actions.onChooseFile, modifier = buttonModifier) {
            Text(stringResource(R.string.photo_choose_file))
        }
    }
}

@Composable
internal fun ScrollColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ScanFitSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.lg),
        content = content,
    )
}
