package app.scanfit.core.pdf

import android.graphics.BitmapFactory
import app.scanfit.core.imaging.Raster
import app.scanfit.core.imaging.Resampler
import app.scanfit.core.imaging.toRaster

/**
 * A test-only renderer for the simple PDFs in spec/fixtures/pdfs and the app's own output: Robolectric cannot run
 * `PdfRenderer`. It reads uncompressed page dictionaries, draws each DCT image XObject where the content stream puts
 * it, and leaves everything else (text) white. Not a PDF parser: anything it does not understand opens as `null`.
 */
class FixturePdfOpener : PdfOpener {
    override fun open(bytes: ByteArray): PdfPages? {
        val text = String(bytes, Charsets.ISO_8859_1)
        val objects = Regex("(\\d+) 0 obj\\n").findAll(text).associate { it.groupValues[1].toInt() to it.range.last + 1 }
        val kids = Regex("/Kids \\[([^\\]]*)]").find(text)?.groupValues?.get(1) ?: return null
        val pages =
            Regex("(\\d+) 0 R").findAll(kids).map { it.groupValues[1].toInt() }.toList().map { number ->
                val start = objects[number] ?: return null
                val body = text.substring(start, text.indexOf("endobj", start))
                val box = Regex("/MediaBox \\[0 0 ([\\d.]+) ([\\d.]+)]").find(body) ?: return null
                val images = Regex("/Im(\\d+) (\\d+) 0 R").findAll(body).associate { it.groupValues[1] to it.groupValues[2].toInt() }
                val contents = Regex("/Contents (\\d+) 0 R").find(body)?.groupValues?.get(1)?.toInt()
                val draws =
                    contents?.let { stream(bytes, text, objects[it] ?: return null) }?.let { content ->
                        Regex("q ([\\d.]+) 0 0 ([\\d.]+) ([\\d.]+) ([\\d.]+) cm /Im(\\d+) Do Q").findAll(String(content, Charsets.ISO_8859_1))
                            .toList()
                            .map { m ->
                                val (w, h, x, y) = m.groupValues.subList(1, 5).map(String::toDouble)
                                val image = images[m.groupValues[5]] ?: return null
                                Draw(stream(bytes, text, objects[image] ?: return null), w, h, x, y)
                            }
                    }.orEmpty()
                FixturePage(PdfSize(box.groupValues[1].toDouble(), box.groupValues[2].toDouble()), draws)
            }
        return Pages(pages)
    }

    private fun stream(
        bytes: ByteArray,
        text: String,
        start: Int,
    ): ByteArray {
        val length = Regex("/Length (\\d+)").find(text, start)!!.groupValues[1].toInt()
        val data = text.indexOf("stream\n", start) + "stream\n".length
        return bytes.copyOfRange(data, data + length)
    }

    private class Draw(
        val jpeg: ByteArray,
        val w: Double,
        val h: Double,
        val x: Double,
        val y: Double,
    )

    private class FixturePage(
        val size: PdfSize,
        val draws: List<Draw>,
    )

    private class Pages(
        private val pages: List<FixturePage>,
    ) : PdfPages {
        override val pageCount: Int get() = pages.size

        override fun pageSize(index: Int): PdfSize = pages[index].size

        override fun render(
            index: Int,
            width: Int,
            height: Int,
        ): Raster {
            val page = pages[index]
            val canvas = Raster.white(width, height)
            val sx = width / page.size.width
            val sy = height / page.size.height
            for (draw in page.draws) {
                val bitmap = BitmapFactory.decodeByteArray(draw.jpeg, 0, draw.jpeg.size)
                val raster = bitmap.toRaster().also { bitmap.recycle() }
                val w = maxOf(1, (draw.w * sx).toInt())
                val h = maxOf(1, (draw.h * sy).toInt())
                val top = ((page.size.height - draw.y - draw.h) * sy).toInt()
                val left = (draw.x * sx).toInt()
                val scaled = Resampler.resize(raster, w, h)
                for (row in 0 until h) {
                    val cy = top + row
                    if (cy !in 0 until height) continue
                    val count = minOf(w, width - left)
                    if (count <= 0 || left < 0) continue
                    System.arraycopy(scaled.rgb, row * w * 3, canvas.rgb, (cy * width + left) * 3, count * 3)
                }
            }
            return canvas
        }

        override fun close() = Unit
    }
}
