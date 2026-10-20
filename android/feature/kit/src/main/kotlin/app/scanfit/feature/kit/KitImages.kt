package app.scanfit.feature.kit

import android.content.Context
import app.scanfit.core.imaging.AndroidImageDecoder
import app.scanfit.core.imaging.Raster
import app.scanfit.core.pdf.AndroidPdfOpener
import app.scanfit.core.pdf.PdfPages
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** Decodes Kit files for thumbnails and previews, behind an interface so the view models run on the JVM. */
interface KitImages {
    /** ALGORITHMS 1.1: downsampled on decode to at most [maxLongSide]; `null` when the bytes are not an image. */
    fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster?

    /** A PDF opened for page previews (close it when done); `null` when it does not open. */
    fun openPdf(bytes: ByteArray): PdfPages? = null
}

/** A PDF's first page scaled so its long side is [maxLongSide] (a history thumbnail). */
internal fun KitImages.pdfFirstPage(
    bytes: ByteArray,
    maxLongSide: Int,
): Raster? = openPdf(bytes)?.use { pages ->
    if (pages.pageCount < 1) return@use null
    val size = pages.pageSize(0)
    val scale = maxLongSide / maxOf(size.width, size.height)
    pages.render(0, maxOf(1, (size.width * scale).toInt()), maxOf(1, (size.height * scale).toInt()))
}

class AndroidKitImages
@Inject
constructor(
    @ApplicationContext context: Context,
) : KitImages {
    private val opener = AndroidPdfOpener(context.cacheDir)

    override fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster? = AndroidImageDecoder.decode(bytes, maxLongSide)

    override fun openPdf(bytes: ByteArray): PdfPages? = opener.open(bytes)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class KitModule {
    @Binds
    abstract fun kitImages(images: AndroidKitImages): KitImages
}
