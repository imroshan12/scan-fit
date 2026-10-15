package app.scanfit.feature.exams

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.data.UserPreferences
import app.scanfit.core.data.draft.DraftStore
import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.data.export.ExportRequest
import app.scanfit.core.data.export.SaveAllPlan
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DocType
import app.scanfit.core.model.Exam
import app.scanfit.core.model.ExamStatus
import app.scanfit.core.presets.PresetsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Save all on the checklist (ALGORITHMS 1.6.2): idle, saving file [done] of [total], or finished. */
sealed interface SaveAllStatus {
    data object Idle : SaveAllStatus

    data class Saving(
        val done: Int,
        val total: Int,
    ) : SaveAllStatus

    data class Done(
        val saved: Int,
        val total: Int,
        /** "Downloads/ScanFit/IBPS-PO", for "4 files saved to …". */
        val folder: String,
    ) : SaveAllStatus {
        val complete: Boolean get() = saved == total
    }
}

/** The exam checklist (UI_UX §3). Immutable: the single source of truth for the screen. */
sealed interface ExamUiState {
    data object Loading : ExamUiState

    /** The id is not in the trusted presets (a stale link, or a retired exam). */
    data object NotFound : ExamUiState

    data class Ready(
        val exam: Exam,
        val pinned: Boolean,
        val savedDocuments: Set<DocType> = emptySet(),
        val readyDocuments: Map<DocType, Int> = emptyMap(),
        val handwritingConfirmed: Boolean = false,
        /** Save all needs no per-file picker (Android 10+); Android 8–9 saves row by row (ALGORITHMS 1.6.2). */
        val canSaveAll: Boolean = false,
        val saveAll: SaveAllStatus = SaveAllStatus.Idle,
    ) : ExamUiState {
        val saveAllPlan: SaveAllPlan
            get() =
                SaveAllPlan.of(
                    exam.documents.map { it.type },
                    readyDocuments.keys,
                    savedDocuments,
                    handwritingConfirmed,
                )
    }
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ExamViewModel internal constructor(
    savedState: SavedStateHandle,
    presets: PresetsRepository,
    private val preferences: UserPreferences,
    private val drafts: DraftStore,
    private val exporter: DocumentExporter,
    /** Where drafts are read and files written; tests pass their scheduler. */
    private val io: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(
        savedState: SavedStateHandle,
        presets: PresetsRepository,
        preferences: UserPreferences,
        drafts: DraftStore,
        exporter: DocumentExporter,
    ) : this(savedState, presets, preferences, drafts, exporter, Dispatchers.IO)

    private val examId: String = savedState.get<String>(EXAM_ID).orEmpty()
    private val refreshes = MutableStateFlow(0L)
    private val saveAll = MutableStateFlow<SaveAllStatus>(SaveAllStatus.Idle)
    private var saveJob: Job? = null

    /** The last Ready rows, so a Saved record or a refresh updates the list in place instead of flashing Loading. */
    private var shown: ExamUiState.Ready? = null

    val uiState: StateFlow<ExamUiState> =
        combine(
            presets.outcome,
            presets.bundle,
            preferences.pinnedExamIds,
            preferences.savedDocuments,
            preferences.handwritingConfirmed,
        ) { outcome, bundle, pinned, saved, confirmed ->
            when {
                bundle == null -> if (outcome == null) ExamUiState.Loading else ExamUiState.NotFound

                else -> {
                    val exam = bundle.exams.firstOrNull { it.id == examId && it.status == ExamStatus.ACTIVE }
                    if (exam == null) {
                        ExamUiState.NotFound
                    } else {
                        ExamUiState.Ready(
                            exam,
                            exam.id in pinned,
                            exam.documents.map { it.type }.filter { type ->
                                saved.any { it.examId == exam.id && it.docType == type.name }
                            }.toSet(),
                            handwritingConfirmed = confirmed,
                            canSaveAll = !exporter.destinations.requiresPicker,
                        )
                    }
                }
            }
        }.combine(combine(drafts.revisions, refreshes) { revision, refresh -> revision to refresh }) { state, _ ->
            state
        }.transformLatest { state ->
            if (state !is ExamUiState.Ready) {
                shown = null
                emit(state)
            } else {
                val previous = shown?.takeIf { it.exam == state.exam }
                emit(previous?.let { state.copy(readyDocuments = it.readyDocuments) } ?: ExamUiState.Loading)
                val available = withContext(io) { readyDocuments(state.exam) }
                val ready = state.copy(readyDocuments = available)
                shown = ready
                emit(ready)
            }
        }.combine(saveAll) { state, status ->
            if (state is ExamUiState.Ready) state.copy(saveAll = status) else state
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0),
            ExamUiState.Loading,
        )

    private suspend fun readyDocuments(exam: Exam): Map<DocType, Int> = buildMap {
        for (spec in exam.documents) {
            val kind = DocKind.of(spec.type)
            if (kind == DocKind.PDF_DOCUMENT) continue
            val draft = drafts.read(exam.id, spec, kind) ?: continue
            put(spec.type, (draft.bytes.size + KB / 2) / KB)
        }
    }

    fun refreshDrafts() {
        refreshes.value += 1
    }

    fun onTogglePin() {
        val ready = uiState.value as? ExamUiState.Ready ?: return
        viewModelScope.launch { preferences.setPinned(ready.exam.id, !ready.pinned) }
    }

    /**
     * Saves every Ready row (ALGORITHMS 1.6.2): each draft is re-read and goes through the full write, re-read, verify
     * and publish, one at a time; a failure does not stop the rest. Retrying re-plans, so saved rows are not
     * written twice.
     */
    fun onSaveAll() {
        val ready = uiState.value as? ExamUiState.Ready ?: return
        if (!ready.canSaveAll || saveJob?.isActive == true) return
        val plan = ready.saveAllPlan.included
        if (plan.isEmpty()) return
        val exam = ready.exam
        saveJob =
            viewModelScope.launch {
                var saved = 0
                plan.forEachIndexed { index, type ->
                    saveAll.value = SaveAllStatus.Saving(index, plan.size)
                    if (saveOne(exam, type)) saved++
                }
                val folder = exporter.destinations.folderLabel(exam.id, exam.name)
                saveAll.value = SaveAllStatus.Done(saved, plan.size, folder)
            }
    }

    /** The finished message was read (or the user tapped Open folder). */
    fun onSaveAllDismissed() {
        if (saveAll.value is SaveAllStatus.Done) saveAll.value = SaveAllStatus.Idle
    }

    private suspend fun saveOne(
        exam: Exam,
        type: DocType,
    ): Boolean {
        val spec = exam.documents.firstOrNull { it.type == type } ?: return false
        val kind = DocKind.of(type)
        return withContext(io) {
            // The draft may have expired since the checklist loaded: then this row fails, the others go on.
            val draft = drafts.read(exam.id, spec, kind) ?: return@withContext false
            val request = ExportRequest(exam.id, exam.name, spec, kind, draft.bytes)
            exporter.export(request).state == SaveState.SAVED
        }
    }

    companion object {
        /** Navigation argument: `exam/{examId}`. */
        const val EXAM_ID = "examId"
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val KB = 1024
    }
}
