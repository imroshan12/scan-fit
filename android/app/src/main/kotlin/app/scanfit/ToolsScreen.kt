package app.scanfit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
fun ToolsScreen(modifier: Modifier = Modifier) {
    val tools =
        listOf(
            R.string.tools_custom_resize,
            R.string.tools_checker,
            R.string.tools_pdf,
            R.string.tools_scan,
            R.string.tools_guide_sheet,
            R.string.tools_coach,
        )
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
