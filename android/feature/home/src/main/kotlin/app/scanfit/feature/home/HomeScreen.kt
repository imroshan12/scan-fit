package app.scanfit.feature.home

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.ConfidenceBadge
import app.scanfit.core.designsystem.components.labelRes
import app.scanfit.core.designsystem.theme.ScanFitRadius
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.model.Confidence
import app.scanfit.core.model.ExamCategory

/** Home tab entry point. Keeps the Hilt ViewModel out of [HomeScreen] so previews and tests need no DI. */
@Composable
fun HomeRoute(
    onOpenExam: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(state, viewModel::onQueryChange, viewModel::onCategorySelected, onOpenExam, modifier)
}

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    onQueryChange: (String) -> Unit,
    onCategorySelected: (ExamCategory?) -> Unit,
    onOpenExam: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The text lives here (Compose text-field guidance); the ViewModel derives everything else from it.
    var text by rememberSaveable { mutableStateOf(state.query) }
    val update: (String) -> Unit = {
        text = it
        onQueryChange(it)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = ScanFitSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
    ) {
        item {
            Text(
                text = stringResource(R.string.home_title),
                style = ScanFitType.display,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = ScanFitSpacing.screenMargin).semantics { heading() },
            )
        }
        item { SearchField(text, update, (state.presets as? PresetsStatus.Ready)?.examCount ?: DEFAULT_EXAM_COUNT) }
        item { CategoryChips(state.category, onCategorySelected) }
        if (state.showsSections) {
            sections(state, onOpenExam)
        } else if (state.presets !is PresetsStatus.Ready) {
            // Nothing to search until the signed presets are verified: say that, never "no matches".
            item { PresetsStatusCard(state.presets) }
        } else if (state.results.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.home_no_results, state.query.trim()),
                    style = ScanFitType.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = ScanFitSpacing.screenMargin, vertical = ScanFitSpacing.lg),
                )
            }
        } else {
            items(state.results, key = { "result:" + it.id }) { ExamRow(it, onOpenExam) }
        }
    }
}

private fun LazyListScope.sections(
    state: HomeUiState,
    onOpenExam: (String) -> Unit,
) {
    if (state.pinned.isNotEmpty()) {
        item { SectionHeader(R.string.home_section_pinned) }
        items(state.pinned, key = { "pinned:" + it.id }) { ExamRow(it, onOpenExam) }
    }
    if (state.popular.isNotEmpty()) {
        item { SectionHeader(R.string.home_section_popular) }
        items(state.popular, key = { "popular:" + it.id }) { ExamRow(it, onOpenExam) }
    }
    item { PresetsStatusCard(state.presets) }
}

@Composable
private fun SearchField(
    text: String,
    onChange: (String) -> Unit,
    examCount: Int,
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScanFitSpacing.screenMargin),
        placeholder = {
            Text(stringResource(R.string.home_search_hint, examCount), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.home_search_clear))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(ScanFitRadius.card),
        // Exam codes (IBPS, CGL) must reach the search as typed: no autocorrect, no auto-capitals.
        keyboardOptions =
        KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}

@Composable
private fun CategoryChips(
    selected: ExamCategory?,
    onSelect: (ExamCategory?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScanFitSpacing.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.home_category_all)) },
            )
        }
        items(ExamCategory.entries, key = { it.name }) { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(category) },
                label = { Text(stringResource(category.labelRes())) },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: Int) {
    Text(
        text = stringResource(text),
        style = ScanFitType.label,
        color = MaterialTheme.colorScheme.primary,
        modifier =
        Modifier
            .padding(horizontal = ScanFitSpacing.screenMargin)
            .padding(top = ScanFitSpacing.sm)
            .semantics { heading() },
    )
}

@Composable
private fun ExamRow(
    exam: ExamListItem,
    onOpenExam: (String) -> Unit,
) {
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = ScanFitSpacing.minTouchTarget)
            .clickable(role = Role.Button) { onOpenExam(exam.id) }
            .padding(horizontal = ScanFitSpacing.screenMargin, vertical = ScanFitSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.xs),
    ) {
        Text(exam.name, style = ScanFitType.headline, color = MaterialTheme.colorScheme.onSurface)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
        ) {
            Text(
                listOf(exam.body, stringResource(exam.category.labelRes())).distinct().joinToString(" · "),
                style = ScanFitType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Only the unverified state needs a badge in a list; the exam screen shows every level (CLAUDE.md rule 6).
            if (exam.confidence == Confidence.LOW) ConfidenceBadge(Confidence.LOW)
        }
        HorizontalDivider(Modifier.padding(top = ScanFitSpacing.sm), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun PresetsStatusCard(status: PresetsStatus) {
    val colors = ScanFitTheme.colors
    Box(Modifier.padding(horizontal = ScanFitSpacing.screenMargin, vertical = ScanFitSpacing.sm)) {
        when (status) {
            // Same card shape as the loaded state, so nothing jumps when the result arrives.
            PresetsStatus.Loading -> {
                StatusCard(
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                    leading = {
                        CircularProgressIndicator(Modifier.size(ScanFitSpacing.xl), strokeWidth = LOADER_STROKE)
                    },
                ) {
                    Text(stringResource(R.string.home_presets_verifying), style = ScanFitType.label)
                }
            }

            is PresetsStatus.Ready -> {
                StatusCard(
                    container = colors.successContainer,
                    content = colors.onSuccessContainer,
                    leading = { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.success) },
                ) {
                    Text(stringResource(R.string.home_presets_verified), style = ScanFitType.label)
                    Text(
                        pluralStringResource(
                            R.plurals.home_presets_status,
                            status.examCount,
                            status.examCount,
                            status.version.toString(),
                        ),
                        style = ScanFitType.figure,
                    )
                }
            }

            PresetsStatus.Failed -> {
                StatusCard(
                    container = colors.errorContainer,
                    content = colors.onErrorContainer,
                    leading = { Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.error) },
                ) {
                    Text(stringResource(R.string.home_presets_unverified), style = ScanFitType.label)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    container: Color,
    content: Color,
    leading: @Composable () -> Unit,
    text: @Composable () -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = ScanFitSpacing.minTouchTarget)
            .background(container, RoundedCornerShape(ScanFitRadius.card))
            .padding(ScanFitSpacing.lg)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
    ) {
        leading()
        CompositionLocalProvider(LocalContentColor provides content) {
            Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.xs)) { text() }
        }
    }
}

private val LOADER_STROKE = 3.dp

internal const val DEFAULT_EXAM_COUNT = 55
