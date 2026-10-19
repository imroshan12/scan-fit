package app.scanfit.core.testing

import app.scanfit.core.data.export.ExportRequest
import app.scanfit.core.data.kit.ExportHistory
import app.scanfit.core.data.kit.ExportRecord
import app.scanfit.core.data.kit.HistoryEntry
import app.scanfit.core.data.kit.KitOriginal
import app.scanfit.core.data.kit.KitStore
import app.scanfit.core.data.kit.SaveOriginalResult
import app.scanfit.core.inspect.Inspector
import app.scanfit.core.match.DocKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/** In-memory My Kit (ALGORITHMS 13) for view-model tests; [FileKitStore] has its own tests on disk. */
class FakeKitStore : KitStore {
    private val changes = MutableStateFlow(0L)
    override val revisions = changes.asStateFlow()
    val originals = mutableListOf<KitOriginal>()
    val originalBytes = mutableMapOf<String, ByteArray>()
    val exports = mutableListOf<ExportRecord>()
    val copies = mutableMapOf<String, ByteArray>()
    var now = 1_800_000_000_000L
    var failReads = false
    var failWrites = false
    var beforeList: suspend () -> Unit = {}
    private var next = 0

    override suspend fun originals(): List<KitOriginal> {
        beforeList()
        if (failReads) throw IOException("fake")
        return originals.sortedByDescending { it.createdAtMillis }
    }

    override suspend fun saveOriginal(kind: DocKind, jpeg: ByteArray): SaveOriginalResult {
        if (failWrites) return SaveOriginalResult.Failed
        if (originals.size >= KitStore.MAX_ORIGINALS) return SaveOriginalResult.Full
        val file = Inspector.inspect(jpeg)
        val id = newId()
        originals += KitOriginal(id, kind, now, file.width ?: 0, file.height ?: 0, jpeg.size)
        originalBytes[id] = jpeg.copyOf()
        changes.value += 1
        return SaveOriginalResult.Saved(id)
    }

    override suspend fun original(id: String): ByteArray? = originalBytes[id]?.copyOf()

    override suspend fun deleteOriginal(id: String) {
        originals.removeAll { it.id == id }
        originalBytes.remove(id)
        changes.value += 1
    }

    override suspend fun history(): List<HistoryEntry> {
        beforeList()
        if (failReads) throw IOException("fake")
        return ExportHistory.prune(exports, now).map { it.copy(shareable = it.shareable && it.record.id in copies) }
    }

    override suspend fun recordExport(request: ExportRequest): Boolean {
        if (failWrites) return false
        val file = Inspector.inspect(request.bytes)
        val id = newId()
        exports += ExportRecord(
            id, now, request.examId, request.examName, request.spec.type, request.kind, request.fileName,
            request.bytes.size, file.width ?: 0, file.height ?: 0, request.pdf?.pages,
        )
        copies[id] = request.bytes
        changes.value += 1
        return true
    }

    override suspend fun exportCopy(id: String): ByteArray? {
        val entry = exports.firstOrNull { it.id == id } ?: return null
        return copies[id]?.takeIf { ExportHistory.shareable(entry.createdAtMillis, now) }?.copyOf()
    }

    override suspend fun clearHistory() {
        exports.clear()
        copies.clear()
        changes.value += 1
    }

    /** Valid v4-shaped ids that sort in creation order. */
    fun newId(): String = "00000000-0000-4000-8000-%012d".format(next++)
}
