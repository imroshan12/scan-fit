package app.scanfit.feature.home

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitRadius
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType

/** Home tab entry point. Keeps the Hilt ViewModel out of [HomeScreen] so previews and tests need no DI. */
@Composable
fun HomeRoute(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(state, modifier)
}

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ScanFitSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.lg),
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = ScanFitType.display,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        SearchPlaceholder(examCount = (state as? HomeUiState.Ready)?.examCount ?: DEFAULT_EXAM_COUNT)
        PresetsStatus(state)
    }
}

/** Search is a Phase 2 feature: this is the visual placeholder, deliberately not interactive yet. */
@Composable
private fun SearchPlaceholder(examCount: Int) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = ScanFitSpacing.minTouchTarget)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(ScanFitRadius.card))
            .padding(ScanFitSpacing.lg)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = stringResource(R.string.home_search_hint, examCount),
            style = ScanFitType.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PresetsStatus(state: HomeUiState) {
    val colors = ScanFitTheme.colors
    when (state) {
        // Same card shape as the loaded state, so nothing jumps when the result arrives.
        HomeUiState.Loading -> {
            StatusCard(
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
                leading = { CircularProgressIndicator(Modifier.size(ScanFitSpacing.xl), strokeWidth = LOADER_STROKE) },
            ) {
                Text(stringResource(R.string.home_presets_verifying), style = ScanFitType.label)
            }
        }

        is HomeUiState.Ready -> {
            StatusCard(
                container = colors.successContainer,
                content = colors.onSuccessContainer,
                leading = { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.success) },
            ) {
                Text(stringResource(R.string.home_presets_verified), style = ScanFitType.label)
                Text(
                    pluralStringResource(
                        R.plurals.home_presets_status,
                        state.examCount,
                        state.examCount,
                        state.version.toString(),
                    ),
                    style = ScanFitType.figure,
                )
            }
        }

        HomeUiState.Failed -> {
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

private const val DEFAULT_EXAM_COUNT = 55

@Preview(name = "Ready", showBackground = true)
@Preview(name = "Ready dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Ready large font", showBackground = true, fontScale = 2f)
@Preview(name = "Ready Hindi", showBackground = true, locale = "hi")
@Composable
private fun HomeScreenReadyPreview() {
    ScanFitTheme { HomeScreen(HomeUiState.Ready(examCount = DEFAULT_EXAM_COUNT, version = 1)) }
}

@Preview(name = "Loading", showBackground = true)
@Composable
private fun HomeScreenLoadingPreview() {
    ScanFitTheme { HomeScreen(HomeUiState.Loading) }
}

@Preview(name = "Failed", showBackground = true)
@Composable
private fun HomeScreenFailedPreview() {
    ScanFitTheme { HomeScreen(HomeUiState.Failed) }
}
