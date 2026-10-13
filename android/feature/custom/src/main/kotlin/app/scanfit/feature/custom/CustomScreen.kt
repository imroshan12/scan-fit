package app.scanfit.feature.custom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.specSummary
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.model.CustomSize
import app.scanfit.core.model.CustomSizeError
import app.scanfit.core.model.DocType
import app.scanfit.core.model.SizeUnit

/** Custom size (ALGORITHMS 11, UI_UX §3): Continue hands the validated size to the app, which opens the flow. */
@Composable
fun CustomRoute(
    onBack: () -> Unit,
    onContinue: (CustomSize) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CustomViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CustomScreen(
        state,
        CustomActions(
            onDoc = viewModel::onDoc,
            onUnit = viewModel::onUnit,
            onWidth = viewModel::onWidth,
            onHeight = viewModel::onHeight,
            onMinKb = viewModel::onMinKb,
            onMaxKb = viewModel::onMaxKb,
            onDpi = viewModel::onDpi,
            onRecent = viewModel::onRecent,
            onContinue = { viewModel.onContinue()?.let(onContinue) },
            onBack = onBack,
        ),
        modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomScreen(
    state: CustomUiState,
    actions: CustomActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.custom_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Button(
                    onClick = actions.onContinue,
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(ScanFitSpacing.screenMargin)
                        .heightIn(min = ScanFitSpacing.minTouchTarget),
                ) { Text(stringResource(R.string.common_continue)) }
            }
        },
    ) { padding ->
        Column(
            modifier =
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ScanFitSpacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.lg),
        ) {
            Text(
                stringResource(R.string.custom_intro),
                style = ScanFitType.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.recent.isNotEmpty()) RecentSizes(state.recent, actions.onRecent)
            DocChoice(state.form.doc, actions.onDoc)
            Dimensions(state, actions)
            FileSize(state, actions)
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = ScanFitType.headline, modifier = Modifier.semantics { heading() })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentSizes(
    recent: List<CustomSize>,
    onRecent: (CustomSize) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
        Heading(stringResource(R.string.custom_recent))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
            recent.forEach { size ->
                AssistChip(
                    onClick = { onRecent(size) },
                    label = { Text("${stringResource(docLabel(size.doc))} · ${summary(size)}") },
                )
            }
        }
    }
}

/** `10–20 KB · 140×60`, or `0–50 KB · Any size` when the size keeps the picture's own dimensions. */
@Composable
private fun summary(size: CustomSize): String {
    val text = specSummary(size.spec)
    return if (size.width == null) "$text · ${stringResource(R.string.custom_any_size)}" else text
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocChoice(
    selected: DocType,
    onDoc: (DocType) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
        Heading(stringResource(R.string.custom_what))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
            CustomSize.DOCS.forEach { doc ->
                FilterChip(
                    selected = doc == selected,
                    onClick = { onDoc(doc) },
                    label = { Text(stringResource(docLabel(doc))) },
                    leadingIcon = if (doc == selected) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dimensions(
    state: CustomUiState,
    actions: CustomActions,
) {
    val form = state.form
    val decimal = if (form.unit == SizeUnit.CM) KeyboardType.Decimal else KeyboardType.Number
    Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
        Heading(stringResource(R.string.custom_size_heading))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SizeUnit.entries.forEachIndexed { index, unit ->
                SegmentedButton(
                    selected = form.unit == unit,
                    onClick = { actions.onUnit(unit) },
                    shape = SegmentedButtonDefaults.itemShape(index, SizeUnit.entries.size),
                ) {
                    Text(stringResource(if (unit == SizeUnit.PX) R.string.custom_unit_px else R.string.custom_unit_cm))
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            val width = state.error(WIDTH)
            val height = state.error(HEIGHT)
            NumberField(form.width, actions.onWidth, R.string.custom_width, decimal, width, Modifier.weight(1f))
            NumberField(form.height, actions.onHeight, R.string.custom_height, decimal, height, Modifier.weight(1f))
        }
        val pair = state.error(listOf(CustomSizeError.SIZE_PAIR))
        Text(
            pair?.let { stringResource(it) } ?: stringResource(R.string.custom_size_hint),
            style = ScanFitType.caption,
            color = if (pair != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val dpi = state.error(DPI)
        NumberField(form.dpi, actions.onDpi, R.string.custom_dpi, KeyboardType.Number, dpi, Modifier.fillMaxWidth())
        state.pixels?.let { (w, h) ->
            Text(stringResource(R.string.custom_in_pixels, w, h), style = ScanFitType.figure)
        }
    }
}

@Composable
private fun FileSize(
    state: CustomUiState,
    actions: CustomActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
        Heading(stringResource(R.string.custom_kb_heading))
        Row(
            horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            NumberField(
                state.form.minKb,
                actions.onMinKb,
                R.string.custom_min_kb,
                KeyboardType.Number,
                state.error(MIN),
                Modifier.weight(1f),
            )
            NumberField(
                state.form.maxKb,
                actions.onMaxKb,
                R.string.custom_max_kb,
                KeyboardType.Number,
                state.error(MAX),
                Modifier.weight(1f),
                ImeAction.Done,
            )
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    keyboard: KeyboardType,
    error: Int?,
    modifier: Modifier,
    ime: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ime),
        textStyle = ScanFitType.figure,
        modifier = modifier,
    )
}
