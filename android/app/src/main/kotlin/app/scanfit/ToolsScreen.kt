package app.scanfit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitType

/**
 * Tools tab. It only lists the tools until their features land; navigation to each feature is composed here
 * in :app because features never depend on each other (ARCHITECTURE section 2).
 */
@Composable
fun ToolsScreen(
    onCustomSize: () -> Unit,
    onChecker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Custom size (ALGORITHMS 11) and the Checker (ALGORITHMS 12) have landed; the rest are still coming soon.
        ToolRow(R.string.tools_custom_resize, onCustomSize)
        ToolRow(R.string.tools_checker, onChecker)
        ComingSoon()
    }
}

@Composable
private fun ToolRow(
    title: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = ScanFitSpacing.minTouchTarget)
            .padding(horizontal = ScanFitSpacing.screenMargin, vertical = ScanFitSpacing.md)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(title), style = ScanFitType.body, color = MaterialTheme.colorScheme.onBackground)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
    HorizontalDivider()
}

@Composable
private fun ComingSoon() {
    val tools =
        listOf(
            R.string.tools_pdf,
            R.string.tools_scan,
            R.string.tools_guide_sheet,
            R.string.tools_coach,
        )
    Column {
        tools.forEach { title ->
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = ScanFitSpacing.minTouchTarget)
                    .padding(horizontal = ScanFitSpacing.screenMargin, vertical = ScanFitSpacing.md)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(title), style = ScanFitType.body, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    stringResource(R.string.common_coming_soon),
                    style = ScanFitType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}
