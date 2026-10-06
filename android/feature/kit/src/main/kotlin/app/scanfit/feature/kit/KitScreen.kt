package app.scanfit.feature.kit

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.ConfidenceBadge
import app.scanfit.core.designsystem.components.labelRes
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.model.DocType

@Composable
fun KitRoute(
    onOpen: (String, DocType) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KitViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshDrafts()
        onPauseOrDispose { }
    }
    KitScreen(state, viewModel::retry, onOpen, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KitScreen(
    state: KitUiState,
    onRetry: () -> Unit,
    onOpen: (String, DocType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_kit)) }) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                KitUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                KitUiState.Empty -> KitEmpty(Modifier.align(Alignment.Center))

                KitUiState.Error -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(ScanFitSpacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
                ) {
                    Text(stringResource(R.string.kit_load_failed), textAlign = TextAlign.Center)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                }

                is KitUiState.Ready -> KitContent(state.exams, onOpen)
            }
        }
    }
}

@Composable
private fun KitEmpty(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(ScanFitSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
    ) {
        Icon(Icons.Filled.AccountBox, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = stringResource(R.string.kit_empty_title),
            style = ScanFitType.title,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.kit_empty_body),
            style = ScanFitType.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun KitContent(exams: List<KitExam>, onOpen: (String, DocType) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(ScanFitSpacing.screenMargin)) {
        for (exam in exams) {
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
                    KitRow(document) { onOpen(exam.id, document.type) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun KitRow(document: KitDocument, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(vertical = ScanFitSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.xs)) {
            Text(stringResource(document.type.labelRes()), style = ScanFitType.body)
            Text(
                stringResource(R.string.draft_ready_size, document.roundedKb),
                style = ScanFitType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
