package app.scanfit.feature.custom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.data.UserPreferences
import app.scanfit.core.model.CustomSize
import app.scanfit.core.model.CustomSizeError
import app.scanfit.core.model.CustomSizeFields
import app.scanfit.core.model.DocType
import app.scanfit.core.model.SizeUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Custom size form as typed: text fields, so a half-typed value never jumps (ALGORITHMS 11). */
data class CustomForm(
    val doc: DocType = DocType.PHOTO,
    val unit: SizeUnit = SizeUnit.PX,
    val width: String = "",
    val height: String = "",
    val minKb: String = "",
    val maxKb: String = "",
    val dpi: String = "",
) {
    val fields: CustomSizeFields
        get() =
            CustomSizeFields(
                doc = doc,
                unit = unit,
                width = width.toDoubleOrNull(),
                height = height.toDoubleOrNull(),
                minKb = minKb.toIntOrNull(),
                maxKb = maxKb.toIntOrNull(),
                dpi = dpi.toIntOrNull(),
            )
}

data class CustomUiState(
    val form: CustomForm = CustomForm(),
    /** Every code that applies now; the screen shows them once the field has text or Continue was tried. */
    val errors: List<CustomSizeError> = emptyList(),
    /** Continue was tapped with errors: show every code, including the ones for empty fields. */
    val attempted: Boolean = false,
    /** The size in pixels while entering centimetres, for the "= 276 × 354 px" line. */
    val pixels: Pair<Int, Int>? = null,
    val recent: List<CustomSize> = emptyList(),
) {
    fun shows(error: CustomSizeError): Boolean {
        if (error !in errors) return false
        if (attempted) return true
        return when (error) {
            CustomSizeError.MAX_KB_MISSING -> false
            CustomSizeError.MAX_KB_RANGE -> form.maxKb.isNotEmpty()
            CustomSizeError.MIN_KB_RANGE -> form.minKb.isNotEmpty() && form.maxKb.isNotEmpty()
            CustomSizeError.SIZE_PAIR -> false
            CustomSizeError.WIDTH_RANGE -> form.width.isNotEmpty()
            CustomSizeError.HEIGHT_RANGE -> form.height.isNotEmpty()
            CustomSizeError.DPI_RANGE -> form.dpi.isNotEmpty()
        }
    }
}

/** UI_UX §3 Custom size: what the file is and the form's limits, then the usual photo or ink flow. */
@HiltViewModel
class CustomViewModel
@Inject
constructor(
    private val preferences: UserPreferences,
) : ViewModel() {
    private val form = MutableStateFlow(CustomForm())
    private val attempted = MutableStateFlow(false)

    val uiState: StateFlow<CustomUiState> =
        combine(form, attempted, preferences.recentCustomSizes) { form, attempted, recent ->
            val fields = form.fields
            val w = fields.widthPx()
            val h = fields.heightPx()
            CustomUiState(
                form = form,
                errors = fields.errors(),
                attempted = attempted,
                pixels = if (form.unit == SizeUnit.CM && w != null && h != null) w to h else null,
                recent = recent.mapNotNull(CustomSize::parse),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CustomUiState())

    fun onDoc(doc: DocType) = update { it.copy(doc = doc) }

    /** Switching units keeps the numbers the user typed; the pixel line shows what they mean. */
    fun onUnit(unit: SizeUnit) = update { it.copy(unit = unit) }

    fun onWidth(text: String) = update { it.copy(width = size(text, it.unit)) }

    fun onHeight(text: String) = update { it.copy(height = size(text, it.unit)) }

    fun onMinKb(text: String) = update { it.copy(minKb = whole(text)) }

    fun onMaxKb(text: String) = update { it.copy(maxKb = whole(text)) }

    fun onDpi(text: String) = update { it.copy(dpi = whole(text)) }

    /** Fills the form from a recent size (always in pixels: the canonical text keeps pixels only). */
    fun onRecent(size: CustomSize) {
        attempted.value = false
        form.value =
            CustomForm(
                doc = size.doc,
                unit = SizeUnit.PX,
                width = size.width?.toString().orEmpty(),
                height = size.height?.toString().orEmpty(),
                minKb = if (size.minKb > 0) size.minKb.toString() else "",
                maxKb = size.maxKb.toString(),
                dpi = size.dpi.toString(),
            )
    }

    /** The size to make, remembered as recent; `null` (and every error shown) while the form is not valid. */
    fun onContinue(): CustomSize? {
        val size = form.value.fields.toSize()
        if (size == null) {
            attempted.value = true
            return null
        }
        viewModelScope.launch { preferences.rememberCustomSize(size.text) }
        return size
    }

    private fun update(change: (CustomForm) -> CustomForm) {
        form.value = change(form.value)
    }

    companion object {

        private const val STOP_TIMEOUT_MS = 5_000L
        private const val MAX_DIGITS = 5
        private const val MAX_DECIMALS = 2

        /** Digits only, at most 5 (the largest valid value is 5120). */
        private fun whole(text: String) = text.filter(Char::isDigit).take(MAX_DIGITS)

        /** Pixels: digits only. Centimetres: digits and one decimal point, two decimals at most. */
        private fun size(
            text: String,
            unit: SizeUnit,
        ): String {
            if (unit == SizeUnit.PX) return whole(text)
            val kept = text.filter { it.isDigit() || it == '.' }
            val point = kept.indexOf('.')
            if (point < 0) return kept.take(MAX_DIGITS)
            val decimals = kept.substring(point + 1).filter(Char::isDigit).take(MAX_DECIMALS)
            return kept.substring(0, point).take(MAX_DIGITS) + "." + decimals
        }
    }
}
