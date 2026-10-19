package app.scanfit.feature.pdf

import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import app.scanfit.core.designsystem.components.FreeCropEditor
import app.scanfit.core.designsystem.components.ShareEffect
import app.scanfit.core.designsystem.components.labelRes
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.imaging.CropCorner
import app.scanfit.core.pdf.PdfFit
import app.scanfit.core.pdf.PdfProgress
import app.scanfit.core.pdf.PdfTool

/** Everything the PDF maker screen can ask for. */
class PdfActions(
    val onBack: () -> Unit = {},
    /** Gallery pictures; the argument is the ID card side to fill, or `null` to add. */
    val onChoosePictures: (Int?) -> Unit = {},
    /** Files: pictures and/or PDFs, as the tool accepts; the argument is the ID card side. */
    val onChooseFiles: (Int?) -> Unit = {},
    /** `null` when the device has no camera. */
    val onTakePhoto: ((Int?) -> Unit)? = null,
    val onRemove: (Long) -> Unit = {},
    val onMove: (Long, Int) -> Unit = { _, _ -> },
    val onTurn: (Long) -> Unit = {},
    val onCrop: (Long) -> Unit = {},
    val onCropMove: (Double, Double) -> Unit = { _, _ -> },
    val onCropResize: (CropCorner, Double, Double) -> Unit = { _, _, _ -> },
    val onCropReset: () -> Unit = {},
    val onCropDone: () -> Unit = {},
    val onCropCancel: () -> Unit = {},
    val onGrayscale: (Boolean) -> Unit = {},
    val onCleanup: (Boolean) -> Unit = {},
    val onMaxKb: (String) -> Unit = {},
    val onMake: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onDone: () -> Unit = {},
)

/** The PDF maker (ALGORITHMS 6, UI_UX §3): wires the pickers, the camera, the save destination and Back. */
@Composable
fun PdfMakerRoute(
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PdfMakerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    // The ID card side a picker or the camera fills; survives the activity being recreated behind the picker.
    var side by rememberSaveable { mutableStateOf<Int?>(null) }
    val added: (List<String>) -> Unit = { uris -> viewModel.onAdd(uris, side) }
    val pickMany =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PdfFit.MAX_PAGES)) { uris ->
            added(uris.map { it.toString() })
        }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        added(listOfNotNull(uri?.toString()))
    }
    val openMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        added(uris.map { it.toString() })
    }
    val openOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        added(listOfNotNull(uri?.toString()))
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { viewModel.onCaptured(it) }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        viewModel.onSaveDestination(uri?.toString())
    }
    val back = {
        when {
            state.crop != null -> viewModel.onCropCancel()
            state.making != null -> viewModel.onCancelMaking()
            state.save.state == SaveState.SAVING -> Unit
            else -> onBack()
        }
    }
    BackHandler(onBack = back)
    val single = state.tool == PdfTool.COMPRESS || state.tool == PdfTool.ID_CARD
    PdfMakerScreen(
        state,
        PdfActions(
            onBack = back,
            onChoosePictures = { which ->
                side = which
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                if (single) pickOne.launch(request) else pickMany.launch(request)
            },
            onChooseFiles = { which ->
                side = which
                val types = acceptedTypes(state.tool)
                try {
                    if (single) openOne.launch(types) else openMany.launch(types)
                } catch (_: ActivityNotFoundException) {
                    // No document picker on this device: the gallery button still works.
                }
            },
            onTakePhoto =
            if (hasCamera) {
                { which ->
                    viewModel.onCaptureRequested(which)?.let { uri ->
                        try {
                            takePhoto.launch(uri.toUri())
                        } catch (_: ActivityNotFoundException) {
                            viewModel.onCaptured(false)
                        }
                    }
                }
            } else {
                null
            },
            onRemove = viewModel::onRemove,
            onMove = viewModel::onMove,
            onTurn = viewModel::onTurn,
            onCrop = viewModel::onCrop,
            onCropMove = viewModel::onCropMove,
            onCropResize = viewModel::onCropResize,
            onCropReset = viewModel::onCropReset,
            onCropDone = viewModel::onCropDone,
            onCropCancel = viewModel::onCropCancel,
            onGrayscale = viewModel::onGrayscale,
            onCleanup = viewModel::onCleanup,
            onMaxKb = viewModel::onMaxKb,
            onMake = viewModel::onMake,
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
            onDone = onDone,
        ),
        modifier,
    )
    ShareEffect(state.shareUri, viewModel::onShareHandled)
}

/** What the Files picker offers for a tool (ALGORITHMS 6.1). */
private fun acceptedTypes(tool: PdfTool): Array<String> = when (tool) {
    PdfTool.IMAGES, PdfTool.ID_CARD -> arrayOf("image/*")
    PdfTool.COMPRESS, PdfTool.MERGE -> arrayOf("application/pdf")
    PdfTool.EXAM -> arrayOf("image/*", "application/pdf")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfMakerScreen(
    state: PdfMakerUiState,
    actions: PdfActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title(state)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack, enabled = state.save.state != SaveState.SAVING) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
        bottomBar = { BottomBar(state, actions) },
    ) { padding ->
        val crop = state.crop
        if (crop != null) {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(ScanFitSpacing.screenMargin),
                verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
            ) {
                FreeCropEditor(
                    image = crop.image,
                    rect = crop.rect,
                    onMove = actions.onCropMove,
                    onResize = actions.onCropResize,
                    description = stringResource(R.string.ink_crop_hint),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.ink_crop_hint),
                    style = ScanFitType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(ScanFitSpacing.screenMargin),
                verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.lg),
            ) {
                Intro(state)
                if (!state.notFound) {
                    FilesSection(state, actions)
                    OptionsSection(state, actions)
                    ResultSection(state)
                }
            }
        }
    }
}

@Composable
private fun title(state: PdfMakerUiState): String = when (state.tool) {
    PdfTool.IMAGES -> stringResource(R.string.tools_pdf_images)
    PdfTool.COMPRESS -> stringResource(R.string.tools_pdf_compress)
    PdfTool.MERGE -> stringResource(R.string.tools_pdf_merge)
    PdfTool.ID_CARD -> stringResource(R.string.tools_pdf_id_card)
    PdfTool.EXAM -> state.target?.spec?.type?.let { stringResource(it.labelRes()) } ?: stringResource(R.string.tools_pdf)
}

@Composable
internal fun Heading(text: String) {
    Text(text, style = ScanFitType.headline, modifier = Modifier.semantics { heading() })
}

/** Crop: Reset, Cancel, Done. Result: Save (Done after a verified save) and Share. Otherwise Make PDF with progress. */
@Composable
private fun BottomBar(
    state: PdfMakerUiState,
    actions: PdfActions,
) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.padding(ScanFitSpacing.screenMargin), verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
            val making = state.making
            val ready = state.result as? PdfResult.Ready
            when {
                state.crop != null -> {
                    Button(onClick = actions.onCropDone, modifier = fullWidth()) { Text(stringResource(R.string.common_done)) }
                    TextButton(onClick = actions.onCropReset, modifier = fullWidth()) { Text(stringResource(R.string.photo_reset)) }
                }

                making != null -> {
                    Text(progressText(making), style = ScanFitType.body)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                ready != null -> {
                    val saved = state.save.state == SaveState.SAVED
                    val label =
                        when {
                            state.save.state == SaveState.SAVING -> R.string.export_saving
                            saved -> R.string.common_done
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> R.string.flow_save_downloads
                            else -> R.string.flow_save_files
                        }
                    Button(
                        onClick = if (saved) actions.onDone else actions.onSave,
                        enabled = saved || state.canSave,
                        modifier = fullWidth(),
                    ) { Text(stringResource(label)) }
                    TextButton(onClick = actions.onShare, enabled = state.canSave, modifier = fullWidth()) {
                        Text(stringResource(R.string.common_share))
                    }
                }

                !state.notFound -> {
                    missingText(state)?.let {
                        Text(it, style = ScanFitType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = actions.onMake, enabled = state.canMake, modifier = fullWidth()) {
                        Text(stringResource(R.string.pdf_make))
                    }
                }
            }
        }
    }
}

@Composable
private fun progressText(progress: PdfProgress): String = when (progress) {
    is PdfProgress.Preparing -> stringResource(R.string.pdf_preparing)
    is PdfProgress.Fitting -> stringResource(R.string.pdf_making, progress.page + 1, progress.total)
}

@Composable
private fun missingText(state: PdfMakerUiState): String? = when (state.missing) {
    PdfMakerUiState.Missing.TWO_PDFS -> stringResource(R.string.pdf_need_two)
    PdfMakerUiState.Missing.BOTH_SIDES -> stringResource(R.string.pdf_need_both_sides)
    PdfMakerUiState.Missing.MAX_KB -> stringResource(R.string.pdf_max_kb_error)
    else -> null
}

internal fun fullWidth() = Modifier.fillMaxWidth().heightIn(min = ScanFitSpacing.minTouchTarget)
