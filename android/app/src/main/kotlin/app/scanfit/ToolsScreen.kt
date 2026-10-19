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
import androidx.compose.ui.semantics.heading
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
    onPdf: (tool: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Custom size (ALGORITHMS 11), the Checker (ALGORITHMS 12) and the PDF tools (ALGORITHMS 6) have landed.
        ToolRow(R.string.tools_custom_resize, onCustomSize)
        ToolRow(R.string.tools_checker, onChecker)
        Text(
            stringResource(R.string.tools_pdf),
            style = ScanFitType.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
            Modifier
                .padding(horizontal = ScanFitSpacing.screenMargin, vertical = ScanFitSpacing.sm)
                .semantics { heading() },
        )
        PDF_TOOLS.forEach { (wire, title, body) -> ToolRow(title, { onPdf(wire) }, body) }
        ComingSoon()
    }
}

/** The PDF tools (route argument, title, one-line description). */
private val PDF_TOOLS =
    listOf(
        Triple("images", R.string.tools_pdf_images, R.string.tools_pdf_images_body),
        Triple("compress", R.string.tools_pdf_compress, R.string.tools_pdf_compress_body),
        Triple("merge", R.string.tools_pdf_merge, R.string.tools_pdf_merge_body),
        Triple("id_card", R.string.tools_pdf_id_card, R.string.tools_pdf_id_card_body),
    )

@Composable
private fun ToolRow(
    title: Int,
    onClick: () -> Unit,
    body: Int? = null,
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
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = ScanFitType.body, color = MaterialTheme.colorScheme.onBackground)
            if (body != null) {
                Text(
                    stringResource(body),
                    style = ScanFitType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
    HorizontalDivider()
}

@Composable
private fun ComingSoon() {
    val tools =
        listOf(
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
