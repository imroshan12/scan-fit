package app.scanfit.core.testing

import app.scanfit.core.data.draft.DraftStore
import app.scanfit.core.data.draft.FileDraftStore
import app.scanfit.core.data.draft.RetainedDraft
import app.scanfit.core.data.export.ExportVerifier
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.DocType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeDraftStore : DraftStore {
    private val changes = MutableStateFlow(0L)
    override val revisions = changes.asStateFlow()
    val records = mutableMapOf<Pair<String, DocType>, RetainedDraft>()
    var now = 1_800_000_000_000L
    var failWrites = false
    var writes = 0
    var beforeRetain: suspend () -> Unit = {}
    var beforeRead: suspend () -> Unit = {}

    override suspend fun read(examId: String, spec: DocSpec, kind: DocKind): RetainedDraft? {
        beforeRead()
        val record = records[examId to spec.type] ?: return null
        val age = now - record.createdAtMillis
        if (age !in 0 until FileDraftStore.TTL_MILLIS ||
            !ExportVerifier.verifies(record.bytes, record.bytes, spec, kind)
        ) {
            delete(examId, spec)
            return null
        }
        return record.copy(bytes = record.bytes.copyOf())
    }

    override suspend fun retain(examId: String, spec: DocSpec, kind: DocKind, bytes: ByteArray): Boolean {
        beforeRetain()
        currentCoroutineContext().ensureActive()
        if (failWrites || !ExportVerifier.verifies(bytes, bytes, spec, kind)) return false
        records[examId to spec.type] = RetainedDraft(bytes.copyOf(), now)
        writes++
        changes.value += 1
        return true
    }

    override suspend fun delete(examId: String, spec: DocSpec) {
        if (records.remove(examId to spec.type) != null) changes.value += 1
    }
}
