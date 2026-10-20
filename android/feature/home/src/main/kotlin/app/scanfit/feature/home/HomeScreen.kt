package app.scanfit.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.ToolAccent
import app.scanfit.core.designsystem.components.ToolCard
import app.scanfit.core.designsystem.icons.ScanFitIcons
import app.scanfit.core.designsystem.theme.ScanFitRadius
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitType

/** One Home card: what it opens, its words and its look. */
private class Card(
    val tool: HomeTool?,
    val title: Int,
    val body: Int,
    val icon: ImageVector,
    val accent: ToolAccent,
)

/** In UI_UX §3 order: Exam forms first, then the everyday editors, the PDF tools and the Checker. */
private val TOOLS =
    listOf(
        Card(
            HomeTool.EXAMS,
            R.string.home_card_exams,
            R.string.home_card_exams_body,
            ScanFitIcons.Exams,
            ToolAccent.EXAM,
        ),
        Card(
            HomeTool.PHOTO,
            R.string.home_card_photo,
            R.string.home_card_photo_body,
            ScanFitIcons.Photo,
            ToolAccent.PHOTO,
        ),
        Card(
            HomeTool.SIGNATURE,
            R.string.home_card_signature,
            R.string.home_card_signature_body,
            ScanFitIcons.Signature,
            ToolAccent.INK,
        ),
        Card(
            HomeTool.COMPRESS_PDF,
            R.string.tools_pdf_compress,
            R.string.tools_pdf_compress_body,
            ScanFitIcons.Compress,
            ToolAccent.PDF,
        ),
        Card(
            HomeTool.IMAGES_TO_PDF,
            R.string.tools_pdf_images,
            R.string.tools_pdf_images_body,
            ScanFitIcons.Pdf,
            ToolAccent.PDF,
        ),
        Card(
            HomeTool.MERGE_PDFS,
            R.string.tools_pdf_merge,
            R.string.tools_pdf_merge_body,
            ScanFitIcons.Merge,
            ToolAccent.PDF,
        ),
        Card(
            HomeTool.ID_CARD,
            R.string.tools_pdf_id_card,
            R.string.tools_pdf_id_card_body,
            ScanFitIcons.IdCard,
            ToolAccent.PDF,
        ),
        Card(
            HomeTool.CHECK,
            R.string.home_card_check,
            R.string.home_fix_file_body,
            ScanFitIcons.Check,
            ToolAccent.CHECK,
        ),
        Card(
            HomeTool.THUMB,
            R.string.home_card_thumb,
            R.string.home_card_thumb_body,
            ScanFitIcons.Thumb,
            ToolAccent.INK,
        ),
        Card(
            HomeTool.DOCUMENT,
            R.string.home_card_document,
            R.string.home_card_document_body,
            ScanFitIcons.Document,
            ToolAccent.INK,
        ),
    )

private val COMING_SOON =
    listOf(
        Card(null, R.string.tools_scan, R.string.tools_scan_body, ScanFitIcons.Scan, ToolAccent.MUTED),
        Card(null, R.string.tools_guide_sheet, R.string.tools_guide_sheet_body, ScanFitIcons.Print, ToolAccent.MUTED),
        Card(null, R.string.tools_coach, R.string.tools_coach_body, Icons.Filled.Face, ToolAccent.MUTED),
    )

/** Home tab entry point. Keeps the Hilt ViewModel out of [HomeScreen] so previews and tests need no DI. */
@Composable
fun HomeRoute(
    onOpenExam: (String) -> Unit,
    onTool: (HomeTool) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(state.pinned, onOpenExam, onTool, modifier)
}

/**
 * Home (UI_UX §3, editor first): a title, a grid of large tool cards (Exam forms is one of them), the pinned exams, and
 * the tools still to come. Two columns, or one when the screen is narrow for the text size (UI_UX §6).
 */
@Composable
internal fun HomeScreen(
    pinned: List<ExamListItem>,
    onOpenExam: (String) -> Unit,
    onTool: (HomeTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val columns = if (maxWidth / fontScale < TWO_COLUMNS_MIN) 1 else 2
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = ScanFitSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
        ) {
            Header()
            CardGrid(TOOLS, columns) { card -> card.tool?.let(onTool) }
            if (pinned.isNotEmpty()) PinnedExams(pinned, onOpenExam)
            SectionTitle(stringResource(R.string.home_coming_soon_heading))
            ComingSoonList()
            Spacer(Modifier.height(ScanFitSpacing.lg))
        }
    }
}

@Composable
private fun Header() {
    Column(
        modifier = Modifier.padding(horizontal = ScanFitSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.xs),
    ) {
        Text("ScanFit", style = ScanFitType.label, color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.home_title),
            style = ScanFitType.display,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.home_subtitle),
            style = ScanFitType.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Rows of cards; every card in a row is as tall as the tallest. */
@Composable
private fun CardGrid(
    cards: List<Card>,
    columns: Int,
    onCard: (Card) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = ScanFitSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
    ) {
        cards.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
            ) {
                row.forEach { card ->
                    ToolCard(
                        title = stringResource(card.title),
                        description = stringResource(card.body),
                        icon = card.icon,
                        accent = card.accent,
                        onClick = { onCard(card) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                // Keep a lone last card at its column's width.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = ScanFitType.headline,
        color = MaterialTheme.colorScheme.onBackground,
        modifier =
        Modifier
            .padding(horizontal = ScanFitSpacing.screenMargin)
            .padding(top = ScanFitSpacing.md)
            .semantics { heading() },
    )
}

/** The tools still to come: one quiet card with a row each, not tappable. */
@Composable
private fun ComingSoonList() {
    val soon = stringResource(R.string.common_coming_soon)
    Column(
        modifier =
        Modifier
            .padding(horizontal = ScanFitSpacing.screenMargin)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(COMING_SOON_RADIUS))
            .padding(horizontal = ScanFitSpacing.lg),
    ) {
        COMING_SOON.forEachIndexed { index, card ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = ScanFitSpacing.minTouchTarget)
                    .padding(vertical = ScanFitSpacing.md)
                    .semantics(mergeDescendants = true) { stateDescription = soon },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
            ) {
                Box(
                    Modifier.size(COMING_SOON_TILE).background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        card.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(COMING_SOON_ICON),
                    )
                }
                Column {
                    Text(
                        stringResource(card.title),
                        style = ScanFitType.body,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(card.body),
                        style = ScanFitType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val COMING_SOON_RADIUS = 20.dp
private val COMING_SOON_TILE = 32.dp
private val COMING_SOON_ICON = 18.dp

/** "My exams": the pinned exams as a row of pills; each opens its checklist. */
@Composable
private fun PinnedExams(
    pinned: List<ExamListItem>,
    onOpenExam: (String) -> Unit,
) {
    SectionTitle(stringResource(R.string.home_section_pinned))
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScanFitSpacing.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
    ) {
        items(pinned, key = { it.id }) { exam ->
            Surface(
                onClick = { onOpenExam(exam.id) },
                shape = RoundedCornerShape(ScanFitRadius.card),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.heightIn(min = ScanFitSpacing.minTouchTarget),
            ) {
                Column(Modifier.padding(horizontal = ScanFitSpacing.lg, vertical = ScanFitSpacing.md)) {
                    Text(exam.name, style = ScanFitType.body, color = MaterialTheme.colorScheme.onSurface)
                    Text(exam.body, style = ScanFitType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Below this width per unit of font scale, two cards no longer fit their words: one column. */
private val TWO_COLUMNS_MIN = 340.dp
