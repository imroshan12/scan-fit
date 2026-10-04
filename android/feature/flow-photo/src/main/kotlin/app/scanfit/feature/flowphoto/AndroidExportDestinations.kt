package app.scanfit.feature.flowphoto

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import app.scanfit.core.imaging.ExportNaming
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject

class AndroidExportDestinations
@Inject
constructor(
    @ApplicationContext context: Context,
) : ExportDestinations {
    private val resolver = context.contentResolver
    override val requiresPicker: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override suspend fun create(request: PhotoExportRequest, pickedUri: String?): ExportDestination {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val uri = pickedUri?.toUri() ?: throw IOException("No destination")
            return ResolverExportDestination(resolver, uri, pending = false)
        }
        return createDownload(request)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createDownload(request: PhotoExportRequest): ExportDestination {
        val folder = ExportNaming.examShort(request.slot.examName).ifEmpty { request.slot.examId }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, request.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ScanFit/$folder/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create destination")
        return ResolverExportDestination(resolver, uri, pending = true)
    }
}

private class ResolverExportDestination(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val pending: Boolean,
) : ExportDestination {
    override suspend fun write(bytes: ByteArray) {
        val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("Could not open destination")
        stream.use { it.write(bytes) }
    }

    override suspend fun read(): ByteArray {
        val stream = resolver.openInputStream(uri) ?: throw IOException("Could not reopen destination")
        return stream.use { it.readBytes() }
    }

    override suspend fun publish() {
        if (pending && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, values, null, null) != 1) throw IOException("Could not publish destination")
        }
    }

    override suspend fun delete(): Boolean = if (pending) {
        resolver.delete(uri, null, null) == 1
    } else {
        DocumentsContract.deleteDocument(resolver, uri)
    }
}
