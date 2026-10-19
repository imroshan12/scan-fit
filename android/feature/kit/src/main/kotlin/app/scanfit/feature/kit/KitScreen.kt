package app.scanfit.feature.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanfit.core.data.kit.HistoryEntry
import app.scanfit.core.data.kit.KitOriginal
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.ConfidenceBadge
import app.scanfit.core.designsystem.components.NoticeCard
import app.scanfit.core.designsystem.components.NoticeKind
import app.scanfit.core.designsystem.components.ShareEffect
import app.scanfit.core.designsystem.components.labelRes
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.imaging.Raster
import app.scanfit.core.imaging.toBitmap
import app.scanfit.core.model.CustomSize
import app.scanfit.core.model.DocType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun KitRoute(
    onOpen: (String, DocType) -> Unit,
    onOpenOriginal: (String) -> Unit,
    modifier: Modifier = Modifier,
    prepared: KitViewModel = hiltViewModel(),
    originals: KitOriginalsViewModel = hiltViewModel(),
    history: KitHistoryViewModel = hiltViewModel(),
) {
    // Survives switching app tabs and process death; a relaunch starts on Prepared.
    var tab by rememberSaveable { mutableStateOf(KitTab.PREPARED) }
    val preparedState by prepared.uiState.collectAsStateWithLifecycle()
    val originalsState by originals.uiState.collectAsStateWithLifecycle()
    val historyState by history.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(prepared, originals, history) {
        prepared.refreshDrafts()
        originals.refresh()
        history.refresh()
        onPauseOrDispose { }
    }
    KitScreen(
        tab,
        preparedState,
        originalsState,
        historyState,
        KitActions(
            onTab = { tab = it },
            onRetryPrepared = prepared::retry,
            onOpen = onOpen,
            onRetryOriginals = originals::refresh,
            onOpenOriginal = onOpenOriginal,
            onRetryHistory = history::refresh,
            onShare = history::onShare,
            onClearHistory = history::onClear,
            thumbnail = originals::thumbnail,
        ),
        modifier,
    )
    ShareEffect((historyState as? KitHistoryUiState.Ready)?.shareUri, history::onShareHandled)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KitScreen(
    tab: KitTab,
    prepared: KitUiState,
    originals: KitOriginalsUiState,
    history: KitHistoryUiState,
    actions: KitActions,
    modifier: Modifier = Modifier,
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_kit)) },
                actions = {
                    if (tab == KitTab.HISTORY && history is KitHistoryUiState.Ready) {
                        TextButton(onClick = { confirmClear = true }) {
                            Text(stringResource(R.string.kit_history_clear))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(horizontal = ScanFitSpacing.screenMargin, vertical = ScanFitSpacing.sm),
            ) {
                KitTab.entries.forEach { option ->
                    SegmentedButton(
                        selected = option == tab,
                        onClick = { actions.onTab(option) },
                        shape = SegmentedButtonDefaults.itemShape(option.ordinal, KitTab.entries.size),
                    ) { Text(stringResource(option.labelRes()), maxLines = 1) }
                }
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    KitTab.PREPARED -> PreparedContent(prepared, actions)
                    KitTab.ORIGINALS -> OriginalsContent(originals, actions)
                    KitTab.HISTORY -> HistoryContent(history, actions)
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.kit_history_clear_title)) },
            text = { Text(stringResource(R.string.kit_history_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    actions.onClearHistory()
                }) { Text(stringResource(R.string.kit_history_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun PreparedContent(
    state: KitUiState,
    actions: KitActions,
) {
    when (state) {
        KitUiState.Loading -> Loading()

        KitUiState.Empty -> Empty(R.string.kit_empty_title, R.string.kit_empty_body)

        KitUiState.Error -> Failed(R.string.kit_load_failed, actions.onRetryPrepared)

        is KitUiState.Ready -> LazyColumn(contentPadding = PaddingValues(ScanFitSpacing.screenMargin)) {
            for (exam in state.exams) {
                item(key = exam.id) {
                    Column(
                        modifier = Modifier.padding(vertical = ScanFitSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
                    ) {
                        Text(exam.name, style = ScanFitType.title, modifier = Modifier.semantics { heading() })
                        ConfidenceBadge(exam.confidence)
                    }
                }
                for (document in exam.documents) {
                    item(key = "${exam.id}/${document.type}") {
                        KitRow(
                            title = stringResource(document.type.labelRes()),
                            caption = stringResource(R.string.draft_ready_size, document.roundedKb),
                            onClick = { actions.onOpen(exam.id, document.type) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun OriginalsContent(
    state: KitOriginalsUiState,
    actions: KitActions,
) {
    when (state) {
        KitOriginalsUiState.Loading -> Loading()

        KitOriginalsUiState.Empty -> Empty(R.string.kit_originals_empty_title, R.string.kit_originals_empty_body)

        KitOriginalsUiState.Error -> Failed(R.string.kit_originals_load_failed, actions.onRetryOriginals)

        is KitOriginalsUiState.Ready -> LazyColumn(contentPadding = PaddingValues(ScanFitSpacing.screenMargin)) {
            for (group in state.groups) {
                item(key = group.kind) {
                    Text(
                        stringResource(group.kind.labelRes()),
                        style = ScanFitType.title,
                        modifier = Modifier.padding(vertical = ScanFitSpacing.md).semantics { heading() },
                    )
                }
                for (original in group.originals) {
                    item(key = original.id) {
                        OriginalRow(original, actions)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun OriginalRow(
    original: KitOriginal,
    actions: KitActions,
) {
    val thumbnail by produceState<ImageBitmap?>(null, original.id) {
        val raster = actions.thumbnail(original.id)
        value = raster?.let { withContext(Dispatchers.Default) { it.toBitmap().asImageBitmap() } }
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .clickable(role = Role.Button) { actions.onOpenOriginal(original.id) }
            .padding(vertical = ScanFitSpacing.sm)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
    ) {
        Box(
            Modifier.size(THUMBNAIL).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            thumbnail?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.xs)) {
            Text(stringResource(R.string.kit_saved_on, formatDay(original.createdAtMillis)), style = ScanFitType.body)
            Text(
                stringResource(R.string.kit_original_size, original.width, original.height),
                style = ScanFitType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
private fun HistoryContent(
    state: KitHistoryUiState,
    actions: KitActions,
) {
    when (state) {
        KitHistoryUiState.Loading -> Loading()

        KitHistoryUiState.Empty -> Empty(R.string.kit_history_empty_title, R.string.kit_history_empty_body)

        KitHistoryUiState.Error -> Failed(R.string.kit_history_load_failed, actions.onRetryHistory)

        is KitHistoryUiState.Ready -> LazyColumn(contentPadding = PaddingValues(ScanFitSpacing.screenMargin)) {
            if (state.shareFailed) {
                item(key = "share_failed") {
                    NoticeCard(stringResource(R.string.export_share_failed), NoticeKind.ERROR)
                }
            }
            for (entry in state.entries) {
                item(key = entry.record.id) {
                    HistoryRow(entry, actions)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    actions: KitActions,
) {
    val record = entry.record
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = ScanFitSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
    ) {
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.xs),
        ) {
            Text(stringResource(record.docType.labelRes()), style = ScanFitType.body)
            val exam = if (record.examId == CustomSize.EXAM_ID) {
                stringResource(R.string.kit_history_custom)
            } else {
                record.examName
            }
            Text(exam, style = ScanFitType.caption)
            Text(
                record.pages.let { pages ->
                    val kb = (record.size + KB / 2) / KB
                    if (pages == null) {
                        stringResource(R.string.photo_result, kb, record.width, record.height)
                    } else {
                        // A PDF (ALGORITHMS 13.3): `182 KB · 3 pages · PDF`.
                        val pageCount = pluralStringResource(R.plurals.pdf_pages, pages, pages)
                        stringResource(R.string.pdf_history_row, kb, pageCount)
                    }
                } + " · " + formatDay(record.createdAtMillis),
                style = ScanFitType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!entry.shareable) {
                Text(
                    stringResource(R.string.kit_history_expired),
                    style = ScanFitType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (entry.shareable) {
            IconButton(onClick = { actions.onShare(record.id) }) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.common_share))
            }
        }
    }
}

private val THUMBNAIL = 56.dp
private const val KB = 1024
