package app.scanfit.feature.flowphoto

import app.scanfit.core.data.UserPreferences
import app.scanfit.core.imaging.ExportNaming
import app.scanfit.core.inspect.Inspector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class PhotoSaveState { IDLE, SAVING, SAVED, SAVE_FAILED, VERIFY_FAILED }

data class PhotoSaveResult(
    val state: PhotoSaveState = PhotoSaveState.IDLE,
    val cleanupSucceeded: Boolean? = null,
)

class PhotoExportRequest(
    val slot: PhotoSlot,
    bytes: ByteArray,
) {
    val bytes: ByteArray = bytes.copyOf()
    val fileName: String
        get() {
            val file = Inspector.inspect(bytes)
            return ExportNaming.fileName(slot.examName, slot.spec, file.width ?: 0, file.height ?: 0, bytes.size)
        }
}

interface ExportDestination {
    suspend fun write(bytes: ByteArray)

    suspend fun read(): ByteArray

    suspend fun publish()

    suspend fun delete(): Boolean
}

interface ExportDestinations {
    val requiresPicker: Boolean

    suspend fun create(request: PhotoExportRequest, pickedUri: String?): ExportDestination
}

class PhotoExporter
@Inject
constructor(
    val destinations: ExportDestinations,
    private val preferences: UserPreferences,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun export(
        request: PhotoExportRequest,
        pickedUri: String? = null,
    ): PhotoSaveResult {
        if (destinations.requiresPicker && pickedUri == null) return PhotoSaveResult()
        var destination: ExportDestination? = null
        return try {
            destination = destinations.create(request, pickedUri)
            destination.write(request.bytes)
            val written = destination.read()
            if (!PhotoExportVerifier.verifies(request.bytes, written, request.slot.spec)) {
                PhotoSaveResult(PhotoSaveState.VERIFY_FAILED, cleanup(destination))
            } else {
                destination.publish()
                preferences.recordSaved(request.slot.examId, request.slot.spec.type.name)
                PhotoSaveResult(PhotoSaveState.SAVED)
            }
        } catch (error: CancellationException) {
            cleanup(destination)
            throw error
        } catch (_: Exception) {
            PhotoSaveResult(PhotoSaveState.SAVE_FAILED, cleanup(destination))
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
