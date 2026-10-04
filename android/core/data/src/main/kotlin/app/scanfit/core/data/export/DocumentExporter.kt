package app.scanfit.core.data.export

import app.scanfit.core.data.UserPreferences
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

/** One fitted file to save for an exam slot. [kind] is the slot's match kind, used by the written-file check. */
class ExportRequest(
    val examId: String,
    val examName: String,
    val spec: DocSpec,
    val kind: DocKind,
    bytes: ByteArray,
) {
    val bytes: ByteArray = bytes.copyOf()

    /** ALGORITHMS 1.7 / 9.8. */
    val fileName: String
        get() {
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
}

/**
 * Writes, re-reads, verifies and only then publishes a file (ALGORITHMS 1.6, CLAUDE.md rule 3). Shared by every flow
 * that saves a fitted file; the Saved checklist status is recorded only after verification and publication.
 */
class DocumentExporter
@Inject
constructor(
    val destinations: ExportDestinations,
    private val preferences: UserPreferences,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun export(
        request: ExportRequest,
        pickedUri: String? = null,
    ): SaveResult {
        if (destinations.requiresPicker && pickedUri == null) return SaveResult()
        var destination: ExportDestination? = null
        return try {
            destination = destinations.create(request, pickedUri)
            destination.write(request.bytes)
            val written = destination.read()
            if (!ExportVerifier.verifies(request.bytes, written, request.spec, request.kind)) {
                SaveResult(SaveState.VERIFY_FAILED, cleanup(destination))
            } else {
                destination.publish()
                preferences.recordSaved(request.examId, request.spec.type.name)
                SaveResult(SaveState.SAVED)
            }
        } catch (error: CancellationException) {
            cleanup(destination)
            throw error
        } catch (_: Exception) {
            SaveResult(SaveState.SAVE_FAILED, cleanup(destination))
        }
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
