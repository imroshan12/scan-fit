package app.scanfit.feature.pdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.NoticeCard
import app.scanfit.core.designsystem.components.NoticeKind
import app.scanfit.core.designsystem.components.SlotVerdict
import app.scanfit.core.designsystem.components.labelRes
import app.scanfit.core.designsystem.components.specSummary
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.imaging.Raster
import app.scanfit.core.imaging.toBitmap
import app.scanfit.core.pdf.PdfError
import app.scanfit.core.pdf.PdfStrategy
import app.scanfit.core.pdf.PdfTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The exam slot (name, document, window) or the tool's intro. */
@Composable
internal fun Intro(state: PdfMakerUiState) {
    val target = state.target
    Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.xs)) {
        if (state.tool == PdfTool.EXAM && target != null) {
            Text(target.examName, style = ScanFitType.headline)
            Text(specSummary(target.spec), style = ScanFitType.figure)
        }
        val intro =
            when (state.tool) {
                PdfTool.IMAGES -> R.string.pdf_intro_images
                PdfTool.COMPRESS -> R.string.pdf_intro_compress
                PdfTool.MERGE -> R.string.pdf_intro_merge
                PdfTool.ID_CARD -> R.string.pdf_intro_id_card
                PdfTool.EXAM -> R.string.pdf_intro_exam
            }
        Text(stringResource(intro), style = ScanFitType.body)
    }
    if (state.notFound) NoticeCard(stringResource(R.string.exam_not_found), NoticeKind.ERROR)
}

/** The files (or the ID card's two sides) with their actions, then the add buttons. */
@Composable
internal fun FilesSection(
    state: PdfMakerUiState,
    actions: PdfActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
        val documents = state.tool == PdfTool.COMPRESS || state.tool == PdfTool.MERGE
        Heading(stringResource(if (documents) R.string.pdf_files_heading else R.string.pdf_pages_heading))
        if (state.tool == PdfTool.ID_CARD) {
            listOf(R.string.pdf_front, R.string.pdf_back).forEachIndexed { side, label ->
                IdCardSide(state, side, stringResource(label), actions)
            }
        } else {
            if (state.items.isEmpty()) {
                Text(stringResource(R.string.pdf_empty), style = ScanFitType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.items.forEachIndexed { index, item -> ItemRow(state, item, index, actions) }
            AddButtons(state, null, actions)
        }
        if (state.adding) CircularProgressIndicator(Modifier.size(ScanFitSpacing.xl))
        state.addFailure?.let { failure ->
            val text =
                when (failure) {
                    AddFailure.UNREADABLE -> R.string.pdf_error_unreadable
                    AddFailure.ENCRYPTED -> R.string.pdf_error_encrypted
                    AddFailure.TOO_MANY_PAGES -> R.string.pdf_error_too_many
                }
            NoticeCard(stringResource(text), NoticeKind.ERROR)
        }
    }
}

@Composable
private fun IdCardSide(
    state: PdfMakerUiState,
    side: Int,
    label: String,
    actions: PdfActions,
) {
    val item = state.items.getOrNull(side)
    Text(label, style = ScanFitType.label)
    if (item != null) {
        ItemRow(state, item, side, actions, label = label)
    } else if (side <= state.items.size) {
        AddButtons(state, side, actions)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddButtons(
    state: PdfMakerUiState,
    side: Int?,
    actions: PdfActions,
) {
    val enabled = !state.busy
    val pictures = state.tool != PdfTool.COMPRESS && state.tool != PdfTool.MERGE
    FlowRow(horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm), verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
        if (pictures) {
            FilledTonalButton(onClick = { actions.onChoosePictures(side) }, enabled = enabled, modifier = touch()) {
                Text(stringResource(R.string.pdf_add_gallery))
            }
        }
        val files =
            when {
                state.tool == PdfTool.COMPRESS && state.items.isNotEmpty() -> R.string.pdf_replace
                state.tool == PdfTool.COMPRESS -> R.string.pdf_choose_pdf
                state.tool == PdfTool.MERGE -> R.string.pdf_add_pdf
                else -> R.string.pdf_add_files
            }
        OutlinedButton(onClick = { actions.onChooseFiles(side) }, enabled = enabled, modifier = touch()) {
            Text(stringResource(files))
        }
        val camera = actions.onTakePhoto
        if (pictures && camera != null) {
            OutlinedButton(onClick = { camera(side) }, enabled = enabled, modifier = touch()) {
                Text(stringResource(R.string.pdf_add_camera))
            }
        }
    }
}

/** A file: thumbnail, what it is, and its actions (reorder, turn, crop, remove). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemRow(
    state: PdfMakerUiState,
    item: PdfItem,
    index: Int,
    actions: PdfActions,
    label: String? = null,
) {
    val enabled = !state.busy
    Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(item.thumbnail)
            Column(Modifier.weight(1f)) {
                val title = label ?: if (item.document) "PDF" else stringResource(R.string.pdf_image_label, index + 1)
                Text(title, style = ScanFitType.body)
                val pages = pluralStringResource(R.plurals.pdf_pages, item.pages, item.pages)
                Text("$pages · ${stringResource(R.string.pdf_result_kb, item.kb)}", style = ScanFitType.figure)
            }
            if (state.tool != PdfTool.ID_CARD && state.tool != PdfTool.COMPRESS) {
                IconButton(onClick = { actions.onMove(item.id, -1) }, enabled = enabled && index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.pdf_move_up))
                }
                IconButton(onClick = { actions.onMove(item.id, 1) }, enabled = enabled && index < state.items.lastIndex) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.pdf_move_down))
                }
            }
            IconButton(onClick = { actions.onRemove(item.id) }, enabled = enabled) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.pdf_remove))
            }
        }
        if (!item.document) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
                TextButton(onClick = { actions.onCrop(item.id) }, enabled = enabled) { Text(stringResource(R.string.pdf_crop)) }
                TextButton(onClick = { actions.onTurn(item.id) }, enabled = enabled) { Text(stringResource(R.string.pdf_rotate)) }
            }
        }
    }
}

@Composable
internal fun Thumbnail(
    raster: Raster?,
    size: Int = THUMBNAIL_DP,
    description: String? = null,
) {
    val bitmap by produceState<ImageBitmap?>(null, raster) {
        value = raster?.let { withContext(Dispatchers.Default) { it.toBitmap().asImageBitmap() } }
    }
    Box(Modifier.sizeIn(minWidth = size.dp, minHeight = size.dp), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = description, modifier = Modifier.size(size.dp)) }
    }
}

/** Clean up (pictures only), Black and white, and Max KB for the tools (ALGORITHMS 6.1). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OptionsSection(
    state: PdfMakerUiState,
    actions: PdfActions,
) {
    val enabled = !state.busy
    Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
        Heading(stringResource(R.string.pdf_options))
        if (state.hasPictures || state.tool == PdfTool.IMAGES || state.tool == PdfTool.ID_CARD) {
            Toggle(stringResource(R.string.pdf_cleanup), stringResource(R.string.pdf_cleanup_hint), state.options.cleanup, enabled, actions.onCleanup)
        }
        Toggle(stringResource(R.string.pdf_grayscale), stringResource(R.string.pdf_grayscale_hint), state.options.grayscale, enabled, actions.onGrayscale)
        if (state.tool != PdfTool.EXAM) {
            OutlinedTextField(
                value = state.maxKbText,
                onValueChange = actions.onMaxKb,
                label = { Text(stringResource(R.string.pdf_max_kb)) },
                supportingText = {
                    val error = state.maxKbText.isNotEmpty() && state.maxKb == null
                    Text(stringResource(if (error) R.string.pdf_max_kb_error else R.string.pdf_max_kb_hint))
                },
                isError = state.maxKbText.isNotEmpty() && state.maxKb == null,
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
                PdfMakerUiState.QUICK_MAX_KB.forEach { kb ->
                    FilterChip(
                        selected = state.maxKb == kb,
                        onClick = { actions.onMaxKb(kb.toString()) },
                        enabled = enabled,
                        label = { Text(stringResource(R.string.pdf_result_kb, kb)) },
                        modifier = Modifier.heightIn(min = ScanFitSpacing.minTouchTarget),
                    )
                }
            }
        }
    }
}

@Composable
private fun Toggle(
    title: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
        Modifier.fillMaxWidth().heightIn(min = ScanFitSpacing.minTouchTarget).semantics(mergeDescendants = true) {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ScanFitType.body)
            Text(hint, style = ScanFitType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** The made PDF (first page, KB, pages, DPI, verdict and notes) or why it could not be made. */
@Composable
internal fun ResultSection(state: PdfMakerUiState) {
    val target = state.target ?: return
    when (val result = state.result) {
        null -> Unit
        is PdfResult.Failed -> NoticeCard(errorText(result.error, target), NoticeKind.ERROR)
        is PdfResult.Ready -> Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
            Heading(stringResource(R.string.pdf_result_heading))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Thumbnail(result.preview, PREVIEW_DP, stringResource(R.string.pdf_preview))
            }
            val facts =
                listOfNotNull(
                    stringResource(R.string.pdf_result_kb, result.kb),
                    pluralStringResource(R.plurals.pdf_pages, result.pages, result.pages),
                    result.dpi?.let { stringResource(R.string.pdf_result_dpi, it) },
                ).joinToString(" · ")
            Text(facts, style = ScanFitType.figure)
            if (target.exam) {
                SlotVerdict(result.meetsRules, target.examName, target.unverified, custom = false)
            } else {
                ToolVerdict(result.meetsRules, target)
            }
            val note =
                when (result.strategy) {
                    PdfStrategy.UNCHANGED -> R.string.pdf_unchanged
                    PdfStrategy.PAD -> R.string.pdf_padded
                    else -> R.string.pdf_text_as_image
                }
            Text(stringResource(note), style = ScanFitType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (state.save.state) {
                SaveState.SAVED -> NoticeCard(stringResource(R.string.export_saved), NoticeKind.INFO)
                SaveState.SAVE_FAILED -> NoticeCard(stringResource(R.string.export_save_failed), NoticeKind.ERROR)
                SaveState.VERIFY_FAILED -> NoticeCard(stringResource(R.string.export_verify_failed), NoticeKind.ERROR)
                else -> Unit
            }
            if (state.shareFailed) NoticeCard(stringResource(R.string.export_share_failed), NoticeKind.ERROR)
        }
    }
}

@Composable
private fun ToolVerdict(
    fits: Boolean,
    target: PdfTarget,
) {
    val max = target.spec.sizeKb.max?.toInt() ?: 0
    if (fits) {
        Text(stringResource(R.string.pdf_fits, max), style = ScanFitType.body, color = ScanFitTheme.colors.success)
    } else {
        NoticeCard(stringResource(R.string.pdf_error_cannot_reach, max), NoticeKind.ERROR)
    }
}

@Composable
private fun errorText(
    error: PdfError,
    target: PdfTarget,
): String = when (error) {
    PdfError.ENCRYPTED -> stringResource(R.string.pdf_error_encrypted)
    PdfError.UNREADABLE, PdfError.NO_PAGES -> stringResource(R.string.pdf_error_unreadable)
    PdfError.TOO_MANY_PAGES -> stringResource(R.string.pdf_error_too_many)
    PdfError.UNKNOWN_LIMIT -> stringResource(R.string.pdf_error_unknown_limit)
    PdfError.CANNOT_REACH -> stringResource(R.string.pdf_error_cannot_reach, target.spec.sizeKb.max?.toInt() ?: 0)
    PdfError.FAILED -> stringResource(R.string.pdf_error_failed)
}

private fun touch() = Modifier.heightIn(min = ScanFitSpacing.minTouchTarget)

private const val THUMBNAIL_DP = 56
private const val PREVIEW_DP = 240
