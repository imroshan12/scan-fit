package app.scanfit.core.data.export

import app.scanfit.core.data.UserPreferences
import app.scanfit.core.data.kit.KitStore
import app.scanfit.core.data.quota.SaveGate
import app.scanfit.core.imaging.ExportNaming
import app.scanfit.core.inspect.Inspector
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DocSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A save's lifecycle (ALGORITHMS 1.6): idle → saving → saved, or a retryable error. */
enum class SaveState { IDLE, SAVING, SAVED, SAVE_FAILED, VERIFY_FAILED }

data class SaveResult(
    val state: SaveState = SaveState.IDLE,
    /** After a failure: whether the partial destination was removed (`null` = nothing to remove). */
    val cleanupSucceeded: Boolean? = null,
)

/** What the written-file check knows about a PDF (ALGORITHMS 6.5). */
data class PdfExport(
    val pages: Int,
    /** The app wrote it (an image PDF of [pages] pages); `false` for a PDF kept unchanged (§6.1). */
    val written: Boolean,
)

/** One fitted file to save for an exam slot. [kind] is the slot's match kind, used by the written-file check. */
class ExportRequest(
    val examId: String,
    val examName: String,
    val spec: DocSpec,
    val kind: DocKind,
    bytes: ByteArray,
    /** Exam slots record Saved on the checklist; a Custom size and the PDF tools have none (ALGORITHMS 11, 6.6). */
    val recordsSaved: Boolean = true,
    /** Set for a PDF (ALGORITHMS 6.5); `null` for a JPEG. */
    val pdf: PdfExport? = null,
) {
    val bytes: ByteArray = bytes.copyOf()

    val mimeType: String get() = if (pdf != null) "application/pdf" else "image/jpeg"

    /** ALGORITHMS 1.7 / 9.8. */
    val fileName: String
        get() {
            if (pdf != null) return ExportNaming.pdfFileName(examName, spec, bytes.size)
            val file = Inspector.inspect(bytes)
            return ExportNaming.fileName(examName, spec, file.width ?: 0, file.height ?: 0, bytes.size)
        }
}

interface ExportDestination {
    suspend fun write(bytes: ByteArray)

    suspend fun read(): ByteArray

    suspend fun publish()

    suspend fun delete(): Boolean
}

interface ExportDestinations {
    /** True when the user must pick the destination first (Android 8–9: SAF `CreateDocument`). */
    val requiresPicker: Boolean

    suspend fun create(request: ExportRequest, pickedUri: String?): ExportDestination

    /** Where files for [examName] land, for "4 files saved to …" (ALGORITHMS 1.6.2). */
    fun folderLabel(
        examId: String,
        examName: String,
    ): String = "Downloads/${ExportNaming.APP_FOLDER}/" + ExportNaming.examShort(examName).ifEmpty { examId }
}

/**
 * Writes, re-reads, verifies and only then publishes a file (ALGORITHMS 1.6, CLAUDE.md rule 3). Shared by every flow
 * that saves a fitted file; the Saved checklist status is recorded only after verification and publication, and so is
 * the export history entry (ALGORITHMS 13.3, best effort: it never changes the result) and the free-saves count
 * (ALGORITHMS 14). Flows ask [gate] before a Save or Share and record a Share there themselves.
 */
class DocumentExporter
@Inject
constructor(
    val destinations: ExportDestinations,
    private val preferences: UserPreferences,
    private val kit: KitStore,
    val gate: SaveGate,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun export(
        request: ExportRequest,
        pickedUri: String? = null,
    ): SaveResult {
        if (destinations.requiresPicker && pickedUri == null) return SaveResult()
        var destination: ExportDestination? = null
        val result = try {
            destination = destinations.create(request, pickedUri)
            destination.write(request.bytes)
            val written = destination.read()
            if (!ExportVerifier.verifies(request, written)) {
                SaveResult(SaveState.VERIFY_FAILED, cleanup(destination))
            } else {
                destination.publish()
                if (request.recordsSaved) preferences.recordSaved(request.examId, request.spec.type.name)
                SaveResult(SaveState.SAVED)
            }
        } catch (error: CancellationException) {
            cleanup(destination)
            throw error
        } catch (_: Exception) {
            SaveResult(SaveState.SAVE_FAILED, cleanup(destination))
        }
        // Outside the try: the file is published, so nothing here may clean it up, and cancelling must not skip it.
        if (result.state == SaveState.SAVED) {
            withContext(NonCancellable) {
                gate.record(request.bytes)
                kit.recordExport(request)
            }
        }
        return result
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun cleanup(destination: ExportDestination?): Boolean? = withContext(NonCancellable) {
        try {
            destination?.delete()
        } catch (_: Exception) {
            false
        }
    }
}
