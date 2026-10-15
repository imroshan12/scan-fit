package app.scanfit.feature.checker

import android.content.Context
import android.provider.OpenableColumns
import androidx.core.net.toUri
import app.scanfit.core.data.ImageSource
import app.scanfit.core.imaging.AndroidImageDecoder
import app.scanfit.core.imaging.AndroidJpegEncoder
import app.scanfit.core.imaging.FitPipeline
import app.scanfit.core.imaging.PipelineOutcome
import app.scanfit.core.imaging.Raster
import app.scanfit.core.model.DocSpec
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Qualifier

/** The platform side of the Checker, behind an interface so [CheckerViewModel] is testable on the JVM. */
interface CheckerTools {
    /** The file's bytes (64 MiB bound), or `null` when it cannot be read. */
    suspend fun read(uri: String): ByteArray?

    /** The file's display name, only for the extension check (ALGORITHMS 5); never stored or sent. */
    fun displayName(uri: String): String

    /** ALGORITHMS 1.1: downsampled on decode, upright, sRGB. `null` when the bytes are not an image we can decode. */
    fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster?

    /** The plain §1 pipeline for [spec]: photo kinds crop centred to the aspect, others pad with white. */
    fun fit(
        image: Raster,
        spec: DocSpec,
    ): PipelineOutcome
}

class AndroidCheckerTools
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val source: ImageSource,
) : CheckerTools {
    private val pipeline = FitPipeline(AndroidJpegEncoder)

    override suspend fun read(uri: String): ByteArray? = source.read(uri)

    override fun displayName(uri: String): String = try {
        context.contentResolver.query(uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0).orEmpty() else ""
        }.orEmpty()
    } catch (_: SecurityException) {
        ""
    } catch (_: IllegalArgumentException) {
        ""
    }

    override fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster? = AndroidImageDecoder.decode(bytes, maxLongSide)

    override fun fit(
        image: Raster,
        spec: DocSpec,
    ): PipelineOutcome = pipeline.run(image, spec)
}

/** Image work runs here, never on the main thread (CLAUDE.md rule 4). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CheckerWork

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CheckerModule {
    @Binds
    abstract fun checkerTools(tools: AndroidCheckerTools): CheckerTools

    companion object {
        @Provides
        @CheckerWork
        fun work(): CoroutineDispatcher = Dispatchers.Default
    }
}
