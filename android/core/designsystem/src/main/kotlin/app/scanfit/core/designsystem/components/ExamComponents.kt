package app.scanfit.core.designsystem.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitRadius
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitTheme
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.model.Confidence
import app.scanfit.core.model.DimensionMode
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.DocType
import app.scanfit.core.model.ExamCategory
import app.scanfit.core.model.FileFormat

@StringRes
fun ExamCategory.labelRes(): Int = when (this) {
    ExamCategory.BANKING -> R.string.category_banking
    ExamCategory.INSURANCE -> R.string.category_insurance
    ExamCategory.REGULATOR -> R.string.category_regulator
    ExamCategory.SSC -> R.string.category_ssc
    ExamCategory.UPSC -> R.string.category_upsc
    ExamCategory.RAILWAY -> R.string.category_railway
    ExamCategory.DEFENCE -> R.string.category_defence
    ExamCategory.TEACHING -> R.string.category_teaching
    ExamCategory.STATE_PSC -> R.string.category_state_psc
    ExamCategory.ENTRANCE -> R.string.category_entrance
}

@StringRes
fun DocType.labelRes(): Int = when (this) {
    DocType.PHOTO -> R.string.doc_photo
    DocType.POSTCARD_PHOTO -> R.string.doc_postcard_photo
    DocType.SIGNATURE -> R.string.doc_signature
    DocType.TRIPLE_SIGNATURE -> R.string.doc_triple_signature
    DocType.LEFT_THUMB -> R.string.doc_left_thumb
    DocType.THUMB_IMPRESSION -> R.string.doc_thumb_impression
    DocType.LEFT_HAND_FINGERS_THUMB -> R.string.doc_left_hand_fingers_thumb
    DocType.RIGHT_HAND_FINGERS_THUMB -> R.string.doc_right_hand_fingers_thumb
    DocType.HANDWRITTEN_DECLARATION -> R.string.doc_handwritten_declaration
    DocType.PHOTO_ID -> R.string.doc_photo_id
    DocType.ID_PROOF -> R.string.doc_id_proof
    DocType.CLASS10_CERTIFICATE -> R.string.doc_class10_certificate
    DocType.CATEGORY_CERTIFICATE -> R.string.doc_category_certificate
    DocType.PWD_CERTIFICATE -> R.string.doc_pwd_certificate
    DocType.SC_ST_CERTIFICATE -> R.string.doc_sc_st_certificate
}

/**
 * The trust level of a preset (UI_UX §4 `ConfidenceBadge`). Low confidence always reads "Unverified — check notice"
 * (CLAUDE.md rule 6). Icon + text, never colour alone (UI_UX §6).
 */
@Composable
fun ConfidenceBadge(
    confidence: Confidence,
    modifier: Modifier = Modifier,
) {
    val colors = ScanFitTheme.colors
    val style =
        when (confidence) {
            Confidence.HIGH -> {
                BadgeStyle(
                    colors.successContainer,
                    colors.onSuccessContainer,
                    Icons.Filled.CheckCircle,
                    R.string.exam_confidence_high,
                )
            }

            Confidence.MEDIUM -> {
                BadgeStyle(
                    colors.surfaceVariant,
                    colors.onSurfaceVariant,
                    Icons.Filled.Info,
                    R.string.exam_confidence_medium,
                )
            }

            Confidence.LOW -> {
                BadgeStyle(
                    colors.warningContainer,
                    colors.onWarningContainer,
                    Icons.Filled.Warning,
                    R.string.exam_confidence_low,
                )
            }
        }
    Row(
        modifier =
        modifier
            .background(style.container, RoundedCornerShape(ScanFitRadius.chip))
            .padding(horizontal = ScanFitSpacing.sm, vertical = ScanFitSpacing.xs)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.xs),
    ) {
        Icon(style.icon, contentDescription = null, tint = style.content, modifier = Modifier.size(ScanFitSpacing.lg))
        Text(stringResource(style.text), style = ScanFitType.caption, color = style.content)
    }
}

private data class BadgeStyle(
    val container: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    @param:StringRes val text: Int,
)

/** `20–50 KB · 200×230` (UI_UX §3 `SpecSummary`), or "Spec not verified yet" when a slot has no limits at all. */
@Composable
fun specSummary(doc: DocSpec): String {
    val min = doc.sizeKb.min
    val max = doc.sizeKb.max
    val sizeText =
        when {
            min != null && max != null -> stringResource(R.string.exam_size_range, kb(min), kb(max))
            max != null -> stringResource(R.string.exam_size_max, kb(max))
            min != null -> stringResource(R.string.exam_size_min, kb(min))
            else -> null
        }
    val d = doc.dimensions
    val dimsText =
        when (d.mode) {
            DimensionMode.EXACT, DimensionMode.PREFERRED -> {
                val w = d.width
                val h = d.height
                if (w != null && h != null) "$w×$h" else null
            }

            DimensionMode.RANGE -> range(d.minW, d.maxW)?.let { w -> range(d.minH, d.maxH)?.let { h -> "$w × $h" } }

            DimensionMode.NONE -> null
        }
    val formatText = if (FileFormat.PDF in doc.formats) "PDF" else null
    val parts = listOfNotNull(sizeText, dimsText, formatText)
    return if (sizeText == null && d.mode == DimensionMode.NONE) {
        stringResource(R.string.exam_spec_unknown)
    } else {
        parts.joinToString(" · ")
    }
}

private fun kb(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

private fun range(
    min: Int?,
    max: Int?,
): String? = when {
    min != null && max != null -> if (min == max) "$min" else "$min–$max"
    else -> null
}
