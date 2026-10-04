package app.scanfit.feature.flowphoto

import app.scanfit.core.data.ImageSource
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
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.LocalDate
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
    private val source: ImageSource,
) : PhotoTools {
    private val pipeline = FitPipeline(AndroidJpegEncoder)

    override suspend fun read(uri: String): ByteArray? = source.read(uri)

    override fun newCaptureUri(): String = source.newCaptureUri()

    override fun discardCaptures() = source.discardCaptures()

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
}

/** Image work runs here, never on the main thread (CLAUDE.md rule 4). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PhotoWork

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PhotoFlowModule {
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
