package app.scanfit.core.data.kit

import app.scanfit.core.data.export.ExportRequest
import app.scanfit.core.imaging.KitOriginalImage
import app.scanfit.core.inspect.ColorKind
import app.scanfit.core.inspect.DetectedFormat
import app.scanfit.core.inspect.Inspector
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DocType
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** A picture the user kept in My Kit to use for other exams (ALGORITHMS 13.1). Metadata only, never the bytes. */
data class KitOriginal(
    val id: String,
    val kind: DocKind,
    val createdAtMillis: Long,
    val width: Int,
    val height: Int,
    val size: Int,
)

/** One verified export (ALGORITHMS 13.3). Custom size entries carry `exam_id` `custom`. */
data class ExportRecord(
    val id: String,
    val createdAtMillis: Long,
    val examId: String,
    val examName: String,
    val docType: DocType,
    val kind: DocKind,
    val fileName: String,
    val size: Int,
    /** 0 for a PDF. */
    val width: Int,
    val height: Int,
    /** A PDF's page count (ALGORITHMS 13.3, `format` `pdf`); `null` for a JPEG. */
    val pages: Int? = null,
) {
    val isPdf: Boolean get() = pages != null

    /** The private copy's file name: `exports/<id>.jpg` or `exports/<id>.pdf`. */
    val copyName: String get() = "$id." + if (isPdf) "pdf" else "jpg"
}

/** A history row: [shareable] while its private copy is inside the 30-day window. */
data class HistoryEntry(
    val record: ExportRecord,
    val shareable: Boolean,
)

sealed interface SaveOriginalResult {
    data class Saved(
        val id: String,
    ) : SaveOriginalResult

    /** My Kit already holds [KitStore.MAX_ORIGINALS]: nothing was written. */
    data object Full : SaveOriginalResult

    data object Failed : SaveOriginalResult
}

/**
 * My Kit's originals and export history (ALGORITHMS 13), app-private and excluded from backup. Listing throws when the
 * storage cannot be read, so the screen can offer a retry instead of claiming an empty kit.
 */
interface KitStore {
    val revisions: StateFlow<Long>

    /** Every readable-looking original, newest first (bytes are re-checked by [original]). */
    suspend fun originals(): List<KitOriginal>

    suspend fun saveOriginal(
        kind: DocKind,
        jpeg: ByteArray,
    ): SaveOriginalResult

    /** The original's bytes after the §13.1 read check, or `null` (an invalid one is removed). */
    suspend fun original(id: String): ByteArray?

    suspend fun deleteOriginal(id: String)

    /** Newest first, at most [ExportHistory.MAX_ENTRIES]. */
    suspend fun history(): List<HistoryEntry>

    /** Best effort: `false` when the entry or its copy could not be written. Never affects the export. */
    suspend fun recordExport(request: ExportRequest): Boolean

    /** The copy's bytes after the §13.3 checks, or `null` when it is gone, too old or does not match its entry. */
    suspend fun exportCopy(id: String): ByteArray?

    suspend fun clearHistory()

    companion object {
        const val MAX_ORIGINALS = 30
        const val MAX_ORIGINAL_BYTES = 16 * 1024 * 1024
        const val MAX_COPY_BYTES = 64 * 1024 * 1024
    }
}

/** Save to My Kit on a review (ALGORITHMS 13.1). */
enum class KitSave {
    IDLE,
    SAVING,
    SAVED,
    FULL,
    FAILED,
    ;

    companion object {
        fun of(result: SaveOriginalResult): KitSave = when (result) {
            is SaveOriginalResult.Saved -> SAVED
            SaveOriginalResult.Full -> FULL
            SaveOriginalResult.Failed -> FAILED
        }
    }
}

/** Ids are app-made lowercase UUID v4 text, checked before any path is built from them (`kit_id_cases`). */
object KitIds {
    /** The flow route's optional argument naming the original to start from (ALGORITHMS 13.2). */
    const val ROUTE_ARGUMENT = "original"

    private val PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    fun isValid(id: String): Boolean = PATTERN.matches(id)

    fun newId(): String = UUID.randomUUID().toString()
}

/** Export history retention (ALGORITHMS 13.3, `kit_history_cases`). */
object ExportHistory {
    const val MAX_ENTRIES = 100
    const val SHARE_WINDOW_MILLIS = 2_592_000_000L

    /** [entries] in recorded order → newest first (later-recorded first on equal times), the first [maxEntries]. */
    fun prune(
        entries: List<ExportRecord>,
        nowMillis: Long,
        maxEntries: Int = MAX_ENTRIES,
    ): List<HistoryEntry> = entries.asReversed()
        .sortedByDescending { it.createdAtMillis }
        .take(maxEntries)
        .map { HistoryEntry(it, shareable(it.createdAtMillis, nowMillis)) }

    fun shareable(
        createdAtMillis: Long,
        nowMillis: Long,
    ): Boolean = nowMillis - createdAtMillis in 0 until SHARE_WINDOW_MILLIS
}

/** The §13.1 read check: a baseline RGB JPEG without private metadata, of the recorded size. */
internal fun kitJpegReadable(
    bytes: ByteArray,
    width: Int,
    height: Int,
): Boolean {
    val file = Inspector.inspect(bytes)
    return file.format == DetectedFormat.JPEG && file.sof == "SOF0" && file.color == ColorKind.RGB &&
        !file.hasExif && !file.hasGps && !file.hasXmp && file.width == width && file.height == height
}

/** The §13.3 read check of a PDF copy: a PDF without a password, of the recorded size. */
internal fun kitPdfReadable(
    bytes: ByteArray,
    size: Int,
): Boolean {
    val file = Inspector.inspect(bytes)
    return bytes.size == size && file.format == DetectedFormat.PDF && !file.pdfEncrypted
}

/** Originals also stay within the stored-size limit. */
internal fun kitOriginalReadable(
    bytes: ByteArray,
    width: Int,
    height: Int,
): Boolean = bytes.size <= KitStore.MAX_ORIGINAL_BYTES && maxOf(width, height) <= KitOriginalImage.MAX_LONG_SIDE &&
    kitJpegReadable(bytes, width, height)
