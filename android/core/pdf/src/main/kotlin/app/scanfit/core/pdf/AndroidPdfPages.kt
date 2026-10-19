package app.scanfit.core.pdf

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.scanfit.core.imaging.Raster
import app.scanfit.core.imaging.toRaster
import java.io.File
import java.io.IOException

/**
 * Android's `PdfRenderer` over a private temporary copy of the bytes (it needs a seekable file descriptor). The copy is
 * deleted on close; nothing is logged. Robolectric cannot run `PdfRenderer`, so this class is checked on devices.
 */
class AndroidPdfOpener(
    private val cacheDir: File,
) : PdfOpener {
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun open(bytes: ByteArray): PdfPages? {
        val file = File.createTempFile("render", ".pdf", cacheDir)
        return try {
            file.writeBytes(bytes)
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            AndroidPdfPages(PdfRenderer(descriptor), descriptor, file)
        } catch (error: IOException) {
            file.delete()
            null
        } catch (error: RuntimeException) {
            // SecurityException for a password, IllegalArgumentException for a damaged file.
            file.delete()
            null
        }
    }
}

private class AndroidPdfPages(
    private val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
    private val file: File,
) : PdfPages {
    override val pageCount: Int get() = renderer.pageCount

    override fun pageSize(index: Int): PdfSize = renderer.openPage(index).use {
        PdfSize(it.width.toDouble(), it.height.toDouble())
    }

    override fun render(
        index: Int,
        width: Int,
        height: Int,
    ): Raster = renderer.openPage(index).use { page ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val matrix = Matrix().apply { setScale(width.toFloat() / page.width, height.toFloat() / page.height) }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap.toRaster()
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        renderer.close()
        descriptor.close()
        file.delete()
    }
}
