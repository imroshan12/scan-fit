package app.scanfit.feature.pdf

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.data.export.ExportRequest
import app.scanfit.core.data.export.ExportVerifier
import app.scanfit.core.data.export.PdfExport
import app.scanfit.core.data.export.SaveResult
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.data.export.ShareStager
import app.scanfit.core.imaging.CropAdjust
import app.scanfit.core.imaging.CropCorner
import app.scanfit.core.imaging.CropRect
import app.scanfit.core.imaging.Raster
import app.scanfit.core.imaging.Resampler
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DimensionMode
import app.scanfit.core.model.Dimensions
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.DocType
import app.scanfit.core.model.FileFormat
import app.scanfit.core.model.FlowTarget
import app.scanfit.core.model.SizeKb
import app.scanfit.core.pdf.PdfError
import app.scanfit.core.pdf.PdfFit
import app.scanfit.core.pdf.PdfJob
import app.scanfit.core.pdf.PdfOptions
import app.scanfit.core.pdf.PdfOutcome
import app.scanfit.core.pdf.PdfProgress
import app.scanfit.core.pdf.PdfSource
import app.scanfit.core.pdf.PdfStrategy
import app.scanfit.core.pdf.PdfTool
import app.scanfit.core.pdf.PixelSize
import app.scanfit.core.presets.PresetsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the PDF is made for: an exam's PDF slot (records Saved) or a tool's synthetic slot (ALGORITHMS 6.6). */
data class PdfTarget(
    val examId: String,
    val examName: String,
    val spec: DocSpec,
    /** Low-confidence preset: never "meets the rules" (CLAUDE.md rule 6). */
    val unverified: Boolean,
    val exam: Boolean,
)

/** One added file: a picture (one page) or a PDF (its pages). The bytes stay in the view model. */
data class PdfItem(
    val id: Long,
    val document: Boolean,
    val pages: Int,
    val kb: Int,
    val thumbnail: Raster?,
    val quarterTurns: Int = 0,
    val crop: CropRect? = null,
    val cropBase: PixelSize? = null,
)

/** Cropping one picture: the upright, turned picture and the free crop on it (ALGORITHMS 9.5 free crop). */
data class PdfCrop(
    val itemId: Long,
    val image: Raster,
    val rect: CropRect,
)

/** Why a file was not added. */
enum class AddFailure { UNREADABLE, ENCRYPTED, TOO_MANY_PAGES }

sealed interface PdfResult {
    class Ready(
        /** The PDF exactly as it will be written. */
        val bytes: ByteArray,
        val kb: Int,
        val pages: Int,
        val dpi: Int?,
        val strategy: PdfStrategy,
        val preview: Raster?,
        /** Passes the §6.5 check against the target (and its verdict is EXACT or ACCEPTED). */
        val meetsRules: Boolean,
    ) : PdfResult

    data class Failed(
        val error: PdfError,
    ) : PdfResult
}

data class PdfMakerUiState(
    val tool: PdfTool,
    val target: PdfTarget? = null,
    /** An exam slot that is not (or no longer) in the trusted presets. */
    val notFound: Boolean = false,
    val items: List<PdfItem> = emptyList(),
    val adding: Boolean = false,
    val addFailure: AddFailure? = null,
    val options: PdfOptions = PdfOptions(),
    val maxKbText: String = DEFAULT_MAX_KB.toString(),
    val crop: PdfCrop? = null,
    /** Non-null while the PDF is being made. */
    val making: PdfProgress? = null,
    val result: PdfResult? = null,
    val save: SaveResult = SaveResult(),
    val shareUri: String? = null,
    val shareFailed: Boolean = false,
) {
    /** Tools ask for Max KB, 1–5120 (ALGORITHMS 6.1); an exam slot has its own window. */
    val maxKb: Int? get() = maxKbText.toIntOrNull()?.takeIf { it in 1..MAX_KB }

    val pages: Int get() = if (tool == PdfTool.ID_CARD) minOf(1, items.size) else items.sumOf { it.pages }

    val hasPictures: Boolean get() = items.any { !it.document }

    val busy: Boolean get() = adding || making != null || save.state == SaveState.SAVING

    /** What is missing before Make PDF, per tool (ALGORITHMS 6.1); `null` = ready. */
    val missing: Missing?
        get() =
            when {
                tool != PdfTool.EXAM && maxKb == null -> Missing.MAX_KB
                target == null -> Missing.TARGET
                tool == PdfTool.ID_CARD && items.size < 2 -> Missing.BOTH_SIDES
                tool == PdfTool.MERGE && items.size < 2 -> Missing.TWO_PDFS
                items.isEmpty() -> Missing.FILES
                else -> null
            }

    val canMake: Boolean get() = missing == null && !busy && result == null && crop == null

    val canSave: Boolean
        get() = (result as? PdfResult.Ready)?.meetsRules == true && save.state != SaveState.SAVING

    enum class Missing { TARGET, FILES, TWO_PDFS, BOTH_SIDES, MAX_KB }

    companion object {
        const val DEFAULT_MAX_KB = 200
        const val MAX_KB = 5120
        val QUICK_MAX_KB = listOf(100, 200, 300, 500)
    }
}

/** The PDF tools and an exam slot's PDF maker (ALGORITHMS 6, UI_UX §3): one screen, configured by the route. */
@HiltViewModel
@Suppress("TooManyFunctions")
class PdfMakerViewModel
@Inject
constructor(
    savedState: SavedStateHandle,
    private val presets: PresetsRepository,
    private val tools: PdfMakerTools,
    private val exporter: DocumentExporter,
    private val share: ShareStager,
    @PdfWork private val work: CoroutineDispatcher,
) : ViewModel() {
    private val examId: String? = savedState[EXAM_ID]
    private val docType: String? = savedState[DOC_TYPE]
    private val tool: PdfTool =
        if (examId != null) PdfTool.EXAM else PdfTool.of(savedState.get<String>(TOOL).orEmpty()) ?: PdfTool.IMAGES

    private val _uiState = MutableStateFlow(PdfMakerUiState(tool))
    val uiState: StateFlow<PdfMakerUiState> = _uiState.asStateFlow()

    private val bytes = mutableMapOf<Long, ByteArray>()
    private var nextId = 1L
    private var job: Job? = null
    private var pendingCapture: String? = null
    private var pendingCaptureSide: Int? = null
    private var pendingSave: ExportRequest? = null

    init {
        if (examId == null) {
            _uiState.update { it.copy(target = toolTarget(tool, it.maxKb)) }
        } else {
            viewModelScope.launch {
                val bundle = presets.bundle.first { it != null || presets.outcome.value != null }
                val slot = FlowTarget.ExamSlot(examId, docType.orEmpty()).resolve(bundle)?.takeIf { FileFormat.PDF in it.spec.formats }
                _uiState.update {
                    if (slot == null) {
                        it.copy(notFound = true)
                    } else {
                        it.copy(target = PdfTarget(slot.examId, slot.examName, slot.spec, slot.unverified, exam = true))
                    }
                }
            }
        }
    }

    /** Files picked from the gallery or Files; [side] is the ID card side (0 front, 1 back) to fill. */
    fun onAdd(
        uris: List<String>,
        side: Int? = null,
    ) = addFiles(uris, side) {}

    private fun addFiles(
        uris: List<String>,
        side: Int?,
        afterwards: suspend () -> Unit,
    ) {
        if (uris.isEmpty() || _uiState.value.busy) return
        _uiState.update { it.copy(adding = true, addFailure = null) }
        viewModelScope.launch {
            var failure: AddFailure? = null
            for (uri in uris) {
                val read = withContext(work) { tools.read(uri)?.let { it to tools.describe(it) } }
                val (data, info) = read ?: (null to null)
                failure =
                    when {
                        data == null || info == null -> AddFailure.UNREADABLE
                        info.encrypted -> AddFailure.ENCRYPTED
                        !add(data, info, side) -> AddFailure.TOO_MANY_PAGES
                        else -> null
                    } ?: failure
                if (failure == AddFailure.TOO_MANY_PAGES) break
            }
            afterwards()
            _uiState.update { it.copy(adding = false, addFailure = failure) }
        }
    }

    /** A capture URI for the camera app, remembered until [onCaptured]. */
    fun onCaptureRequested(side: Int? = null): String? {
        if (_uiState.value.busy) return null
        pendingCaptureSide = side
        return tools.newCaptureUri().also { pendingCapture = it }
    }

    /** The camera app returned; the captured file lives on only as bytes in memory once read. */
    fun onCaptured(success: Boolean) {
        val uri = pendingCapture ?: return
        pendingCapture = null
        val discard: suspend () -> Unit = { withContext(work) { tools.discardCaptures() } }
        if (success && !_uiState.value.busy) {
            addFiles(listOf(uri), pendingCaptureSide, discard)
        } else {
            viewModelScope.launch { discard() }
        }
    }

    private fun add(
        data: ByteArray,
        info: PdfFileInfo,
        side: Int?,
    ): Boolean {
        val state = _uiState.value
        val item = PdfItem(nextId++, info.document, info.pages, (data.size + KB / 2) / KB, info.thumbnail)
        val items =
            when {
                state.tool == PdfTool.COMPRESS -> listOf(item)
                // ID card: replace the side when it is there, otherwise the next side (front, then back).
                state.tool == PdfTool.ID_CARD && side != null && side < state.items.size ->
                    state.items.toMutableList().also { it[side] = item }
                state.tool == PdfTool.ID_CARD && state.items.size >= 2 -> state.items.toMutableList().also { it[1] = item }
                else -> state.items + item
            }
        if (state.tool != PdfTool.ID_CARD && items.sumOf { it.pages } > PdfFit.MAX_PAGES) return false
        bytes[item.id] = data
        edit { it.copy(items = items) }
        bytes.keys.retainAll(items.map { it.id }.toSet())
        return true
    }

    fun onRemove(id: Long) = edit { state ->
        bytes.remove(id)
        state.copy(items = state.items.filterNot { it.id == id })
    }

    fun onMove(
        id: Long,
        by: Int,
    ) = edit { state ->
        val from = state.items.indexOfFirst { it.id == id }
        val to = from + by
        if (from < 0 || to !in state.items.indices) return@edit state
        state.copy(items = state.items.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** A quarter turn clockwise; the crop starts again (it was drawn on the unturned picture). */
    fun onTurn(id: Long) = edit { state ->
        state.copy(
            items =
            state.items.map {
                if (it.id != id || it.document) {
                    it
                } else {
                    it.copy(
                        quarterTurns = (it.quarterTurns + 1) % QUARTER_TURNS,
                        crop = null,
                        cropBase = null,
                        thumbnail = it.thumbnail?.let(CropAdjust::rotateClockwise),
                    )
                }
            },
        )
    }

    fun onCrop(id: Long) {
        val state = _uiState.value
        val item = state.items.firstOrNull { it.id == id && !it.document } ?: return
        val data = bytes[id] ?: return
        if (state.busy) return
        viewModelScope.launch {
            val image =
                withContext(work) {
                    var raster = tools.decode(data, CROP_CAP) ?: return@withContext null
                    repeat(item.quarterTurns) { raster = CropAdjust.rotateClockwise(raster) }
                    raster
                } ?: return@launch
            val rect = item.crop?.takeIf { item.cropBase == PixelSize(image.width, image.height) } ?: CropRect(0, 0, image.width, image.height)
            _uiState.update { it.copy(crop = PdfCrop(id, image, rect)) }
        }
    }

    fun onCropMove(
        dx: Double,
        dy: Double,
    ) = updateCrop { CropAdjust.move(it.rect, dx, dy, it.image.width, it.image.height) }

    fun onCropResize(
        corner: CropCorner,
        dx: Double,
        dy: Double,
    ) = updateCrop { CropAdjust.resize(it.rect, corner, dx, dy, it.image.width, it.image.height) }

    fun onCropReset() = updateCrop { CropRect(0, 0, it.image.width, it.image.height) }

    fun onCropCancel() = _uiState.update { it.copy(crop = null) }

    fun onCropDone() {
        val crop = _uiState.value.crop ?: return
        viewModelScope.launch {
            val thumbnail =
                withContext(work) {
                    val r = crop.rect
                    val cut = crop.image.crop(r.x, r.y, r.w, r.h)
                    val scale = THUMBNAIL.toDouble() / maxOf(cut.width, cut.height)
                    if (scale >= 1) cut else Resampler.resize(cut, maxOf(1, (cut.width * scale).toInt()), maxOf(1, (cut.height * scale).toInt()))
                }
            val whole = crop.rect == CropRect(0, 0, crop.image.width, crop.image.height)
            edit { state ->
                state.copy(
                    crop = null,
                    items =
                    state.items.map {
                        if (it.id != crop.itemId) {
                            it
                        } else {
                            it.copy(
                                crop = crop.rect.takeUnless { whole },
                                cropBase = PixelSize(crop.image.width, crop.image.height).takeUnless { whole },
                                thumbnail = thumbnail,
                            )
                        }
                    },
                )
            }
        }
    }

    fun onGrayscale(on: Boolean) = edit { it.copy(options = it.options.copy(grayscale = on)) }

    fun onCleanup(on: Boolean) = edit { it.copy(options = it.options.copy(cleanup = on)) }

    fun onMaxKb(text: String) = edit {
        val digits = text.filter(Char::isDigit).take(MAX_KB_DIGITS)
        val next = it.copy(maxKbText = digits)
        next.copy(target = if (it.tool == PdfTool.EXAM) it.target else toolTarget(it.tool, next.maxKb))
    }

    fun onDismissAddFailure() = _uiState.update { it.copy(addFailure = null) }

    /** Makes the PDF off the main thread with progress (ALGORITHMS 6.3); the result is checked like the export. */
    fun onMake() {
        val state = _uiState.value
        val target = state.target ?: return
        if (!state.canMake) return
        val sources =
            state.items.map { item ->
                val data = bytes.getValue(item.id)
                if (item.document) PdfSource.Document(data) else PdfSource.Image(data, item.quarterTurns, item.crop, item.cropBase)
            }
        val job = PdfJob(state.tool, sources, target.spec.sizeKb, state.options)
        _uiState.update { it.copy(making = PdfProgress.Preparing(0, maxOf(1, sources.size)), addFailure = null) }
        this.job =
            viewModelScope.launch {
                val outcome = withContext(work) { tools.make(job) { p -> _uiState.update { it.copy(making = p) } } }
                val result =
                    when (outcome) {
                        is PdfOutcome.Failed -> PdfResult.Failed(outcome.error)
                        is PdfOutcome.Made -> {
                            val pdf = outcome.pdf
                            val written = pdf.strategy != PdfStrategy.UNCHANGED
                            PdfResult.Ready(
                                pdf.bytes,
                                (pdf.bytes.size + KB / 2) / KB,
                                pdf.pages,
                                pdf.dpi,
                                pdf.strategy,
                                pdf.preview,
                                ExportVerifier.verifiesPdf(pdf.bytes, pdf.bytes, target.spec, PdfExport(pdf.pages, written)),
                            )
                        }
                    }
                _uiState.update { it.copy(making = null, result = result) }
            }
    }

    /** Back while making: stop, keep the files. */
    fun onCancelMaking() {
        if (_uiState.value.making == null) return
        job?.cancel()
        _uiState.update { it.copy(making = null) }
    }

    /** Back to editing from a result (the result is dropped; saving is never interrupted). */
    fun onEdit() = edit { it }

    /**
     * Saves the PDF (ALGORITHMS 6.5). Returns a file name when the UI must first let the user pick the destination
     * (Android 8–9), else `null`; the result arrives in `save`.
     */
    fun onSave(): String? {
        val state = _uiState.value
        val request = request(state) ?: return null
        if (!state.canSave) return null
        _uiState.update { it.copy(save = SaveResult(SaveState.SAVING)) }
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
        if (uri == null) {
            val next = if (launchFailed) SaveState.SAVE_FAILED else SaveState.IDLE
            _uiState.update { it.copy(save = SaveResult(next)) }
        } else {
            export(request, uri)
        }
    }

    /** Shares exactly the made PDF under its export name (ALGORITHMS 1.6.2); same gate as Save. */
    fun onShare() {
        val state = _uiState.value
        val request = request(state) ?: return
        if (!state.canSave) return
        val result = state.result
        viewModelScope.launch {
            val uri = share.stage(request.fileName, request.bytes)
            if (_uiState.value.result !== result) return@launch
            _uiState.update { it.copy(shareUri = uri, shareFailed = uri == null) }
        }
    }

    fun onShareHandled() = _uiState.update { it.copy(shareUri = null) }

    private fun request(state: PdfMakerUiState): ExportRequest? {
        val ready = state.result as? PdfResult.Ready ?: return null
        val target = state.target ?: return null
        val pdf = PdfExport(ready.pages, written = ready.strategy != PdfStrategy.UNCHANGED)
        return ExportRequest(target.examId, target.examName, target.spec, DocKind.PDF_DOCUMENT, ready.bytes, target.exam, pdf)
    }

    private fun export(
        request: ExportRequest,
        uri: String?,
    ) {
        viewModelScope.launch {
            val result = withContext(work) { exporter.export(request, uri) }
            _uiState.update { it.copy(save = result) }
        }
    }

    /** Any change to the files or options drops a made PDF; nothing changes while working or saving. */
    private fun edit(change: (PdfMakerUiState) -> PdfMakerUiState) {
        if (_uiState.value.making != null || _uiState.value.save.state == SaveState.SAVING) return
        _uiState.update { change(it).copy(result = null, save = SaveResult(), shareFailed = false) }
    }

    private fun updateCrop(change: (PdfCrop) -> CropRect) = _uiState.update { state ->
        val crop = state.crop ?: return@update state
        state.copy(crop = crop.copy(rect = change(crop)))
    }

    companion object {
        /** Navigation arguments: `pdf/{tool}` or `exam/{examId}/pdf/{docType}`. */
        const val TOOL = "tool"
        const val EXAM_ID = "examId"
        const val DOC_TYPE = "docType"

        /** Tools save as exam `pdf` / `PDF` (ALGORITHMS 6.6). */
        const val TOOL_EXAM_ID = "pdf"
        const val TOOL_EXAM_NAME = "PDF"

        private const val KB = 1024
        private const val QUARTER_TURNS = 4
        private const val CROP_CAP = 1600
        private const val THUMBNAIL = 192
        private const val MAX_KB_DIGITS = 4

        /** A tool's synthetic slot: PDF, at most [maxKb], no minimum, no dimensions. */
        fun toolTarget(
            tool: PdfTool,
            maxKb: Int?,
        ): PdfTarget? {
            maxKb ?: return null
            val type = if (tool == PdfTool.ID_CARD) DocType.ID_CARD else DocType.DOCUMENT
            val spec = DocSpec(type, true, listOf(FileFormat.PDF), SizeKb(max = maxKb.toDouble()), Dimensions(DimensionMode.NONE))
            return PdfTarget(TOOL_EXAM_ID, TOOL_EXAM_NAME, spec, unverified = false, exam = false)
        }
    }
}
