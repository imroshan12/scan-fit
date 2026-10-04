package app.scanfit.core.data

import android.content.Context
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject

/** Where a flow's input image comes from: a picked file or the camera app's capture (photo and ink flows). */
interface ImageSource {
    /** The bytes of a picked or captured image, or `null` when it cannot be read (gone, too large, no access). */
    suspend fun read(uri: String): ByteArray?

    /** A fresh `content://` URI for the camera app to write into. [discardCaptures] deletes the file. */
    fun newCaptureUri(): String

    /** Deletes every captured file: the image lives on only as the decoded raster in memory. */
    fun discardCaptures()
}

class AndroidImageSource
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : ImageSource {
    private val captureDir get() = File(context.cacheDir, CAPTURE_DIR)

    override suspend fun read(uri: String): ByteArray? = try {
        context.contentResolver.openInputStream(uri.toUri())?.use { it.readAtMost(MAX_INPUT_BYTES) }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    override fun newCaptureUri(): String {
        captureDir.mkdirs()
        val file = File(captureDir, "${UUID.randomUUID()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.capture", file).toString()
    }

    override fun discardCaptures() {
        captureDir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val CAPTURE_DIR = "capture"

        /** A 50 MP HEIC or JPEG is well under this; anything bigger is not a photo we should hold in memory. */
        const val MAX_INPUT_BYTES = 64 * 1024 * 1024
    }
}

/** The whole stream, or `null` when it is longer than [limit] bytes (`readNBytes` needs API 33). */
private fun InputStream.readAtMost(limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    while (true) {
        val n = read(buffer)
        if (n < 0) return out.toByteArray()
        if (out.size() + n > limit) return null
        out.write(buffer, 0, n)
    }
}

private const val BUFFER_BYTES = 64 * 1024
