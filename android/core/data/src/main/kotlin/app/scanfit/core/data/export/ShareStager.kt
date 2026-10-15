package app.scanfit.core.data.export

import android.content.Context
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Stages the review bytes for the system share sheet (ALGORITHMS 1.6.2): one file at a time in an app-private cache
 * folder, re-read and compared before it is handed out. Sharing never records Saved.
 */
interface ShareStager {
    /** A `content://` URI for [bytes] named [fileName], or `null` when the file could not be written exactly. */
    suspend fun stage(
        fileName: String,
        bytes: ByteArray,
    ): String?
}

class AndroidShareStager
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : ShareStager {
    override suspend fun stage(
        fileName: String,
        bytes: ByteArray,
    ): String? = withContext(Dispatchers.IO) {
        val dir = shareDir(context)
        try {
            dir.deleteRecursively()
            dir.mkdirs()
            val file = File(dir, fileName)
            // A §9.8 name is a single path segment; anything else is not shared.
            if (file.canonicalFile.parentFile != dir.canonicalFile) return@withContext null
            file.writeBytes(bytes)
            if (!file.readBytes().contentEquals(bytes)) {
                dir.deleteRecursively()
                return@withContext null
            }
            FileProvider.getUriForFile(context, "${context.packageName}.capture", file).toString()
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        private fun shareDir(context: Context) = File(context.cacheDir, "share")

        /** Empties the share folder: called on app start, so a shared file never outlives the session. */
        fun clear(context: Context) {
            shareDir(context).deleteRecursively()
        }
    }
}
