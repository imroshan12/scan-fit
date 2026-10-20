package app.scanfit.feature.kit

import app.scanfit.core.imaging.Raster
import app.scanfit.core.pdf.PdfPages
import app.scanfit.core.pdf.PdfSize

/** Decodes nothing: answers a raster of the asked size, and remembers the cap. PDFs open as [pdfPageCount] A4 pages. */
class FakeKitImages(
    private val pdfPageCount: Int = 3,
) : KitImages {
    val caps = mutableListOf<Int>()
    var opened = 0
    var closed = 0

    override fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster {
        caps += maxLongSide
        return Raster.white(maxLongSide, maxLongSide)
    }

    override fun openPdf(bytes: ByteArray): PdfPages {
        opened++
        return object : PdfPages {
            override val pageCount = pdfPageCount

            override fun pageSize(index: Int) = PdfSize(595.0, 842.0)

            override fun render(
                index: Int,
                width: Int,
                height: Int,
            ) = Raster.white(width, height)

            override fun close() {
                closed++
            }
        }
    }
}
