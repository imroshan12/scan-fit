package app.scanfit.feature.exams

import android.content.res.Configuration
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanfit.core.data.draft.DraftStatus
import app.scanfit.core.data.draft.draftStatus
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.ConfidenceBadge
import app.scanfit.core.designsystem.components.labelRes
import app.scanfit.core.designsystem.components.specSummary
import app.scanfit.core.designsystem.theme.ScanFitRadius
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.DocType
import app.scanfit.core.model.Exam
import app.scanfit.core.model.PresetBundle
import app.scanfit.core.model.SourceKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/** Exam screen entry point. Keeps the Hilt ViewModel out of [ExamScreen] so previews and tests need no DI. */
@Composable
fun ExamRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    docActions: DocActions = DocActions(),
    viewModel: ExamViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshDrafts()
        onPauseOrDispose { }
    }
    ExamScreen(state, onBack, viewModel::onTogglePin, modifier, docActions)
}

/**
 * Which document rows open a flow, and what opening one does. The app decides, because features never depend on each
 * other: a row is tappable only once its flow exists.
 */
class DocActions(
    val canOpen: (DocType) -> Boolean = { false },
    val onOpen: (examId: String, type: DocType) -> Unit = { _, _ -> },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExamScreen(
    state: ExamUiState,
    onBack: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
    docActions: DocActions = DocActions(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (state as? ExamUiState.Ready)?.exam?.name.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    if (state is ExamUiState.Ready) {
                        IconButton(onClick = onTogglePin) {
                            Icon(
                                if (state.pinned) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription =
                                stringResource(if (state.pinned) R.string.exam_unpin else R.string.exam_pin),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                ExamUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                ExamUiState.NotFound -> {
                    Text(
                        stringResource(R.string.exam_not_found),
                        style = ScanFitType.body,
                        modifier = Modifier.align(Alignment.Center).padding(ScanFitSpacing.screenMargin),
                    )
                }

                is ExamUiState.Ready -> ExamContent(state.exam, docActions, state.savedDocuments, state.readyDocuments)
            }
        }
    }
}

@Composable
private fun ExamContent(
    exam: Exam,
    docActions: DocActions,
    savedDocuments: Set<DocType>,
    readyDocuments: Map<DocType, Int>,
) {
    LazyColumn(
        contentPadding = PaddingValues(ScanFitSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
    ) {
        item { Header(exam) }
        if (exam.livePhotoCapture == true) item { LivePhotoRow() }
        item { SectionTitle(R.string.exam_documents) }
        items(exam.documents, key = { it.type.name }) { doc ->
            val open = if (docActions.canOpen(doc.type)) ({ docActions.onOpen(exam.id, doc.type) }) else null
            DocRow(doc, onClick = open, saved = doc.type in savedDocuments, readyKb = readyDocuments[doc.type])
        }
        if (exam.specialRules.isNotEmpty()) item { RulesCard(exam.specialRules) }
    }
}

@Composable
private fun Header(exam: Exam) {
    val uriHandler = LocalUriHandler.current
    val source = exam.sources.firstOrNull { it.kind == SourceKind.OFFICIAL } ?: exam.sources.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
        Text(exam.name, style = ScanFitType.title, modifier = Modifier.semantics { heading() })
        Text(
            listOf(exam.body, stringResource(exam.category.labelRes())).distinct().joinToString(" · "),
            style = ScanFitType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ConfidenceBadge(exam.confidence)
        val locale = LocalConfiguration.current.locales[0]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.exam_verified_on, formatDate(exam.lastVerified, locale)),
                style = ScanFitType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (source != null) {
                TextButton(onClick = { uriHandler.openUri(source.url) }) { Text(stringResource(R.string.exam_source)) }
            }
        }
    }
}

@Composable
private fun LivePhotoRow() {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(ScanFitRadius.card))
            .padding(ScanFitSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Face, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.exam_live_photo_row),
            style = ScanFitType.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        stringResource(text),
        style = ScanFitType.label,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = ScanFitSpacing.sm).semantics { heading() },
    )
}

/** UI_UX §4 `DocRow`. Saved records a verified export, not whether the user has kept the file. */
@Composable
private fun DocRow(
    doc: DocSpec,
    onClick: (() -> Unit)?,
    saved: Boolean,
    readyKb: Int?,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = ScanFitSpacing.minTouchTarget)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.xs),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(doc.type.labelRes()),
                    style = ScanFitType.headline,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!doc.required) {
                    Text(
                        stringResource(R.string.exam_optional),
                        style = ScanFitType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(specSummary(doc), style = ScanFitType.figure, color = MaterialTheme.colorScheme.onSurface)
            Text(
                when (draftStatus(saved, readyKb != null)) {
                    DraftStatus.SAVED -> stringResource(R.string.exam_status_saved)
                    DraftStatus.READY -> stringResource(R.string.draft_ready_size, requireNotNull(readyKb))
                    DraftStatus.NOT_STARTED -> stringResource(R.string.exam_status_not_started)
                },
                style = ScanFitType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(
                Modifier.padding(top = ScanFitSpacing.sm),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RulesCard(rules: List<String>) {
    val colors = ScanFitTheme.colors
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .background(colors.warningContainer, RoundedCornerShape(ScanFitRadius.card))
            .padding(ScanFitSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
    ) {
        Text(
            stringResource(R.string.exam_before_upload),
            style = ScanFitType.label,
            color = colors.onWarningContainer,
            modifier = Modifier.semantics { heading() },
        )
        // Rules are preset data, shown exactly as written (presets are data, CLAUDE.md rule 5).
        rules.forEach { Text("• $it", style = ScanFitType.body, color = colors.onWarningContainer) }
    }
}

/**
 * `2026-09-30` in the app's language ("30 Sep 2026", "30 सित॰ 2026"). The locale comes from the composition, not
 * `Locale.getDefault()`, which need not follow the per-app language. Falls back to the raw text for a bad date.
 */
private fun formatDate(
    iso: String,
    locale: Locale,
): String = try {
    LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
} catch (_: DateTimeParseException) {
    iso
}

private val previewExam: Exam by lazy {
    PresetBundle.decodeExam(
        """
        {"id":"ibps_po","name":"IBPS PO / MT","body":"IBPS","category":"banking","live_photo_capture":true,
         "status":"active",
         "documents":[{"type":"photo","required":true,"formats":["jpg"],"size_kb":{"min":20,"max":50,"target":38},
           "dimensions":{"mode":"preferred","width":200,"height":230}},
          {"type":"signature","required":true,"formats":["jpg"],"size_kb":{"min":10,"max":20,"target":16},
           "dimensions":{"mode":"preferred","width":140,"height":60}}],
         "special_rules":["Signature in running handwriting, not CAPITAL letters"],
         "sources":[{"url":"https://example.org/notice","kind":"official"}],"confidence":"high","confidence_note":"",
         "last_verified":"2026-09-30"}
        """.trimIndent(),
    )
}

@Preview(name = "Ready", showBackground = true)
@Preview(name = "Ready dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Ready large font", showBackground = true, fontScale = 2f)
@Preview(name = "Ready Hindi", showBackground = true, locale = "hi")
@Composable
private fun ExamReadyPreview() {
    ScanFitTheme {
        val actions = DocActions(canOpen = { it == DocType.PHOTO })
        ExamScreen(
            ExamUiState.Ready(
                previewExam,
                pinned = true,
                savedDocuments = setOf(DocType.SIGNATURE),
                readyDocuments = mapOf(DocType.PHOTO to 34, DocType.SIGNATURE to 16),
            ),
            {},
            {},
            docActions = actions,
        )
    }
}

@Preview(name = "Not found", showBackground = true)
@Composable
private fun ExamNotFoundPreview() {
    ScanFitTheme { ExamScreen(ExamUiState.NotFound, {}, {}) }
}
