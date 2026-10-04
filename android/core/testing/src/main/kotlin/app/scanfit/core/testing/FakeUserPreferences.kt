package app.scanfit.core.testing

import app.scanfit.core.data.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [UserPreferences] for view-model tests. */
class FakeUserPreferences(
    pinned: List<String> = emptyList(),
    showUnverified: Boolean = false,
) : UserPreferences {
    override val pinnedExamIds = MutableStateFlow(pinned)
    override val showUnverified = MutableStateFlow(showUnverified)

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
