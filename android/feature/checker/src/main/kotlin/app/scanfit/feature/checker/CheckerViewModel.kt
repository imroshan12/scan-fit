package app.scanfit.feature.checker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.data.UserPreferences
import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.data.export.ExportRequest
import app.scanfit.core.data.export.ExportVerifier
import app.scanfit.core.data.export.PdfExport
import app.scanfit.core.data.export.SaveResult
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.data.export.ShareStager
import app.scanfit.core.data.quota.PaywallTrigger
import app.scanfit.core.imaging.AndroidImageDecoder
import app.scanfit.core.imaging.FitError
import app.scanfit.core.imaging.PipelineOutcome
import app.scanfit.core.imaging.Raster
import app.scanfit.core.inspect.DetectedFormat
import app.scanfit.core.inspect.InspectedFile
import app.scanfit.core.inspect.Inspector
import app.scanfit.core.match.CheckerStatus
import app.scanfit.core.match.CheckerTarget
import app.scanfit.core.match.CheckerTargets
import app.scanfit.core.match.DocKind
import app.scanfit.core.match.FileFacts
import app.scanfit.core.match.MatchEngine
import app.scanfit.core.match.MatchNote
import app.scanfit.core.match.ReviewChecks
import app.scanfit.core.match.SlotEvaluation
import app.scanfit.core.match.Verdict
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.FileFormat
import app.scanfit.core.model.PresetBundle
import app.scanfit.core.pdf.PdfError
import app.scanfit.core.pdf.PdfOutcome
import app.scanfit.core.pdf.PdfStrategy
import app.scanfit.core.presets.PresetsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The picked file as the Checker shows it: what it is and its own problems (ALGORITHMS 5, 12). */
data class CheckedFile(
    val name: String,
    val inspected: InspectedFile,
) {
    val kb: Int get() = (inspected.bytes + KB / 2) / KB

    private companion object {
        const val KB = 1024
    }
}

/** The file compared with one exam slot. */
data class TargetResult(
    val target: CheckerTarget,
    val evaluation: SlotEvaluation,
    val checks: ReviewChecks,
    val status: CheckerStatus,
)

/** "Make it fit" (ALGORITHMS 12): nothing yet, working, a fitted copy, or why it could not be made. */
sealed interface FixState {
    data object None : FixState

    data object Working : FixState

    class Ready(
        /** The fitted JPEG, exactly as it would be written. */
        val bytes: ByteArray,
        val kb: Int,
        val width: Int,
        val height: Int,
        val meetsRules: Boolean,
        val checks: ReviewChecks,
        val note: MatchNote,
        /** Set when the copy is a PDF made for a PDF slot (ALGORITHMS 6.6). */
        val pdf: PdfFix? = null,
    ) : FixState

    data class Failed(
        val error: FitError?,
        /** Why no PDF could be made, for a PDF slot. */
        val pdfError: PdfError? = null,
    ) : FixState
}

/** A PDF made by "Make it fit": its pages, whether the app wrote it, and its first page for display. */
class PdfFix(
    val pages: Int,
    val written: Boolean,
    val preview: Raster?,
)

data class CheckerUiState(
    val opening: Boolean = false,
    val openFailed: Boolean = false,
    val file: CheckedFile? = null,
    val kind: DocKind? = null,
    /** Exams that accept the file as it is, for the chosen kind. */
    val note: MatchNote = MatchNote.EMPTY,
    val query: String = "",
    val targets: List<CheckerTarget> = emptyList(),
    val result: TargetResult? = null,
    val fix: FixState = FixState.None,
    val handwritingConfirmed: Boolean = false,
    val save: SaveResult = SaveResult(),
    /** A staged file for the share sheet (ALGORITHMS 1.6.2): the screen opens it once, then `onShareHandled`. */
    val shareUri: String? = null,
    val shareFailed: Boolean = false,
) {
    /** An exam signature needs the one-time running-handwriting tick before it is saved or shared (ALGORITHMS 9.5). */
    val needsHandwriting: Boolean get() = kind == DocKind.SIGNATURE && !handwritingConfirmed

    val canSave: Boolean
        get() =
            (fix as? FixState.Ready)?.meetsRules == true && !needsHandwriting && save.state != SaveState.SAVING
}

/** The pre-submit Checker (ALGORITHMS 12, UI_UX §3): checks a file and fixes a copy on request. */
@HiltViewModel
@Suppress("TooManyFunctions")
class CheckerViewModel
@Inject
constructor(
    private val presets: PresetsRepository,
    private val tools: CheckerTools,
    private val preferences: UserPreferences,
    private val exporter: DocumentExporter,
    private val share: ShareStager,
    @CheckerWork private val work: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CheckerUiState())
    val uiState: StateFlow<CheckerUiState> = _uiState.asStateFlow()

    /** The picked file's bytes, kept out of the UI state; only "Make it fit" decodes them. */
    private var bytes: ByteArray? = null
    private var job: Job? = null
    private var pendingSave: ExportRequest? = null

    init {
        viewModelScope.launch {
            preferences.handwritingConfirmed.collect { confirmed ->
                _uiState.value = _uiState.value.copy(handwritingConfirmed = confirmed)
            }
        }
    }

    fun onFileSelected(uri: String) {
        if (_uiState.value.save.state == SaveState.SAVING) return
        job?.cancel()
        _uiState.value = CheckerUiState(opening = true, handwritingConfirmed = _uiState.value.handwritingConfirmed)
        job =
            viewModelScope.launch {
                val picked =
                    withContext(work) {
                        val read = tools.read(uri) ?: return@withContext null
                        val name = tools.displayName(uri)
                        read to CheckedFile(name, Inspector.inspect(read, name))
                    }
                bytes = picked?.first
                _uiState.value =
                    _uiState.value.copy(opening = false, openFailed = picked == null, file = picked?.second)
            }
    }

    /** What the file is (ALGORITHMS 12): the app never guesses. Clears any exam already chosen. */
    fun onKind(kind: DocKind) {
        val state = _uiState.value
        val file = state.file ?: return
        if (state.save.state == SaveState.SAVING) return
        job?.cancel()
        viewModelScope.launch {
            val bundle = bundle() ?: return@launch
            val facts = FileFacts.of(file.inspected, kind)
            _uiState.value =
                _uiState.value.copy(
                    kind = kind,
                    note = MatchNote.of(facts, bundle.exams, bundle.popular),
                    query = "",
                    targets = CheckerTargets.of(bundle, kind),
                    result = null,
                    fix = FixState.None,
                    save = SaveResult(),
                )
        }
    }

    fun onQuery(query: String) {
        val kind = _uiState.value.kind ?: return
        _uiState.value = _uiState.value.copy(query = query)
        viewModelScope.launch {
            val bundle = bundle() ?: return@launch
            if (_uiState.value.query != query) return@launch
            _uiState.value = _uiState.value.copy(targets = CheckerTargets.of(bundle, kind, query))
        }
    }

    /** Compares the file with one exam slot (ALGORITHMS 9.7, 12). */
    fun onTarget(target: CheckerTarget) {
        val state = _uiState.value
        val file = state.file ?: return
        val kind = state.kind ?: return
        val evaluation = MatchEngine.evaluate(target.slot, FileFacts.of(file.inspected, kind))
        val unverified = target.exam.isUnverified
        val inspected = file.inspected
        val status =
            CheckerStatus.of(inspected.format, target.slot, evaluation.verdict, unverified, inspected.pdfEncrypted)
        _uiState.value =
            state.copy(
                result = TargetResult(target, evaluation, ReviewChecks.of(evaluation), status),
                fix = FixState.None,
                save = SaveResult(),
            )
    }

    /** Back to the exam list for the same file and kind. */
    fun onChangeExam() {
        if (_uiState.value.save.state == SaveState.SAVING) return
        job?.cancel()
        _uiState.value = _uiState.value.copy(result = null, fix = FixState.None, save = SaveResult())
    }

    /** Decodes the file and runs the plain §1 pipeline for the chosen slot; the original is never changed. */
    fun onFix() {
        val state = _uiState.value
        val result = state.result ?: return
        val kind = state.kind ?: return
        val source = bytes ?: return
        if (result.status != CheckerStatus.FIX || state.fix == FixState.Working) return
        val slot = result.target.slot
        _uiState.value = state.copy(fix = FixState.Working, save = SaveResult())
        job =
            viewModelScope.launch {
                val bundle = bundle()
                val format = _uiState.value.file?.inspected?.format
                val imageSlot = FileFormat.JPG in slot.formats || FileFormat.JPEG in slot.formats
                val fix =
                    withContext(work) {
                        // A PDF slot, or a PDF for an image slot that also takes PDF: the PDF engine (ALGORITHMS 6.6).
                        if (!imageSlot || format == DetectedFormat.PDF) {
                            return@withContext pdfFix(source, slot, kind, bundle)
                        }
                        val image = tools.decode(source, decodeCap(slot)) ?: return@withContext FixState.Failed(null)
                        when (val outcome = tools.fit(image, slot)) {
                            is PipelineOutcome.Failure -> FixState.Failed(outcome.error)
                            is PipelineOutcome.Success -> ready(outcome.result.fit.bytes, slot, kind, bundle)
                        }
                    }
                if (_uiState.value.result != result) return@launch
                _uiState.value = _uiState.value.copy(fix = fix)
            }
    }

    fun onConfirmHandwriting() {
        viewModelScope.launch { preferences.confirmHandwriting() }
    }

    /**
     * Saves the fitted copy for the chosen exam slot (ALGORITHMS 1.6). Returns a file name when the UI must first let
     * the user pick the destination (Android 8–9), else `null`; the result arrives in `save`.
     */
    fun onSave(): String? {
        val state = _uiState.value
        val request = request(state) ?: return null
        if (!state.canSave) return null
        if (!exporter.gate.allows(listOf(request.bytes), PaywallTrigger.SAVE)) return null
        _uiState.value = state.copy(save = SaveResult(SaveState.SAVING))
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
            _uiState.value = _uiState.value.copy(save = SaveResult(next))
        } else {
            export(request, uri)
        }
    }

    /** Shares exactly the fitted copy under its export name (ALGORITHMS 1.6.2); same gate as Save. */
    fun onShare() {
        val state = _uiState.value
        val request = request(state) ?: return
        if (!state.canSave) return
        if (!exporter.gate.allows(listOf(request.bytes), PaywallTrigger.SHARE)) return
        val fix = state.fix
        viewModelScope.launch {
            val uri = share.stage(request.fileName, request.bytes)
            if (uri != null) withContext(work) { exporter.gate.record(request.bytes) }
            if (_uiState.value.fix !== fix) return@launch
            _uiState.value = _uiState.value.copy(shareUri = uri, shareFailed = uri == null)
        }
    }

    fun onShareHandled() {
        _uiState.value = _uiState.value.copy(shareUri = null)
    }

    private fun request(state: CheckerUiState): ExportRequest? {
        val fix = state.fix as? FixState.Ready ?: return null
        val target = state.result?.target ?: return null
        val kind = state.kind ?: return null
        val pdf = fix.pdf?.let { PdfExport(it.pages, it.written) }
        return ExportRequest(target.exam.id, target.exam.name, target.slot, kind, fix.bytes, pdf = pdf)
    }

    /** "Make it fit" for a PDF slot: the PDF engine, checked as the export will check it (ALGORITHMS 6.5). */
    private suspend fun pdfFix(
        source: ByteArray,
        slot: DocSpec,
        kind: DocKind,
        bundle: PresetBundle?,
    ): FixState = when (val outcome = tools.makePdf(source, slot)) {
        is PdfOutcome.Failed -> FixState.Failed(null, outcome.error)

        is PdfOutcome.Made -> {
            val made = outcome.pdf
            val written = made.strategy != PdfStrategy.UNCHANGED
            val facts = FileFacts.of(Inspector.inspect(made.bytes), kind)
            val evaluation = MatchEngine.evaluate(slot, facts)
            FixState.Ready(
                bytes = made.bytes,
                kb = (made.bytes.size + KB / 2) / KB,
                width = 0,
                height = 0,
                meetsRules = ExportVerifier.verifiesPdf(made.bytes, made.bytes, slot, PdfExport(made.pages, written)),
                checks = ReviewChecks.of(evaluation),
                note = bundle?.let { MatchNote.of(facts, it.exams, it.popular) } ?: MatchNote.EMPTY,
                pdf = PdfFix(made.pages, written, made.preview),
            )
        }
    }

    private fun export(
        request: ExportRequest,
        uri: String?,
    ) {
        viewModelScope.launch {
            val result = withContext(work) { exporter.export(request, uri) }
            _uiState.value = _uiState.value.copy(save = result)
        }
    }

    private suspend fun bundle(): PresetBundle? = presets.bundle.first { it != null || presets.outcome.value != null }

    private companion object {
        const val KB = 1024

        /** ALGORITHMS 1.1: decode no larger than 2x the largest target dimension (at least 1600 px). */
        fun decodeCap(spec: DocSpec): Int {
            val d = spec.dimensions
            val largest = listOfNotNull(d.width, d.height, d.maxW, d.maxH).maxOrNull() ?: 0
            return AndroidImageDecoder.longSideCap(largest)
        }

        /** Re-inspects the fitted bytes and evaluates them as the slot's kind, as the export will be (rule 3). */
        fun ready(
            bytes: ByteArray,
            slot: DocSpec,
            kind: DocKind,
            bundle: PresetBundle?,
        ): FixState.Ready {
            val inspected = Inspector.inspect(bytes)
            val facts = FileFacts.of(inspected, kind)
            val evaluation = MatchEngine.evaluate(slot, facts)
            val accepted = evaluation.verdict == Verdict.EXACT || evaluation.verdict == Verdict.ACCEPTED
            return FixState.Ready(
                bytes = bytes,
                kb = (bytes.size + KB / 2) / KB,
                width = inspected.width ?: 0,
                height = inspected.height ?: 0,
                meetsRules = accepted && ExportVerifier.verifies(bytes, bytes, slot, kind),
                checks = ReviewChecks.of(evaluation),
                note = bundle?.let { MatchNote.of(facts, it.exams, it.popular) } ?: MatchNote.EMPTY,
            )
        }
    }
}
