package app.scanfit.feature.flowink

import app.scanfit.core.data.ImageSource
import app.scanfit.core.imaging.AndroidImageDecoder
import app.scanfit.core.imaging.AndroidJpegEncoder
import app.scanfit.core.imaging.CropRect
import app.scanfit.core.imaging.FitPipeline
import app.scanfit.core.imaging.InkOptions
import app.scanfit.core.imaging.Pipeline
import app.scanfit.core.imaging.PipelineOutcome
import app.scanfit.core.imaging.Raster
import app.scanfit.core.model.DocSpec
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Qualifier

/** The platform side of the ink flow, behind an interface so [InkFlowViewModel] is testable on the JVM. */
interface InkTools {
    suspend fun read(uri: String): ByteArray?

    fun newCaptureUri(): String

    fun discardCaptures()

    /** ALGORITHMS 1.1: downsampled on decode, upright, sRGB. `null` when the bytes are not an image we can decode. */
    fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster?

    /** ALGORITHMS 3 / 9.5: cleanup by [pipeline] with [ink] options, pad to aspect, then fit (§9.4). */
    fun fit(
        cropped: Raster,
        spec: DocSpec,
        pipeline: Pipeline,
        ink: InkOptions,
    ): PipelineOutcome
}

class AndroidInkTools
@Inject
constructor(
    private val source: ImageSource,
) : InkTools {
    private val pipeline = FitPipeline(AndroidJpegEncoder)

    override suspend fun read(uri: String): ByteArray? = source.read(uri)

    override fun newCaptureUri(): String = source.newCaptureUri()

    override fun discardCaptures() = source.discardCaptures()

    override fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster? = AndroidImageDecoder.decode(bytes, maxLongSide)

    override fun fit(
        cropped: Raster,
        spec: DocSpec,
        pipeline: Pipeline,
        ink: InkOptions,
    ): PipelineOutcome = this.pipeline.run(
        cropped,
        spec,
        pipeline = pipeline,
        crop = CropRect(0, 0, cropped.width, cropped.height),
        ink = ink,
    )
}

/** Image work runs here, never on the main thread (CLAUDE.md rule 4). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InkWork

@Module
@InstallIn(SingletonComponent::class)
internal abstract class InkFlowModule {
    @Binds
    abstract fun inkTools(tools: AndroidInkTools): InkTools

    companion object {
        @Provides
        @InkWork
        fun work(): CoroutineDispatcher = Dispatchers.Default
    }
}
