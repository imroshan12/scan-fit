package app.scanfit.feature.custom

import app.scanfit.core.designsystem.R
import app.scanfit.core.model.CustomSize
import app.scanfit.core.model.CustomSizeError
import app.scanfit.core.model.DocType
import app.scanfit.core.model.SizeUnit

/** The screen's callbacks, so previews and tests can render it without a view model. */
data class CustomActions(
    val onDoc: (DocType) -> Unit = {},
    val onUnit: (SizeUnit) -> Unit = {},
    val onWidth: (String) -> Unit = {},
    val onHeight: (String) -> Unit = {},
    val onMinKb: (String) -> Unit = {},
    val onMaxKb: (String) -> Unit = {},
    val onDpi: (String) -> Unit = {},
    val onRecent: (CustomSize) -> Unit = {},
    val onContinue: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/** The message of the first code among [codes] that the screen shows now, or `null`. */
internal fun CustomUiState.error(codes: List<CustomSizeError>): Int? = codes.firstOrNull { shows(it) }?.let(::message)

internal val WIDTH = listOf(CustomSizeError.WIDTH_RANGE)
internal val HEIGHT = listOf(CustomSizeError.HEIGHT_RANGE)
internal val DPI = listOf(CustomSizeError.DPI_RANGE)
internal val MIN = listOf(CustomSizeError.MIN_KB_RANGE)
internal val MAX = listOf(CustomSizeError.MAX_KB_MISSING, CustomSizeError.MAX_KB_RANGE)

internal fun message(error: CustomSizeError): Int = when (error) {
    CustomSizeError.MAX_KB_MISSING -> R.string.custom_error_max_kb_missing
    CustomSizeError.MAX_KB_RANGE -> R.string.custom_error_max_kb_range
    CustomSizeError.MIN_KB_RANGE -> R.string.custom_error_min_kb_range
    CustomSizeError.SIZE_PAIR -> R.string.custom_error_size_pair
    CustomSizeError.WIDTH_RANGE -> R.string.custom_error_width_range
    CustomSizeError.HEIGHT_RANGE -> R.string.custom_error_height_range
    CustomSizeError.DPI_RANGE -> R.string.custom_error_dpi_range
}

internal fun docLabel(doc: DocType): Int = when (doc) {
    DocType.SIGNATURE -> R.string.custom_doc_signature
    DocType.THUMB_IMPRESSION -> R.string.custom_doc_thumb
    DocType.HANDWRITTEN_DECLARATION -> R.string.custom_doc_document
    else -> R.string.custom_doc_photo
}
