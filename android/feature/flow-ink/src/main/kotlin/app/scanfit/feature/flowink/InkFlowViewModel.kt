package app.scanfit.feature.flowink

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.data.UserPreferences
import app.scanfit.core.data.draft.DraftStore
import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.data.export.ExportRequest
import app.scanfit.core.data.export.ExportVerifier
import app.scanfit.core.data.export.SaveResult
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.data.export.ShareStager
import app.scanfit.core.data.kit.KitIds
import app.scanfit.core.data.kit.KitSave
import app.scanfit.core.data.kit.KitStore
import app.scanfit.core.data.quota.PaywallTrigger
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
import app.scanfit.core.imaging.Resampler
import app.scanfit.core.inspect.Inspector
import app.scanfit.core.match.DocKind
import app.scanfit.core.match.FileFacts
import app.scanfit.core.match.MatchEngine
import app.scanfit.core.match.MatchNote
import app.scanfit.core.match.ReviewChecks
import app.scanfit.core.match.Verdict
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.Exam
import app.scanfit.core.model.FlowTarget
import app.scanfit.core.presets.PresetsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
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
    /** A Custom size (ALGORITHMS 11): no draft, no Saved status, no handwriting tick, "your size" verdicts. */
    val custom: Boolean = false,
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

    /** Exam signatures need the one-time "running handwriting" confirmation before saving (§9.5, §11). */
    val needsHandwritingConfirmation: Boolean get() = kind == DocKind.SIGNATURE && !custom

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
        /** Which other exams accept these bytes (ALGORITHMS 4 "Match note on review"). */
        val note: MatchNote = MatchNote.EMPTY,
        val checks: ReviewChecks = ReviewChecks(),
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
        val before: Raster? = null,
        val restored: Boolean = false,
        val retainFailed: Boolean = false,
        /** A staged file for the share sheet (ALGORITHMS 1.6.2): the screen opens it once, then `onShareHandled`. */
        val shareUri: String? = null,
        val shareFailed: Boolean = false,
        /** Save to My Kit for the picture this review was made from (ALGORITHMS 13.1); hidden when [restored]. */
        val kit: KitSave = KitSave.IDLE,
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
@Suppress("TooManyFunctions")
class InkFlowViewModel
@Inject
constructor(
    savedState: SavedStateHandle,
    private val presets: PresetsRepository,
    private val tools: InkTools,
    private val preferences: UserPreferences,
    private val exporter: DocumentExporter,
    @InkWork private val work: CoroutineDispatcher,
    private val drafts: DraftStore,
    private val share: ShareStager,
    private val kit: KitStore,
) : ViewModel() {
    private val target: FlowTarget? =
        FlowTarget.of(savedState.get<String>(EXAM_ID), savedState.get<String>(DOC_TYPE), savedState.get<String>(CUSTOM))

    /** "Use for exam…" from My Kit (ALGORITHMS 13.2): the flow starts from this original instead of the picker. */
    private val originalId: String? = savedState.get<String>(ORIGINAL)?.takeIf(KitIds::isValid)
    private var originalOpened = false

    /** Counts the pictures the flow has opened, so a late Save to My Kit never lands on a newer picture. */
    private var sourceGeneration = 0L
    private var kitSave = KitSave.IDLE

    private val _uiState = MutableStateFlow<InkUiState>(InkUiState.Loading)
    val uiState: StateFlow<InkUiState> = _uiState.asStateFlow()

    private var job: Job? = null
    private var renderJob: Job? = null
    private var renderVersion = 0
    private var lastCrop: InkUiState.Crop? = null
    private var cropped: Raster? = null
    private var pendingSave: ExportRequest? = null
    private var exams: List<Exam> = emptyList()
    private var popular: List<String> = emptyList()

    init {
        viewModelScope.launch {
            combine(presets.outcome, presets.bundle) { outcome, bundle -> outcome to bundle }
                .collectLatest { (outcome, bundle) ->
                    // A Custom size needs no preset, only the exams for its match note, so it opens without them.
                    if (bundle != null || (outcome != null && target is FlowTarget.Custom)) {
                        refreshDraft()
                    } else if (outcome != null) {
                        _uiState.value = InkUiState.NotFound
                    }
                }
        }
        viewModelScope.launch {
            drafts.revisions.drop(1).collectLatest { refreshDraft() }
        }
    }

    fun refreshDraft() {
        val target = target
        val bundle = presets.bundle.value
        if (target == null) {
            _uiState.value = InkUiState.NotFound
            return
        }
        if (bundle == null && target !is FlowTarget.Custom) return
        val resolved = target.resolve(bundle)
        val spec = resolved?.spec
        if ((_uiState.value as? InkUiState.Review)?.save?.state == SaveState.SAVING) return
        if (slot?.spec != null && slot?.spec != spec) {
            job?.cancel()
            renderJob?.cancel()
            renderVersion++
            lastCrop = null
            _uiState.value = InkUiState.Loading
        }
        val previous = _uiState.value
        if (previous != InkUiState.Loading && previous != InkUiState.NotFound && previous !is InkUiState.PickSource &&
            (previous as? InkUiState.Review)?.restored != true
        ) {
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            if (resolved == null || DocKind.of(resolved.spec.type) in setOf(DocKind.PHOTO, DocKind.PDF_DOCUMENT)) {
                _uiState.value = InkUiState.NotFound
                return@launch
            }
            val slot = InkSlot(resolved.examId, resolved.examName, resolved.unverified, resolved.spec, resolved.custom)
            exams = bundle?.exams.orEmpty()
            popular = bundle?.popular.orEmpty()
            if (originalId != null && !originalOpened) {
                originalOpened = true
                if (_uiState.value === previous) open(slot, fromKit = true) { kit.original(originalId) }
                return@launch
            }
            val draft = if (slot.custom) null else drafts.read(slot.examId, slot.spec, slot.kind)
            val next = if (draft == null || previous is InkUiState.PickSource) {
                InkUiState.PickSource(slot)
            } else {
                restored(slot, draft.bytes)
            }
            if (_uiState.value === previous && presets.bundle.value == bundle) _uiState.value = next
        }
    }

    private suspend fun restored(slot: InkSlot, bytes: ByteArray): InkUiState.Review {
        val confirmed = preferences.handwritingConfirmed.first()
        return withContext(work) {
            InkUiState.Review(
                slot,
                InkReviewOptions(crispBlack = false),
                ready(bytes, slot, InkQuality.OK, exams, popular),
                confirmed,
                restored = true,
            )
        }
    }

    fun onReplace() {
        val review = _uiState.value as? InkUiState.Review ?: return
        if (!review.restored || review.save.state == SaveState.SAVING) return
        job?.cancel()
        _uiState.value = InkUiState.PickSource(review.slot)
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
        open(slot, fromKit = false) { tools.read(uri).also { tools.discardCaptures() } }
    }

    /** Decodes a new working picture (ALGORITHMS 1.1) for the crop; [fromKit] = it is already a Kit original. */
    private fun open(
        slot: InkSlot,
        fromKit: Boolean,
        read: suspend () -> ByteArray?,
    ) {
        renderJob?.cancel()
        renderVersion++
        job?.cancel()
        sourceGeneration++
        kitSave = if (fromKit) KitSave.SAVED else KitSave.IDLE
        _uiState.value = InkUiState.Opening(slot)
        job =
            viewModelScope.launch {
                val raster =
                    withContext(work) {
                        read()?.let { tools.decode(it, decodeCap(slot.spec)) }
                    }
                _uiState.value =
                    if (raster == null) {
                        InkUiState.PickSource(slot, openFailed = true)
                    } else {
                        InkUiState.Crop(slot, raster, wholeImage(raster))
                    }
            }
    }

    /** What Save to My Kit would keep (ALGORITHMS 13.3 previews): the working source, downsampled for display only. */
    suspend fun originalPreview(): Raster? {
        val source = lastCrop?.image ?: return null
        return withContext(work) {
            val scale = PREVIEW_LONG_SIDE.toDouble() / maxOf(source.width, source.height)
            if (scale >= 1) {
                source
            } else {
                val width = maxOf(1, (source.width * scale).toInt())
                Resampler.resize(source, width, maxOf(1, (source.height * scale).toInt()))
            }
        }
    }

    /** Keeps the picture this review was made from in My Kit (ALGORITHMS 13.1). The review itself never changes. */
    fun onSaveOriginal() {
        val review = _uiState.value as? InkUiState.Review ?: return
        val source = lastCrop?.image ?: return
        if (review.restored || kitSave == KitSave.SAVING || kitSave == KitSave.SAVED) return
        val generation = sourceGeneration
        showKit(KitSave.SAVING)
        viewModelScope.launch {
            val jpeg = withContext(work) { encodeOriginal(source) }
            val result = if (jpeg == null) KitSave.FAILED else KitSave.of(kit.saveOriginal(review.slot.kind, jpeg))
            if (generation == sourceGeneration) showKit(result)
        }
    }

    @Suppress("TooGenericExceptionCaught") // an encoder failure must not crash the review
    private fun encodeOriginal(source: Raster): ByteArray? = try {
        tools.encodeOriginal(source)
    } catch (_: Exception) {
        null
    }

    private fun showKit(state: KitSave) {
        kitSave = state
        val review = _uiState.value as? InkUiState.Review ?: return
        _uiState.value = review.copy(kit = state)
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
        if (!exporter.gate.allows(listOf(ready.bytes), PaywallTrigger.SAVE)) return null
        val slot = review.slot
        val request =
            ExportRequest(slot.examId, slot.examName, slot.spec, slot.kind, ready.bytes, recordsSaved = !slot.custom)
        _uiState.value = review.copy(save = SaveResult(SaveState.SAVING))
        if (exporter.destinations.requiresPicker) {
            pendingSave = request
            return request.fileName
        }
        export(request, null)
        return null
    }

    /**
     * Shares exactly the review bytes under the §9.8 name (ALGORITHMS 1.6.2). Allowed whenever Save is; it never
     * records Saved, because the destination cannot be re-read.
     */
    fun onShare() {
        val review = _uiState.value as? InkUiState.Review ?: return
        val ready = review.result as? InkReviewResult.Ready ?: return
        if (!review.canSave) return
        if (!exporter.gate.allows(listOf(ready.bytes), PaywallTrigger.SHARE)) return
        val slot = review.slot
        val name = ExportRequest(slot.examId, slot.examName, slot.spec, slot.kind, ready.bytes).fileName
        viewModelScope.launch {
            val uri = share.stage(name, ready.bytes)
            if (uri != null) withContext(work) { exporter.gate.record(ready.bytes) }
            val current = _uiState.value as? InkUiState.Review ?: return@launch
            if (current.result !== ready) return@launch
            _uiState.value = current.copy(shareUri = uri, shareFailed = uri == null)
        }
    }

    /** The share sheet was opened (or the failure message read). */
    fun onShareHandled() {
        val review = _uiState.value as? InkUiState.Review ?: return
        _uiState.value = review.copy(shareUri = null)
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
        if (review.save.state == SaveState.SAVING || review.restored || !review.slot.hasInkOptions) return
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
        _uiState.value =
            InkUiState.Review(slot, options, shown, confirmed, rendering = true, before = source, kit = kitSave)
        renderJob =
            viewModelScope.launch {
                if (debounce) {
                    delay(SLIDER_DEBOUNCE_MS)
                    if (version != renderVersion) return@launch
                    _uiState.value =
                        InkUiState.Review(
                            slot,
                            options,
                            working,
                            confirmed,
                            rendering = true,
                            before = source,
                            kit = kitSave,
                        )
                }
                val ink = InkOptions(crispBlack = options.crispBlack, inkFactor = options.inkFactor)
                val result =
                    withContext(work) {
                        when (val outcome = tools.fit(source, slot.spec, slot.pipeline, ink)) {
                            is PipelineOutcome.Failure -> InkReviewResult.Failed(outcome.error)

                            is PipelineOutcome.Success -> {
                                val quality = outcome.result.ink?.quality ?: InkQuality.OK
                                ready(outcome.result.fit.bytes, slot, quality, exams, popular)
                            }
                        }
                    }
                if (version != renderVersion) return@launch
                // The preset changed while fitting: this output was made for rules that no longer apply.
                if (target?.resolve(presets.bundle.value)?.spec != slot.spec) return@launch
                val retained = if (result is InkReviewResult.Ready && result.meetsRules && !slot.custom) {
                    try {
                        drafts.retain(slot.examId, slot.spec, slot.kind, result.bytes)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    true
                }
                if (version != renderVersion) return@launch
                val current = _uiState.value as? InkUiState.Review
                _uiState.value = InkUiState.Review(
                    slot,
                    options,
                    result,
                    current?.handwritingConfirmed ?: confirmed,
                    before = source,
                    retainFailed = !retained,
                    kit = kitSave,
                )
            }
    }

    companion object {
        private const val PREVIEW_LONG_SIDE = 720

        /** Navigation arguments: `exam/{examId}/ink/{docType}` or `custom/ink?custom={text}` (ALGORITHMS 11). */
        const val EXAM_ID = FlowTarget.EXAM_ID
        const val DOC_TYPE = FlowTarget.DOC_TYPE
        const val CUSTOM = FlowTarget.CUSTOM

        /** Optional `?original={id}`: a My Kit original to start from (ALGORITHMS 13.2). */
        const val ORIGINAL = KitIds.ROUTE_ARGUMENT

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
            exams: List<Exam>,
            popular: List<String>,
        ): InkReviewResult.Ready {
            val inspected = Inspector.inspect(bytes)
            val facts = FileFacts.of(inspected, slot.kind)
            val evaluation = MatchEngine.evaluate(slot.spec, facts)
            val verdict = evaluation.verdict
            return InkReviewResult.Ready(
                bytes = bytes,
                kb = (bytes.size + KB / 2) / KB,
                width = inspected.width ?: 0,
                height = inspected.height ?: 0,
                meetsRules = (verdict == Verdict.EXACT || verdict == Verdict.ACCEPTED) &&
                    ExportVerifier.verifies(bytes, bytes, slot.spec, slot.kind),
                quality = quality,
                note = MatchNote.of(facts, exams, popular),
                checks = ReviewChecks.of(evaluation),
            )
        }
    }
}
