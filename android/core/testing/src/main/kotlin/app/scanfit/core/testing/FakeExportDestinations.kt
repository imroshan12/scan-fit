package app.scanfit.core.testing

import app.scanfit.core.data.export.ExportDestination
import app.scanfit.core.data.export.ExportDestinations
import app.scanfit.core.data.export.ExportRequest
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException

/** Test double for [ExportDestinations]: scripted failures by [operation], and a log of what was called. */
class FakeExportDestinations(
    var operation: String = "success",
    override val requiresPicker: Boolean = false,
) : ExportDestinations {
    val events = mutableListOf<String>()
    var deleteSucceeds = true
    var gate: CompletableDeferred<Unit>? = null
    var lastRequest: ExportRequest? = null

    override suspend fun create(request: ExportRequest, pickedUri: String?): ExportDestination {
        events += "create"
        lastRequest = request
        if (operation == "create_failed") throw IOException("create")
        return object : ExportDestination {
            override suspend fun write(bytes: ByteArray) {
                events += "write"
                gate?.await()
                if (operation == "write_failed") throw IOException("write")
            }

            override suspend fun read(): ByteArray {
                events += "read"
                if (operation == "read_failed") throw IOException("read")
                return request.bytes.copyOf().apply {
                    if (operation == "corrupt") this[lastIndex] = 1
                }
            }

            override suspend fun publish() {
                events += "publish"
                if (operation == "publish_failed") throw IOException("publish")
            }

            override suspend fun delete(): Boolean {
                events += "delete"
                return deleteSucceeds
            }
        }
    }
}
