package app.scanfit.feature.flowphoto

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.data.export.SaveResult
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.imaging.AndroidImageDecoder
import app.scanfit.core.imaging.AutoFraming
import app.scanfit.core.imaging.BackgroundWhitening
import app.scanfit.core.imaging.CropAdjust
import app.scanfit.core.imaging.CropRect
import app.scanfit.core.imaging.FitError
import app.scanfit.core.imaging.Geometry
import app.scanfit.core.imaging.PipelineOutcome
import app.scanfit.core.imaging.Raster
import app.scanfit.core.inspect.Inspector
import app.scanfit.core.match.DocKind
import app.scanfit.core.match.FileFacts
import app.scanfit.core.match.MatchEngine
import app.scanfit.core.match.Verdict
import app.scanfit.core.model.DocSpec
import app.scanfit.core.presets.PresetsRepository
import app.scanfit.core.vision.FaceBox
import app.scanfit.core.vision.FaceCheck
import app.scanfit.core.vision.FaceDetector
import app.scanfit.core.vision.PersonSegmenter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** The exam slot the photo is for. [unverified] = low-confidence preset: never "meets the rules" (CLAUDE.md rule 6). */
data class PhotoSlot(
    val examId: String,
    val examName: String,
    val unverified: Boolean,
    val spec: DocSpec,
) {
    /** The aspect the crop is locked to (ALGORITHMS 9.6); a free-ratio slot keeps the photo's own shape. */
    val aspect: Double? get() = Geometry.targetAspect(spec)

    /** `T` of ALGORITHMS 9.4 in whole KB, for "Fitting to 38 KB"; `null` when the slot has no maximum. */
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

enum class SourceProblem { OPEN_FAILED, NO_FACE, FAILED }

enum class CropProblem { NO_FACE, SEVERAL_FACES, FAILED }

data class PhotoOptions(
    val whiteBackground: Boolean = false,
    /** `false` once the segmenter said it cannot run on this device. */
    val whiteBackgroundAvailable: Boolean = true,
    val nameDate: Boolean = false,
    val name: String = "",
    val date: String = "",
)

sealed interface ReviewResult {
    data class Working(
        val targetKb: Int?,
    ) : ReviewResult

    class Ready(
        /** The fitted JPEG, exactly as it would be written. */
        val bytes: ByteArray,
        val kb: Int,
        val width: Int,
        val height: Int,
        /** EXACT or ACCEPTED for this slot (ALGORITHMS 9.7), checked on the re-inspected bytes. */
        val meetsRules: Boolean,
    ) : ReviewResult

    data class Failed(
        val error: FitError,
    ) : ReviewResult
}

/** The photo flow's steps (UI_UX §3 Capture/crop, Review). Immutable: the single source of truth for the screen. */
sealed interface PhotoUiState {
    data object Loading : PhotoUiState

    /** The exam or its photo slot is not in the trusted presets. */
    data object NotFound : PhotoUiState

    data class PickSource(
        val slot: PhotoSlot,
        val problem: SourceProblem? = null,
    ) : PhotoUiState

    data class FindingFace(
        val slot: PhotoSlot,
    ) : PhotoUiState

    data class Crop(
        val slot: PhotoSlot,
        val image: Raster,
        val rect: CropRect,
        /** The face is too large for the target coverage (ALGORITHMS 9.6 `coverage_adjusted`). */
        val tight: Boolean,
        val problem: CropProblem? = null,
        /** The final face check on the crop is running. */
        val checking: Boolean = false,
    ) : PhotoUiState

    data class Review(
        val slot: PhotoSlot,
        val options: PhotoOptions,
        val result: ReviewResult,
        val save: SaveResult = SaveResult(),
        val rendering: Boolean = false,
    ) : PhotoUiState
}

@HiltViewModel
class PhotoFlowViewModel
@Inject
constructor(
    savedState: SavedStateHandle,
    presets: PresetsRepository,
    private val tools: PhotoTools,
    private val faces: FaceDetector,
    private val segmenter: PersonSegmenter,
    @PhotoWork private val work: CoroutineDispatcher,
    exporter: DocumentExporter,
) : ViewModel() {
    private val examId: String = savedState.get<String>(EXAM_ID).orEmpty()
    private val docType: String = savedState.get<String>(DOC_TYPE).orEmpty()

    private val _uiState = MutableStateFlow<PhotoUiState>(PhotoUiState.Loading)
    val uiState: StateFlow<PhotoUiState> = _uiState.asStateFlow()
    internal val saves = PhotoSaveController(_uiState, viewModelScope, work, exporter)

    private var job: Job? = null
    private var renderJob: Job? = null
    private var lastCrop: PhotoUiState.Crop? = null
    private var cropped: Raster? = null
    private var mask: ByteArray? = null
    private var maskTried = false
    private var renderVersion = 0L

    init {
        viewModelScope.launch {
            val bundle =
                combine(presets.outcome, presets.bundle) { outcome, bundle -> outcome to bundle }
                    .first { (outcome, bundle) -> outcome != null || bundle != null }
                    .second
            val exam = bundle?.exams?.firstOrNull { it.id == examId }
            val spec = exam?.documents?.firstOrNull { it.type.name.lowercase() == docType }
            _uiState.value =
                if (exam == null || spec == null) {
                    PhotoUiState.NotFound
                } else {
                    PhotoUiState.PickSource(PhotoSlot(exam.id, exam.name, exam.isUnverified, spec))
                }
        }
    }

    private val slot: PhotoSlot?
        get() =
            when (val s = _uiState.value) {
                is PhotoUiState.PickSource -> s.slot
                is PhotoUiState.FindingFace -> s.slot
                is PhotoUiState.Crop -> s.slot
                is PhotoUiState.Review -> s.slot
                else -> null
            }

    /** A URI for the camera app to write into (the UI launches `TakePicture` with it). */
    fun newCaptureUri(): String = tools.newCaptureUri()

    fun onImageSelected(uri: String) {
        if ((_uiState.value as? PhotoUiState.Review)?.save?.state == SaveState.SAVING) return
        val slot = slot ?: return
        job?.cancel()
        _uiState.value = PhotoUiState.FindingFace(slot)
        job =
            viewModelScope.launch {
                val raster =
                    withContext(work) {
                        val bytes = tools.read(uri)
                        tools.discardCaptures()
                        bytes?.let { tools.decode(it, decodeCap(slot.spec)) }
                    }
                if (raster == null) {
                    _uiState.value = PhotoUiState.PickSource(slot, SourceProblem.OPEN_FAILED)
                    return@launch
                }
                val found = detect(raster)
                _uiState.value =
                    when {
                        found == null -> PhotoUiState.PickSource(slot, SourceProblem.FAILED)
                        found.isEmpty() -> PhotoUiState.PickSource(slot, SourceProblem.NO_FACE)
                        else -> framed(slot, raster, found)
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

    fun onZoom(factor: Double) = updateCrop {
        val aspect = it.slot.aspect ?: (it.rect.w.toDouble() / it.rect.h)
        CropAdjust.zoom(it.rect, factor, aspect, it.image.width, it.image.height)
    }

    fun onRotate() {
        val crop = _uiState.value as? PhotoUiState.Crop ?: return
        job?.cancel()
        job =
            viewModelScope.launch {
                val rotated = withContext(work) { CropAdjust.rotateClockwise(crop.image) }
                val found = detect(rotated) ?: emptyList()
                _uiState.value = framed(crop.slot, rotated, found)
            }
    }

    /** Back to the automatic framing for the current image. */
    fun onResetCrop() {
        val crop = _uiState.value as? PhotoUiState.Crop ?: return
        job?.cancel()
        job =
            viewModelScope.launch {
                _uiState.value = framed(crop.slot, crop.image, detect(crop.image) ?: emptyList())
            }
    }

    /** The crop must hold exactly one face (ALGORITHMS 9.6 face count) before the photo is fitted. */
    fun onCropDone() {
        val crop = _uiState.value as? PhotoUiState.Crop ?: return
        if (crop.checking) return
        job?.cancel()
        _uiState.value = crop.copy(checking = true)
        job =
            viewModelScope.launch {
                val rect = crop.rect
                val image = withContext(work) { crop.image.crop(rect.x, rect.y, rect.w, rect.h) }
                val found = detect(image)
                val problem =
                    when (found?.let { FaceCheck.of(it) }) {
                        null -> CropProblem.FAILED
                        FaceCheck.NoFace -> CropProblem.NO_FACE
                        is FaceCheck.Several -> CropProblem.SEVERAL_FACES
                        is FaceCheck.Single -> null
                    }
                if (problem != null) {
                    _uiState.value = crop.copy(problem = problem, checking = false)
                    return@launch
                }
                lastCrop = crop.copy(problem = null, checking = false)
                cropped = image
                mask = null
                maskTried = false
                // The strip is off until the user turns it on (ALGORITHMS 2.4); a preset that asks for it shows a hint.
                render(crop.slot, PhotoOptions(date = tools.today().format(DATE_FORMAT)))
            }
    }

    fun onWhiteBackground(on: Boolean) = updateOptions(debounce = false) { it.copy(whiteBackground = on) }

    fun onNameDate(on: Boolean) = updateOptions(debounce = false) { it.copy(nameDate = on) }

    fun onName(name: String) = updateOptions(debounce = true) { it.copy(name = name.take(MAX_NAME)) }

    fun onDate(date: String) = updateOptions(debounce = true) { it.copy(date = date.take(MAX_DATE)) }

    /** System back inside the flow: Review -> Crop -> PickSource. `false` = leave the flow. */
    fun onBack(): Boolean {
        if ((_uiState.value as? PhotoUiState.Review)?.save?.state == SaveState.SAVING) return true
        val slot = slot ?: return false
        return when (_uiState.value) {
            is PhotoUiState.Review -> {
                renderJob?.cancel()
                renderVersion++
                _uiState.value = lastCrop ?: PhotoUiState.PickSource(slot)
                true
            }

            is PhotoUiState.Crop, is PhotoUiState.FindingFace -> {
                job?.cancel()
                _uiState.value = PhotoUiState.PickSource(slot)
                true
            }

            else -> false
        }
    }

    private fun updateCrop(change: (PhotoUiState.Crop) -> CropRect) {
        val crop = _uiState.value as? PhotoUiState.Crop ?: return
        if (crop.checking) return
        _uiState.value = crop.copy(rect = change(crop), problem = null)
    }

    private fun updateOptions(
        debounce: Boolean,
        change: (PhotoOptions) -> PhotoOptions,
    ) {
        val review = _uiState.value as? PhotoUiState.Review ?: return
        if (review.save.state == SaveState.SAVING) return
        render(review.slot, change(review.options), debounce)
    }

    /** Whitening -> strip -> fit -> re-inspect (ALGORITHMS 9.6 order). A newer change cancels the running one. */
    private fun render(
        slot: PhotoSlot,
        options: PhotoOptions,
        debounce: Boolean = false,
    ) {
        val source = cropped ?: return
        renderJob?.cancel()
        val version = ++renderVersion
        val working = ReviewResult.Working(slot.targetKb)
        // While typing, the last result stays on screen until the debounce has passed (no flashing per key).
        val shown = (_uiState.value as? PhotoUiState.Review)?.result?.takeIf { debounce } ?: working
        _uiState.value = PhotoUiState.Review(slot, options, shown, rendering = true)
        renderJob =
            viewModelScope.launch {
                if (debounce) {
                    delay(TYPING_DEBOUNCE_MS)
                    if (version != renderVersion) return@launch
                    _uiState.value = PhotoUiState.Review(slot, options, working, rendering = true)
                }
                var applied = options
                var image = source
                if (options.whiteBackground) {
                    val m = personMask(source)
                    if (m == null) {
                        applied = applied.copy(whiteBackground = false, whiteBackgroundAvailable = false)
                    } else {
                        image = withContext(work) { BackgroundWhitening.composite(source, m) }
                    }
                }
                val result =
                    withContext(work) {
                        val prepared =
                            if (applied.nameDate) {
                                tools.drawStrip(image, applied.name.trim(), stripDate(applied))
                            } else {
                                image
                            }
                        when (val outcome = tools.fit(prepared, slot.spec)) {
                            is PipelineOutcome.Failure -> ReviewResult.Failed(outcome.error)
                            is PipelineOutcome.Success -> ready(outcome.result.fit.bytes, slot.spec)
                        }
                    }
                if (version == renderVersion) _uiState.value = PhotoUiState.Review(slot, applied, result)
            }
    }

    private suspend fun personMask(source: Raster): ByteArray? {
        if (!maskTried) {
            maskTried = true
            mask = attempt { withContext(work) { segmenter.mask(source) } }
        }
        return mask
    }

    private fun stripDate(options: PhotoOptions) = options.date.trim().ifEmpty { tools.today().format(DATE_FORMAT) }

    private suspend fun detect(raster: Raster): List<FaceBox>? = attempt { withContext(work) { faces.detect(raster) } }

    /**
     * ML engines can fail with any runtime exception (a missing model, a GPU delegate error). The flow then offers a
     * retry instead of crashing; cancellation still propagates.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Navigation arguments: `exam/{examId}/photo/{docType}` (docType = preset wire name, e.g. `photo`). */
        const val EXAM_ID = "examId"
        const val DOC_TYPE = "docType"

        private const val TYPING_DEBOUNCE_MS = 400L
        private const val MAX_NAME = 60
        private const val MAX_DATE = 10
        private const val KB = 1024
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        /** ALGORITHMS 1.1: decode no larger than 2x the largest target dimension (at least 1600 px). */
        private fun decodeCap(spec: DocSpec): Int {
            val d = spec.dimensions
            val largest = listOfNotNull(d.width, d.height, d.maxW, d.maxH).maxOrNull() ?: 0
            return AndroidImageDecoder.longSideCap(largest)
        }

        /** Auto-framing for one face (ALGORITHMS 9.6); else the default crop, and a re-crop ask for several faces. */
        private fun framed(
            slot: PhotoSlot,
            image: Raster,
            found: List<FaceBox>,
        ): PhotoUiState.Crop {
            val aspect = slot.aspect ?: (image.width.toDouble() / image.height)
            return when (val check = FaceCheck.of(found)) {
                is FaceCheck.Single -> {
                    val f = AutoFraming.frame(check.face, image.width, image.height, aspect)
                    PhotoUiState.Crop(slot, image, f.crop, tight = f.coverageAdjusted)
                }

                else -> {
                    val rect = Geometry.defaultCrop(image.width, image.height, aspect)
                    val problem = if (check is FaceCheck.Several) CropProblem.SEVERAL_FACES else null
                    PhotoUiState.Crop(slot, image, rect, tight = false, problem = problem)
                }
            }
        }

        /** Re-inspects the fitted bytes and evaluates them against the slot, as the export will be (rule 3). */
        private fun ready(
            bytes: ByteArray,
            spec: DocSpec,
        ): ReviewResult.Ready {
            val inspected = Inspector.inspect(bytes)
            val verdict = MatchEngine.evaluate(spec, FileFacts.of(inspected, DocKind.PHOTO)).verdict
            return ReviewResult.Ready(
                bytes = bytes,
                kb = (bytes.size + KB / 2) / KB,
                width = inspected.width ?: 0,
                height = inspected.height ?: 0,
                meetsRules = verdict == Verdict.EXACT || verdict == Verdict.ACCEPTED,
            )
        }
    }
}
