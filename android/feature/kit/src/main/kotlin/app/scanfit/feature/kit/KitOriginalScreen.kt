package app.scanfit.feature.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.components.CheckerTargetRow
import app.scanfit.core.designsystem.components.labelRes
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.imaging.toBitmap
import app.scanfit.core.model.DocType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun KitOriginalRoute(
    onBack: () -> Unit,
    onUse: (examId: String, type: DocType) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KitOriginalViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state) { if (state == KitOriginalUiState.Deleted) onBack() }
    KitOriginalScreen(state, KitOriginalActions(onBack, viewModel::onQuery, onUse, viewModel::onDelete), modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KitOriginalScreen(
    state: KitOriginalUiState,
    actions: KitOriginalActions,
    modifier: Modifier = Modifier,
) {
    val ready = state as? KitOriginalUiState.Ready
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(ready?.let { stringResource(it.original.kind.labelRes()) }.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    if (ready != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.kit_delete))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (ready != null) {
                Surface(tonalElevation = 2.dp) {
                    Button(
                        onClick = { choosing = true },
                        modifier = Modifier.fillMaxWidth().padding(ScanFitSpacing.screenMargin)
                            .heightIn(min = ScanFitSpacing.minTouchTarget),
                    ) { Text(stringResource(R.string.kit_use_for_exam)) }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                KitOriginalUiState.Loading, KitOriginalUiState.Deleted ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                KitOriginalUiState.Missing -> Column(
                    Modifier.align(Alignment.Center).padding(ScanFitSpacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
                ) {
                    Text(stringResource(R.string.kit_original_missing), textAlign = TextAlign.Center)
                    TextButton(onClick = actions.onBack) { Text(stringResource(R.string.common_back)) }
                }

                is KitOriginalUiState.Ready -> OriginalDetails(state)
            }
        }
    }
    if (ready != null && choosing) {
        ModalBottomSheet(onDismissRequest = { choosing = false }) {
            TargetList(ready, actions) { choosing = false }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.kit_delete_title)) },
            text = { Text(stringResource(R.string.kit_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.onDelete()
                }) { Text(stringResource(R.string.kit_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun OriginalDetails(state: KitOriginalUiState.Ready) {
    val image by produceState<ImageBitmap?>(null, state.preview) {
        value = state.preview?.let { withContext(Dispatchers.Default) { it.toBitmap().asImageBitmap() } }
    }
    Column(
        Modifier.fillMaxSize().padding(ScanFitSpacing.screenMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(max = 320.dp), contentAlignment = Alignment.Center) {
            image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit) }
        }
        Text(stringResource(R.string.kit_saved_on, formatDay(state.original.createdAtMillis)), style = ScanFitType.body)
        Text(
            stringResource(R.string.kit_original_size, state.original.width, state.original.height),
            style = ScanFitType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Use for exam…" (ALGORITHMS 13.2): the Checker's list for the original's kind, with search. */
@Composable
private fun TargetList(
    state: KitOriginalUiState.Ready,
    actions: KitOriginalActions,
    onChosen: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(horizontal = ScanFitSpacing.screenMargin)) {
        item(key = "heading") {
            Column(verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
                Text(
                    stringResource(R.string.kit_use_title),
                    style = ScanFitType.title,
                    modifier = Modifier.semantics { heading() },
                )
                OutlinedTextField(
                    value = state.query,
                    onValueChange = actions.onQuery,
                    placeholder = { Text(stringResource(R.string.checker_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        items(state.targets, key = { "${it.exam.id}/${it.slot.type}" }) { target ->
            CheckerTargetRow(target) {
                onChosen()
                actions.onUse(it.exam.id, it.slot.type)
            }
        }
    }
}
