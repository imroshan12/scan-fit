package app.scanfit.core.testing

import app.scanfit.core.data.SavedDocument
import app.scanfit.core.data.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [UserPreferences] for view-model tests. */
class FakeUserPreferences(
    pinned: List<String> = emptyList(),
    showUnverified: Boolean = true, // the production default (ALGORITHMS §10)
) : UserPreferences {
    override val pinnedExamIds = MutableStateFlow(pinned)
    override val showUnverified = MutableStateFlow(showUnverified)
    override val savedDocuments = MutableStateFlow(emptySet<SavedDocument>())

    override suspend fun recordSaved(examId: String, docType: String) {
        savedDocuments.value += SavedDocument(examId, docType)
    }

    override suspend fun setPinned(
        examId: String,
        pinned: Boolean,
    ) {
        val others = pinnedExamIds.value.filter { it != examId }
        pinnedExamIds.value = if (pinned) listOf(examId) + others else others
    }

    override suspend fun setShowUnverified(show: Boolean) {
        showUnverified.value = show
    }
}
