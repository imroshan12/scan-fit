package app.scanfit.feature.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanfit.core.data.kit.ExportRecord
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.NoticeCard
import app.scanfit.core.designsystem.components.NoticeKind
import app.scanfit.core.designsystem.components.ShareEffect
import app.scanfit.core.designsystem.components.labelRes
import app.scanfit.core.designsystem.theme.ScanFitRadius
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.imaging.Raster
import app.scanfit.core.imaging.toBitmap
import app.scanfit.core.model.CustomSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A saved file, previewed before it is shared again (ALGORITHMS 13.3, UI_UX §3 My Kit). */
@Composable
fun KitHistoryPreviewRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KitHistoryPreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    KitHistoryPreviewScreen(state, onBack, viewModel::onShare, viewModel::page, modifier)
    ShareEffect((state as? KitHistoryPreviewUiState.Ready)?.shareUri, viewModel::onShareHandled)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KitHistoryPreviewScreen(
    state: KitHistoryPreviewUiState,
    onBack: () -> Unit,
    onShare: () -> Unit,
    page: suspend (Int, Int) -> Raster?,
    modifier: Modifier = Modifier,
) {
    val ready = state as? KitHistoryPreviewUiState.Ready
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(ready?.let { stringResource(it.record.docType.labelRes()) }.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    if (ready != null) {
                        IconButton(onClick = onShare) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.common_share))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                KitHistoryPreviewUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                KitHistoryPreviewUiState.Failed ->
                    Box(Modifier.padding(ScanFitSpacing.screenMargin)) {
                        NoticeCard(stringResource(R.string.kit_preview_failed), NoticeKind.ERROR)
                    }

                is KitHistoryPreviewUiState.Ready -> PreviewContent(state, page)
            }
        }
    }
}

@Composable
private fun PreviewContent(
    state: KitHistoryPreviewUiState.Ready,
    page: suspend (Int, Int) -> Raster?,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { (maxWidth - ScanFitSpacing.screenMargin * 2).roundToPx() }
        LazyColumn(
            contentPadding = PaddingValues(ScanFitSpacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
        ) {
            item { Facts(state.record) }
            if (state.shareFailed) {
                item { NoticeCard(stringResource(R.string.export_share_failed), NoticeKind.ERROR) }
            }
            val image = state.image
            if (image != null) {
                item { RasterFrame(image, image.width.toFloat() / image.height, null) }
            } else {
                items(state.pages) { index ->
                    val description = stringResource(R.string.kit_preview_page, index + 1, state.pages)
                    val raster by produceState<Raster?>(null, index, widthPx) { value = page(index, widthPx) }
                    val shown = raster
                    if (shown == null) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(A4_ASPECT).clip(RoundedCornerShape(ScanFitRadius.card))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    } else {
                        RasterFrame(shown, shown.width.toFloat() / shown.height, description)
                    }
                }
            }
        }
    }
}

/** Exam, size and the day it was saved. */
@Composable
private fun Facts(record: ExportRecord) {
    Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.xs)) {
        val exam =
            if (record.examId == CustomSize.EXAM_ID) stringResource(R.string.kit_history_custom) else record.examName
        Text(exam, style = ScanFitType.headline)
        val kb = (record.size + KB / 2) / KB
        val size =
            record.pages?.let {
                stringResource(R.string.pdf_history_row, kb, pluralStringResource(R.plurals.pdf_pages, it, it))
            }
                ?: stringResource(R.string.photo_result, kb, record.width, record.height)
        Text(
            size + " · " + formatDay(record.createdAtMillis),
            style = ScanFitType.figure,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The file or a page on a light card, at its own aspect ratio. */
@Composable
private fun RasterFrame(
    raster: Raster,
    aspect: Float,
    description: String?,
) {
    val bitmap by produceState<ImageBitmap?>(null, raster) {
        value = withContext(Dispatchers.Default) { raster.toBitmap().asImageBitmap() }
    }
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(ScanFitRadius.card))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        bitmap?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

private const val KB = 1024
private const val A4_ASPECT = 0.707f
