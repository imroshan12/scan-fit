package app.scanfit.core.data.draft

import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DocSpec
import kotlinx.coroutines.flow.StateFlow

data class RetainedDraft(val bytes: ByteArray, val createdAtMillis: Long)

interface DraftStore {
    val revisions: StateFlow<Long>

    suspend fun read(examId: String, spec: DocSpec, kind: DocKind): RetainedDraft?

    suspend fun retain(examId: String, spec: DocSpec, kind: DocKind, bytes: ByteArray): Boolean

    suspend fun delete(examId: String, spec: DocSpec)
}

enum class DraftStatus { SAVED, READY, NOT_STARTED }

fun draftStatus(saved: Boolean, available: Boolean): DraftStatus = when {
    saved -> DraftStatus.SAVED
    available -> DraftStatus.READY
    else -> DraftStatus.NOT_STARTED
}
