package app.scanfit.feature.flowink

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.data.UserPreferences
import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.data.export.ExportRequest
import app.scanfit.core.data.export.SaveResult
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.imaging.AndroidImageDecoder
import app.scanfit.core.imaging.CropAdjust
import app.scanfit.core.imaging.CropCorner
import app.scanfit.core.imaging.CropRect
import app.scanfit.core.imaging.FitError
import app.scanfit.core.imaging.InkOptions
import app.scanfit.core.imaging.InkQuality
import app.scanfit.core.imaging.InkVariant
import app.scanfit.core.imaging.Pipeline
import app.scanfit.core.imaging.PipelineOutcome
import app.scanfit.core.imaging.Raster
import app.scanfit.core.inspect.Inspector
import app.scanfit.core.match.DocKind
import app.scanfit.core.match.FileFacts
import app.scanfit.core.match.MatchEngine
import app.scanfit.core.match.Verdict
import app.scanfit.core.model.DocSpec
import app.scanfit.core.presets.PresetsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The exam slot the ink document is for (ALGORITHMS 9.5: its cleanup variant and match kind come from its type). */
data class InkSlot(
    val examId: String,
    val examName: String,
    val unverified: Boolean,
    val spec: DocSpec,
) {
    val kind: DocKind get() = DocKind.of(spec.type)

    val variant: InkVariant
        get() =
            when (kind) {
                DocKind.SIGNATURE -> InkVariant.SIGNATURE
                DocKind.THUMB, DocKind.FINGERS -> InkVariant.THUMB
                else -> InkVariant.DOCUMENT
            }

    val pipeline: Pipeline
        get() =
            when (variant) {
                InkVariant.SIGNATURE -> Pipeline.SIGNATURE_CLEANUP
                InkVariant.THUMB -> Pipeline.THUMB_CLEANUP
                InkVariant.DOCUMENT -> Pipeline.DOCUMENT_CLEANUP
            }

    /** Signatures need the one-time "running handwriting" confirmation before saving (§9.5). */
    val needsHandwritingConfirmation: Boolean get() = kind == DocKind.SIGNATURE

    /** "Crisp black" and "Darker ink" apply to binarised ink only; a thumb keeps its ridges (§9.5). */
    val hasInkOptions: Boolean get() = variant != InkVariant.THUMB

    /** `T` of ALGORITHMS 9.4 in whole KB, for "Fitting to 16 KB"; `null` when the slot has no maximum. */
    val targetKb: Int?
        get() {
            val max = spec.sizeKb.max ?: return null
            val t = spec.sizeKb.target ?: (((spec.sizeKb.min ?: 0.0) + max) / 2)
            return (t + HALF).toInt()
        }

    private companion object {
        const val HALF = 0.5
    }
}

/** The review options of §9.5: crisp black (signature on, document off) and the "Darker ink" factor. */
data class InkReviewOptions(
    val crispBlack: Boolean,
    val inkFactor: Double = InkOptions.DEFAULT_INK_FACTOR,
)

sealed interface InkReviewResult {
    data class Working(
        val targetKb: Int?,
    ) : InkReviewResult

    class Ready(
        /** The fitted JPEG, exactly as it would be written. */
        val bytes: ByteArray,
        val kb: Int,
        val width: Int,
        val height: Int,
        /** EXACT or ACCEPTED for this slot (ALGORITHMS 9.7), checked on the re-inspected bytes as the slot's kind. */
        val meetsRules: Boolean,
        /** The coverage gate (§3 step 8): a warning only, saving is still allowed. */
        val quality: InkQuality,
    ) : InkReviewResult

    data class Failed(
        val error: FitError,
    ) : InkReviewResult
}

/** The ink flow's steps (UI_UX §3 Capture/crop, Review). Immutable: the single source of truth for the screen. */
sealed interface InkUiState {
    data object Loading : InkUiState

    /** The exam or this ink slot is not in the trusted presets. */
    data object NotFound : InkUiState

    data class PickSource(
        val slot: InkSlot,
        val openFailed: Boolean = false,
    ) : InkUiState

    data class Opening(
        val slot: InkSlot,
    ) : InkUiState

    data class Crop(
        val slot: InkSlot,
        val image: Raster,
        val rect: CropRect,
    ) : InkUiState

    data class Review(
        val slot: InkSlot,
        val options: InkReviewOptions,
        val result: InkReviewResult,
        /** The handwriting tick (signatures only): from the device, or ticked now. */
        val handwritingConfirmed: Boolean,
        val save: SaveResult = SaveResult(),
        val rendering: Boolean = false,
    ) : InkUiState {
        val canSave: Boolean
            get() =
                !rendering &&
                    (result as? InkReviewResult.Ready)?.meetsRules == true &&
                    (!slot.needsHandwritingConfirmation || handwritingConfirmed) &&
                    save.state != SaveState.SAVING
    }
}

@HiltViewModel
class InkFlowViewModel
@Inject
constructor(
    savedState: SavedStateHandle,
    presets: PresetsRepository,
    private val tools: InkTools,
    private val preferences: UserPreferences,
    private val exporter: DocumentExporter,
    @InkWork private val work: CoroutineDispatcher,
) : ViewModel() {
    private val examId: String = savedState.get<String>(EXAM_ID).orEmpty()
    private val docType: String = savedState.get<String>(DOC_TYPE).orEmpty()

    private val _uiState = MutableStateFlow<InkUiState>(InkUiState.Loading)
    val uiState: StateFlow<InkUiState> = _uiState.asStateFlow()

    private var job: Job? = null
    private var renderJob: Job? = null
    private var renderVersion = 0
    private var lastCrop: InkUiState.Crop? = null
    private var cropped: Raster? = null
    private var pendingSave: ExportRequest? = null

    init {
        viewModelScope.launch {
            val bundle =
                combine(presets.outcome, presets.bundle) { outcome, bundle -> outcome to bundle }
                    .first { (outcome, bundle) -> outcome != null || bundle != null }
                    .second
            val exam = bundle?.exams?.firstOrNull { it.id == examId }
            val spec = exam?.documents?.firstOrNull { it.type.name.lowercase() == docType }
            val slot = if (exam != null && spec != null) InkSlot(exam.id, exam.name, exam.isUnverified, spec) else null
            _uiState.value =
                if (slot == null || slot.kind == DocKind.PHOTO || slot.kind == DocKind.PDF_DOCUMENT) {
                    InkUiState.NotFound
                } else {
                    InkUiState.PickSource(slot)
                }
        }
    }

    private val slot: InkSlot?
        get() =
            when (val s = _uiState.value) {
                is InkUiState.PickSource -> s.slot
                is InkUiState.Opening -> s.slot
                is InkUiState.Crop -> s.slot
                is InkUiState.Review -> s.slot
                else -> null
            }

    /** A URI for the camera app to write into (the UI launches `TakePicture` with it). */
    fun newCaptureUri(): String = tools.newCaptureUri()

    fun onImageSelected(uri: String) {
        val slot = slot ?: return
        if ((_uiState.value as? InkUiState.Review)?.save?.state == SaveState.SAVING) return
        job?.cancel()
        _uiState.value = InkUiState.Opening(slot)
        job =
            viewModelScope.launch {
                val raster =
                    withContext(work) {
                        val bytes = tools.read(uri)
                        tools.discardCaptures()
                        bytes?.let { tools.decode(it, decodeCap(slot.spec)) }
                    }
                _uiState.value =
                    if (raster == null) {
                        InkUiState.PickSource(slot, openFailed = true)
                    } else {
                        InkUiState.Crop(slot, raster, wholeImage(raster))
                    }
            }
    }

    /** The camera was closed without a photo, or the picker without a choice: just tidy up. */
    fun onPickCancelled() {
        tools.discardCaptures()
    }

    fun onMove(
        dx: Double,
        dy: Double,
    ) = updateCrop { CropAdjust.move(it.rect, dx, dy, it.image.width, it.image.height) }

    fun onResize(
        corner: CropCorner,
        dx: Double,
        dy: Double,
    ) = updateCrop { CropAdjust.resize(it.rect, corner, dx, dy, it.image.width, it.image.height) }

    fun onRotate() {
        val crop = _uiState.value as? InkUiState.Crop ?: return
        job?.cancel()
        job =
            viewModelScope.launch {
                val rotated = withContext(work) { CropAdjust.rotateClockwise(crop.image) }
                _uiState.value = InkUiState.Crop(crop.slot, rotated, wholeImage(rotated))
            }
    }

    /** Back to the whole image (§9.5: the free crop starts as the whole image). */
    fun onResetCrop() = updateCrop { wholeImage(it.image) }

    fun onCropDone() {
        val crop = _uiState.value as? InkUiState.Crop ?: return
        job?.cancel()
        job =
            viewModelScope.launch {
                val r = crop.rect
                cropped = withContext(work) { crop.image.crop(r.x, r.y, r.w, r.h) }
                lastCrop = crop
                val confirmed = preferences.handwritingConfirmed.first()
                val options = InkReviewOptions(crispBlack = crop.slot.variant == InkVariant.SIGNATURE)
                render(crop.slot, options, confirmed)
            }
    }

    fun onCrispBlack(on: Boolean) = updateOptions(debounce = false) { it.copy(crispBlack = on) }

    /** "Darker ink" (§9.5): 0.3–0.9 in steps of 0.1; a slider drag is debounced. */
    fun onInkFactor(factor: Double) = updateOptions(debounce = true) {
        it.copy(inkFactor = (Math.round(factor.coerceIn(MIN_INK, MAX_INK) * TENTHS) / TENTHS))
    }

    /** The one-time handwriting tick (§9.5): remembered on the device, never asked again. */
    fun onConfirmHandwriting() {
        val review = _uiState.value as? InkUiState.Review ?: return
        if (review.handwritingConfirmed) return
        _uiState.value = review.copy(handwritingConfirmed = true)
        viewModelScope.launch { preferences.confirmHandwriting() }
    }

    /**
     * Starts saving the review (ALGORITHMS 1.6). Returns a file name when the UI must first let the user pick the
     * destination (Android 8–9), else `null`; the result arrives in the review's `save`.
     */
    fun onSave(): String? {
        val review = _uiState.value as? InkUiState.Review ?: return null
        val ready = review.result as? InkReviewResult.Ready ?: return null
        if (!review.canSave) return null
        val slot = review.slot
        val request = ExportRequest(slot.examId, slot.examName, slot.spec, slot.kind, ready.bytes)
        _uiState.value = review.copy(save = SaveResult(SaveState.SAVING))
        if (exporter.destinations.requiresPicker) {
            pendingSave = request
            return request.fileName
        }
        export(request, null)
        return null
    }

    /** The destination picker's answer (Android 8–9): `null` = cancelled, or the launch failed. */
    fun onSaveDestination(
        uri: String?,
        launchFailed: Boolean = false,
    ) {
        val request = pendingSave ?: return
        pendingSave = null
        val review = _uiState.value as? InkUiState.Review ?: return
        if (uri == null) {
            val next = if (launchFailed) SaveState.SAVE_FAILED else SaveState.IDLE
            _uiState.value = review.copy(save = SaveResult(next))
        } else {
            export(request, uri)
        }
    }

    /** Back inside the flow: Review -> Crop -> PickSource. `false` = leave the flow. */
    fun onBack(): Boolean {
        val slot = slot ?: return false
        return when (val s = _uiState.value) {
            is InkUiState.Review -> {
                if (s.save.state == SaveState.SAVING) return true
                renderJob?.cancel()
                renderVersion++
                _uiState.value = lastCrop ?: InkUiState.PickSource(slot)
                true
            }

            is InkUiState.Crop, is InkUiState.Opening -> {
                job?.cancel()
                _uiState.value = InkUiState.PickSource(slot)
                true
            }

            else -> false
        }
    }

    private fun export(
        request: ExportRequest,
        uri: String?,
    ) {
        viewModelScope.launch {
            val result = withContext(work) { exporter.export(request, uri) }
            val review = _uiState.value as? InkUiState.Review ?: return@launch
            _uiState.value = review.copy(save = result)
        }
    }

    private fun updateCrop(change: (InkUiState.Crop) -> CropRect) {
        val crop = _uiState.value as? InkUiState.Crop ?: return
        _uiState.value = crop.copy(rect = change(crop))
    }

    private fun updateOptions(
        debounce: Boolean,
        change: (InkReviewOptions) -> InkReviewOptions,
    ) {
        val review = _uiState.value as? InkUiState.Review ?: return
        if (review.save.state == SaveState.SAVING || !review.slot.hasInkOptions) return
        render(review.slot, change(review.options), review.handwritingConfirmed, debounce)
    }

    /** Cleanup → pad to aspect → fit → re-inspect (§3, §9.5). A newer change cancels the running one. */
    private fun render(
        slot: InkSlot,
        options: InkReviewOptions,
        confirmed: Boolean,
        debounce: Boolean = false,
    ) {
        val source = cropped ?: return
        renderJob?.cancel()
        val version = ++renderVersion
        val working = InkReviewResult.Working(slot.targetKb)
        // While the slider moves, the last result stays on screen until the debounce has passed.
        val shown = (_uiState.value as? InkUiState.Review)?.result?.takeIf { debounce } ?: working
        _uiState.value = InkUiState.Review(slot, options, shown, confirmed, rendering = true)
        renderJob =
            viewModelScope.launch {
                if (debounce) {
                    delay(SLIDER_DEBOUNCE_MS)
                    if (version != renderVersion) return@launch
                    _uiState.value = InkUiState.Review(slot, options, working, confirmed, rendering = true)
                }
                val ink = InkOptions(crispBlack = options.crispBlack, inkFactor = options.inkFactor)
                val result =
                    withContext(work) {
                        when (val outcome = tools.fit(source, slot.spec, slot.pipeline, ink)) {
                            is PipelineOutcome.Failure -> InkReviewResult.Failed(outcome.error)

                            is PipelineOutcome.Success -> {
                                val quality = outcome.result.ink?.quality ?: InkQuality.OK
                                ready(outcome.result.fit.bytes, slot, quality)
                            }
                        }
                    }
                if (version != renderVersion) return@launch
                val current = _uiState.value as? InkUiState.Review
                _uiState.value = InkUiState.Review(slot, options, result, current?.handwritingConfirmed ?: confirmed)
            }
    }

    companion object {
        /** Navigation arguments: `exam/{examId}/ink/{docType}` (docType = preset wire name, e.g. `signature`). */
        const val EXAM_ID = "examId"
        const val DOC_TYPE = "docType"

        private const val SLIDER_DEBOUNCE_MS = 300L
        private const val MIN_INK = 0.3
        private const val MAX_INK = 0.9
        private const val TENTHS = 10.0
        private const val KB = 1024

        private fun wholeImage(image: Raster) = CropRect(0, 0, image.width, image.height)

        /** ALGORITHMS 1.1: decode no larger than 2x the largest target dimension (at least 1600 px). */
        private fun decodeCap(spec: DocSpec): Int {
            val d = spec.dimensions
            val largest = listOfNotNull(d.width, d.height, d.maxW, d.maxH).maxOrNull() ?: 0
            return AndroidImageDecoder.longSideCap(largest)
        }

        /** Re-inspects the fitted bytes and evaluates them as the slot's kind, as the export will be (rule 3). */
        private fun ready(
            bytes: ByteArray,
            slot: InkSlot,
            quality: InkQuality,
        ): InkReviewResult.Ready {
            val inspected = Inspector.inspect(bytes)
            val verdict = MatchEngine.evaluate(slot.spec, FileFacts.of(inspected, slot.kind)).verdict
            return InkReviewResult.Ready(
                bytes = bytes,
                kb = (bytes.size + KB / 2) / KB,
                width = inspected.width ?: 0,
                height = inspected.height ?: 0,
                meetsRules = verdict == Verdict.EXACT || verdict == Verdict.ACCEPTED,
                quality = quality,
            )
        }
    }
}
