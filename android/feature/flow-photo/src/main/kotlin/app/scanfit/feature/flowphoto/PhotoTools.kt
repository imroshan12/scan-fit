package app.scanfit.feature.flowphoto

import android.content.Context
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.scanfit.core.imaging.AndroidImageDecoder
import app.scanfit.core.imaging.AndroidJpegEncoder
import app.scanfit.core.imaging.AndroidStripRenderer
import app.scanfit.core.imaging.CropRect
import app.scanfit.core.imaging.FitPipeline
import app.scanfit.core.imaging.PipelineOutcome
import app.scanfit.core.imaging.Raster
import app.scanfit.core.model.DocSpec
import app.scanfit.core.vision.FaceDetector
import app.scanfit.core.vision.MlKitFaceDetector
import app.scanfit.core.vision.MlKitPersonSegmenter
import app.scanfit.core.vision.PersonSegmenter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** The platform side of the photo flow, behind an interface so [PhotoFlowViewModel] is testable on the JVM. */
interface PhotoTools {
    /** The bytes of a picked or captured image, or `null` when it cannot be read (gone, too large, no access). */
    suspend fun read(uri: String): ByteArray?

    /** A fresh `content://` URI for the camera app to write into. [discardCaptures] deletes the file. */
    fun newCaptureUri(): String

    /** Deletes every captured file: the photo lives on only as the decoded raster in memory. */
    fun discardCaptures()

    /** ALGORITHMS 1.1: downsampled on decode, upright, sRGB. `null` when the bytes are not an image we can decode. */
    fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster?

    /** ALGORITHMS 2.4: the white name/date strip inside the photo's own size. */
    fun drawStrip(
        photo: Raster,
        name: String,
        date: String,
    ): Raster

    /** ALGORITHMS 9.4: the cropped (and whitened, striped) photo fitted to the slot. */
    fun fit(
        prepared: Raster,
        spec: DocSpec,
    ): PipelineOutcome

    fun today(): LocalDate
}

class AndroidPhotoTools
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : PhotoTools {
    private val captureDir get() = File(context.cacheDir, CAPTURE_DIR)
    private val pipeline = FitPipeline(AndroidJpegEncoder)

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
        return FileProvider.getUriForFile(context, "${context.packageName}.photoflow.files", file).toString()
    }

    override fun discardCaptures() {
        captureDir.listFiles()?.forEach { it.delete() }
    }

    override fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster? = AndroidImageDecoder.decode(bytes, maxLongSide)

    override fun drawStrip(
        photo: Raster,
        name: String,
        date: String,
    ): Raster = AndroidStripRenderer.withStrip(photo, name, date)

    override fun fit(
        prepared: Raster,
        spec: DocSpec,
    ): PipelineOutcome = pipeline.run(prepared, spec, crop = CropRect(0, 0, prepared.width, prepared.height))

    override fun today(): LocalDate = LocalDate.now()

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

/** Image work runs here, never on the main thread (CLAUDE.md rule 4). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PhotoWork

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PhotoFlowModule {
    @Binds
    abstract fun exportDestinations(destinations: AndroidExportDestinations): ExportDestinations

    @Binds
    abstract fun photoTools(tools: AndroidPhotoTools): PhotoTools

    companion object {
        @Provides
        @Singleton
        fun faceDetector(): FaceDetector = MlKitFaceDetector()

        @Provides
        @Singleton
        fun personSegmenter(): PersonSegmenter = MlKitPersonSegmenter()

        @Provides
        @PhotoWork
        fun work(): CoroutineDispatcher = Dispatchers.Default
    }
}
