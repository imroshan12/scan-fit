package app.scanfit.feature.flowphoto

import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.labelRes
import app.scanfit.core.designsystem.components.specSummary
import app.scanfit.core.designsystem.theme.ScanFitRadius
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType

/** The photo flow's entry point: wires the pickers and system back to the Hilt view model. */
@Composable
fun PhotoFlowRoute(
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PhotoFlowViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    val pickPhoto =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) viewModel.onImageSelected(uri.toString()) else viewModel.onPickCancelled()
        }
    // Files (Downloads, Drive, a USB stick...) through the system document picker: no storage permission either.
    val openFile =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.onImageSelected(uri.toString()) else viewModel.onPickCancelled()
        }
    val takePhoto =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            val uri = pendingCapture
            pendingCapture = null
            if (saved && uri != null) viewModel.onImageSelected(uri) else viewModel.onPickCancelled()
        }
    val createDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
            viewModel.onSaveDestination(uri?.toString())
        }
    val back = { if (!viewModel.onBack()) onBack() }
    BackHandler(onBack = back)
    val takePhotoAction: (() -> Unit)? =
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
        }
    PhotoFlowScreen(
        state = state,
        actions =
        PhotoActions(
            onBack = back,
            onTakePhoto = takePhotoAction,
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
            onZoom = viewModel::onZoom,
            onRotate = viewModel::onRotate,
            onResetCrop = viewModel::onResetCrop,
            onCropDone = viewModel::onCropDone,
            onWhiteBackground = viewModel::onWhiteBackground,
            onNameDate = viewModel::onNameDate,
            onName = viewModel::onName,
            onDate = viewModel::onDate,
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
internal class PhotoActions(
    val onBack: () -> Unit = {},
    val onTakePhoto: (() -> Unit)? = {},
    val onChoosePhoto: () -> Unit = {},
    val onChooseFile: () -> Unit = {},
    val onMove: (Double, Double) -> Unit = { _, _ -> },
    val onZoom: (Double) -> Unit = {},
    val onRotate: () -> Unit = {},
    val onResetCrop: () -> Unit = {},
    val onCropDone: () -> Unit = {},
    val onWhiteBackground: (Boolean) -> Unit = {},
    val onNameDate: (Boolean) -> Unit = {},
    val onName: (String) -> Unit = {},
    val onDate: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onDone: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoFlowScreen(
    state: PhotoUiState,
    actions: PhotoActions,
    modifier: Modifier = Modifier,
) {
    val slot =
        when (state) {
            is PhotoUiState.PickSource -> state.slot
            is PhotoUiState.FindingFace -> state.slot
            is PhotoUiState.Crop -> state.slot
            is PhotoUiState.Review -> state.slot
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
                        enabled = (state as? PhotoUiState.Review)?.save?.state != PhotoSaveState.SAVING,
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
                PhotoUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                PhotoUiState.NotFound -> {
                    Text(
                        stringResource(R.string.exam_not_found),
                        style = ScanFitType.body,
                        modifier = Modifier.align(Alignment.Center).padding(ScanFitSpacing.screenMargin),
                    )
                }

                is PhotoUiState.PickSource -> PickSourceContent(state, actions)

                is PhotoUiState.FindingFace -> Busy(stringResource(R.string.photo_finding_face))

                is PhotoUiState.Crop -> CropContent(state, actions)

                is PhotoUiState.Review -> ReviewContent(state, actions)
            }
        }
    }
}

/** One primary action per screen, pinned to the bottom (UI_UX §1). */
@Composable
private fun BottomAction(
    state: PhotoUiState,
    actions: PhotoActions,
) {
    val (label, onClick, enabled) =
        when (state) {
            is PhotoUiState.Crop -> Triple(R.string.common_continue, actions.onCropDone, !state.checking)

            is PhotoUiState.Review -> {
                val ready = state.result as? ReviewResult.Ready
                val label = when (state.save.state) {
                    PhotoSaveState.SAVING -> R.string.export_saving

                    PhotoSaveState.SAVED -> R.string.common_done

                    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        R.string.flow_save_downloads
                    } else {
                        R.string.flow_save_files
                    }
                }
                val click = if (state.save.state == PhotoSaveState.SAVED) actions.onDone else actions.onSave
                Triple(
                    label,
                    click,
                    !state.rendering && ready?.meetsRules == true &&
                        state.save.state != PhotoSaveState.SAVING,
                )
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
            if (state is PhotoUiState.Crop && state.checking) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(label))
            }
        }
    }
}

@Composable
private fun PickSourceContent(
    state: PhotoUiState.PickSource,
    actions: PhotoActions,
) {
    ScrollColumn {
        Text(
            stringResource(R.string.photo_source_heading),
            style = ScanFitType.title,
            modifier = Modifier.semantics { heading() },
        )
        Text(specSummary(state.slot.spec), style = ScanFitType.figure)
        Notice(stringResource(R.string.photo_tips), NoticeKind.INFO)
        state.problem?.let {
            val text =
                when (it) {
                    SourceProblem.OPEN_FAILED -> R.string.photo_open_failed
                    SourceProblem.NO_FACE -> R.string.error_face_none
                    SourceProblem.FAILED -> R.string.error_generic
                }
            Notice(stringResource(text), NoticeKind.ERROR)
        }
        val buttonModifier = Modifier.fillMaxWidth().heightIn(min = ScanFitSpacing.minTouchTarget)
        actions.onTakePhoto?.let { take ->
            Button(onClick = take, modifier = buttonModifier) { Text(stringResource(R.string.photo_take)) }
        }
        val chooseLabel =
            stringResource(if (state.problem == null) R.string.photo_choose else R.string.photo_choose_another)
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
private fun Busy(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(ScanFitSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(text, style = ScanFitType.body)
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

internal enum class NoticeKind { INFO, WARNING, ERROR }

/** A message card: text on a tinted background (the text carries the meaning, not the colour). */
@Composable
internal fun Notice(
    text: String,
    kind: NoticeKind,
) {
    val colors = ScanFitTheme.colors
    val (background, foreground) =
        when (kind) {
            NoticeKind.INFO -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
            NoticeKind.WARNING -> colors.warningContainer to colors.onWarningContainer
            NoticeKind.ERROR -> colors.errorContainer to colors.onErrorContainer
        }
    Text(
        text,
        style = ScanFitType.body,
        color = foreground,
        modifier =
        Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(ScanFitRadius.card))
            .padding(ScanFitSpacing.lg),
    )
}
