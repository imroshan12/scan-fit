package app.scanfit.feature.pdf

import android.content.Context
import app.scanfit.core.data.ImageSource
import app.scanfit.core.imaging.AndroidImageDecoder
import app.scanfit.core.imaging.AndroidJpegEncoder
import app.scanfit.core.imaging.Raster
import app.scanfit.core.inspect.DetectedFormat
import app.scanfit.core.inspect.Inspector
import app.scanfit.core.pdf.AndroidPdfOpener
import app.scanfit.core.pdf.PdfEngine
import app.scanfit.core.pdf.PdfJob
import app.scanfit.core.pdf.PdfOutcome
import app.scanfit.core.pdf.PdfProgress
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

/** What the list shows for an added file: a picture or a PDF (with its pages), and a small thumbnail. */
data class PdfFileInfo(
    val document: Boolean,
    val pages: Int,
    val thumbnail: Raster?,
    /** A PDF with a password: it cannot be added (ALGORITHMS 6.1). */
    val encrypted: Boolean = false,
)

/** The platform side of the PDF maker, behind an interface so [PdfMakerViewModel] is testable on the JVM. */
interface PdfMakerTools {
    /** The file's bytes (64 MiB bound), or `null` when it cannot be read. */
    suspend fun read(uri: String): ByteArray?

    /** A fresh `content://` URI for the camera app; [discardCaptures] deletes the captured file. */
    fun newCaptureUri(): String

    fun discardCaptures()

    /** `null` when the file is neither a picture we can decode nor a PDF we can open. */
    fun describe(bytes: ByteArray): PdfFileInfo?

    /** ALGORITHMS 1.1: upright, downsampled on decode. */
    fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster?

    suspend fun make(
        job: PdfJob,
        progress: (PdfProgress) -> Unit,
    ): PdfOutcome
}

class AndroidPdfMakerTools
@Inject
constructor(
    @ApplicationContext context: Context,
    private val source: ImageSource,
) : PdfMakerTools {
    private val opener = AndroidPdfOpener(context.cacheDir)
    private val engine = PdfEngine(opener, AndroidImageDecoder::decode, AndroidJpegEncoder)

    override suspend fun read(uri: String): ByteArray? = source.read(uri)

    override fun newCaptureUri(): String = source.newCaptureUri()

    override fun discardCaptures() = source.discardCaptures()

    override fun describe(bytes: ByteArray): PdfFileInfo? {
        val file = Inspector.inspect(bytes)
        if (file.format != DetectedFormat.PDF) {
            return AndroidImageDecoder.decode(bytes, THUMBNAIL)?.let { PdfFileInfo(document = false, pages = 1, it) }
        }
        if (file.pdfEncrypted) return PdfFileInfo(document = true, pages = 0, thumbnail = null, encrypted = true)
        return opener.open(bytes)?.use { pages ->
            if (pages.pageCount < 1) return@use null
            val size = pages.pageSize(0)
            val scale = THUMBNAIL / maxOf(size.width, size.height)
            val thumbnail = pages.render(0, maxOf(1, (size.width * scale).toInt()), maxOf(1, (size.height * scale).toInt()))
            PdfFileInfo(document = true, pages = pages.pageCount, thumbnail = thumbnail)
        }
    }

    override fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster? = AndroidImageDecoder.decode(bytes, maxLongSide)

    override suspend fun make(
        job: PdfJob,
        progress: (PdfProgress) -> Unit,
    ): PdfOutcome = engine.make(job, progress)

    private companion object {
        /** Twice a 56 dp row thumbnail at about 1.7x density. */
        const val THUMBNAIL = 192
    }
}

/** PDF work runs here, never on the main thread (CLAUDE.md rule 4). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PdfWork

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PdfModule {
    @Binds
    abstract fun pdfMakerTools(tools: AndroidPdfMakerTools): PdfMakerTools

    companion object {
        @Provides
        @PdfWork
        fun work(): CoroutineDispatcher = Dispatchers.Default
    }
}
