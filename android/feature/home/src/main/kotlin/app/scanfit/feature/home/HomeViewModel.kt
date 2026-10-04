package app.scanfit.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.scanfit.core.data.UserPreferences
import app.scanfit.core.model.Confidence
import app.scanfit.core.model.Exam
import app.scanfit.core.model.ExamCategory
import app.scanfit.core.model.ExamSearch
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Whether the signed presets loaded (Phase 0 exit-gate proof, still shown under the sections). */
sealed interface PresetsStatus {
    data object Loading : PresetsStatus

    data class Ready(
        val examCount: Int,
        val version: Int,
    ) : PresetsStatus

    /** Verification failed or the snapshot is missing. The user sees a plain message, never a raw error. */
    data object Failed : PresetsStatus
}

/** One row in a list of exams. */
data class ExamListItem(
    val id: String,
    val name: String,
    val body: String,
    val category: ExamCategory,
    val confidence: Confidence,
)

/**
 * Home (UI_UX §3). With an empty query and no category it shows sections (My exams, Popular now); otherwise a list:
 * search results (ALGORITHMS §10), or the selected category's exams. Immutable: the single source of truth.
 */
data class HomeUiState(
    val presets: PresetsStatus = PresetsStatus.Loading,
    val query: String = "",
    val category: ExamCategory? = null,
    val results: List<ExamListItem> = emptyList(),
    val pinned: List<ExamListItem> = emptyList(),
    val popular: List<ExamListItem> = emptyList(),
) {
    val showsSections: Boolean get() = query.isBlank() && category == null
}

@HiltViewModel
class HomeViewModel
@Inject
constructor(
    presets: PresetsRepository,
    preferences: UserPreferences,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<ExamCategory?>(null)

    private val search = presets.bundle.map { bundle -> bundle?.let(::ExamSearch) }

    private val filters = combine(query, category, preferences.showUnverified) { q, c, u -> Filters(q, c, u) }

    val uiState: StateFlow<HomeUiState> =
        combine(
            presets.outcome,
            presets.bundle,
            search,
            preferences.pinnedExamIds,
            filters,
        ) { outcome, bundle, search, pinned, f ->
            val byId = bundle?.exams.orEmpty().associateBy { it.id }
            HomeUiState(
                presets = outcome.toStatus(),
                query = f.query,
                category = f.category,
                results =
                when {
                    search == null -> emptyList()
                    f.query.isNotBlank() -> search.search(f.query, f.category, f.showUnverified)
                    f.category != null -> search.browse(f.category, f.showUnverified)
                    else -> emptyList()
                }.map(Exam::toItem),
                // A pinned exam stays visible even if it is unverified: the user chose it explicitly.
                pinned = pinned.mapNotNull { byId[it]?.toItem() },
                popular = search?.popular(POPULAR_COUNT, f.showUnverified).orEmpty().map(Exam::toItem),
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    fun onQueryChange(text: String) {
        query.value = text
    }

    /** `null` = "All". Tapping the selected chip again clears it. */
    fun onCategorySelected(selected: ExamCategory?) {
        category.value = if (selected == category.value) null else selected
    }

    private data class Filters(
        val query: String,
        val category: ExamCategory?,
        val showUnverified: Boolean,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val POPULAR_COUNT = 6
    }
}

private fun PresetsLoadOutcome?.toStatus(): PresetsStatus = when (this) {
    null -> PresetsStatus.Loading
    is PresetsLoadOutcome.Ready -> PresetsStatus.Ready(summary.examCount, summary.version)
    is PresetsLoadOutcome.Failed, PresetsLoadOutcome.MissingEmbedded -> PresetsStatus.Failed
}

private fun Exam.toItem() = ExamListItem(id, name, body, category, confidence)
