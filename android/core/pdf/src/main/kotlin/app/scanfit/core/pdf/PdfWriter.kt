package app.scanfit.core.pdf

import java.io.ByteArrayOutputStream

/** One page to write: its size and the placed JPEGs, drawn in order as /Im1, /Im2. */
class WriterPage(
    val size: PdfSize,
    val images: List<WriterImage>,
)

class WriterImage(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val rect: PdfRect,
)

/**
 * The image PDF the app writes (ALGORITHMS 6.4 / 9.10): PDF 1.4, no metadata, no fonts, one DCT image XObject per placed
 * JPEG. Byte-identical to the Swift writer and the Python reference (`pdf_writer_cases`). Pure.
 */
object PdfWriter {
    private const val HUNDRED = 100
    private const val TEN = 10
    private const val HALF = 0.5
    private val HEADER = "%PDF-1.4\n%".toByteArray() + byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte()) +
        "\n".toByteArray()

    /** `num(v)` of ALGORITHMS 9.10: at most two decimals, no trailing zeros. */
    fun num(v: Double): String {
        val c = kotlin.math.floor(v * HUNDRED + HALF).toLong()
        val whole = c / HUNDRED
        val frac = c % HUNDRED
        return when {
            frac == 0L -> "$whole"
            c % TEN == 0L -> "$whole.${frac / TEN}"
            else -> "$whole.${frac.toString().padStart(2, '0')}"
        }
    }

    fun write(
        pages: List<WriterPage>,
        pad: Int? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream(pages.sumOf { page -> page.images.sumOf { it.jpeg.size } } + OVERHEAD_HINT)
        emit(pages, pad, Sink.Bytes(out))
        return out.toByteArray()
    }

    /** The size [write] would produce for these pages with images of [lengths] bytes, without the image data. */
    fun size(
        pages: List<PageGeometry>,
        lengths: List<List<Int>>,
        pad: Int? = null,
    ): Long {
        val placeholders =
            pages.map { page ->
                WriterPage(page.size, page.images.map { WriterImage(EMPTY, it.width, it.height, it.rect) })
            }
        val sink = Sink.Count()
        emit(placeholders, pad, sink) { p, i -> lengths[p][i] }
        return sink.count
    }

    /** ALGORITHMS 9.10 Pad: the smallest padding object that makes the PDF at least [target] bytes (none if already). */
    fun writePadded(
        pages: List<WriterPage>,
        target: Long,
    ): ByteArray {
        val plain = write(pages)
        if (plain.size >= target) return plain
        val geometry = pages.map { page -> PageGeometry(page.size, page.images.map { PlacedPixels(it.width, it.height, it.rect) }) }
        val lengths = pages.map { page -> page.images.map { it.jpeg.size } }
        fun sized(p: Int) = size(geometry, lengths, p)
        var p = maxOf(0L, target - sized(0)).toInt()
        while (sized(p) < target) p++
        while (p > 0 && sized(p - 1) >= target) p--
        return write(pages, p)
    }

    private sealed interface Sink {
        fun text(s: String)

        fun data(
            bytes: ByteArray,
            length: Int,
        )

        val position: Long

        class Bytes(
            private val out: ByteArrayOutputStream,
        ) : Sink {
            override fun text(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))

            override fun data(
                bytes: ByteArray,
                length: Int,
            ) = out.write(bytes)

            override val position: Long get() = out.size().toLong()
        }

        class Count : Sink {
            var count = 0L

            override fun text(s: String) {
                count += s.length
            }

            override fun data(
                bytes: ByteArray,
                length: Int,
            ) {
                count += length
            }

            override val position: Long get() = count
        }
    }

    private fun emit(
        pages: List<WriterPage>,
        pad: Int?,
        sink: Sink,
        lengthOf: (page: Int, image: Int) -> Int = { p, i -> pages[p][i].jpeg.size },
    ) {
        sink.data(HEADER, HEADER.size)
        val offsets = mutableListOf<Long>()
        fun obj(
            number: Int,
            body: () -> Unit,
        ) {
            offsets += sink.position
            sink.text("$number 0 obj\n")
            body()
            sink.text("\nendobj\n")
        }

        var next = FIRST_PAGE_OBJECT
        val numbers =
            pages.map { page ->
                val first = next
                next += 2 + page.images.size
                first
            }
        obj(1) { sink.text("<< /Type /Catalog /Pages 2 0 R >>") }
        val kids = numbers.joinToString(" ") { "$it 0 R" }
        obj(2) { sink.text("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>") }
        pages.forEachIndexed { p, page ->
            val pageNo = numbers[p]
            val contentNo = pageNo + 1
            val xobjects = page.images.indices.joinToString(" ") { "/Im${it + 1} ${contentNo + 1 + it} 0 R" }
            obj(pageNo) {
                sink.text(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(page.size.width)} ${num(page.size.height)}] " +
                        "/Resources << /XObject << $xobjects >> >> /Contents $contentNo 0 R >>",
                )
            }
            val content =
                buildString {
                    page.images.forEachIndexed { j, image ->
                        val r = image.rect
                        append("q ${num(r.width)} 0 0 ${num(r.height)} ${num(r.x)} ")
                        append("${num(page.size.height - r.y - r.height)} cm /Im${j + 1} Do Q\n")
                    }
                }
            obj(contentNo) { sink.text("<< /Length ${content.length} >>\nstream\n${content}\nendstream") }
            page.images.forEachIndexed { j, image ->
                val length = lengthOf(p, j)
                obj(contentNo + 1 + j) {
                    sink.text(
                        "<< /Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
                            "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length $length >>\nstream\n",
                    )
                    sink.data(image.jpeg, length)
                    sink.text("\nendstream")
                }
            }
        }
        if (pad != null) obj(next) { sink.text("<< /Length $pad >>\nstream\n${" ".repeat(pad)}\nendstream") }
        val xref = sink.position
        val count = offsets.size + 1
        sink.text("xref\n0 $count\n0000000000 65535 f \n")
        for (offset in offsets) sink.text("${offset.toString().padStart(OFFSET_DIGITS, '0')} 00000 n \n")
        sink.text("trailer\n<< /Size $count /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    }

    private operator fun WriterPage.get(i: Int) = images[i]

    private const val FIRST_PAGE_OBJECT = 3
    private const val OFFSET_DIGITS = 10
    private const val OVERHEAD_HINT = 4096
    private val EMPTY = ByteArray(0)
}

/** A page's geometry without image data, for measuring sizes ([PdfWriter.size]). */
class PageGeometry(
    val size: PdfSize,
    val images: List<PlacedPixels>,
)

class PlacedPixels(
    val width: Int,
    val height: Int,
    val rect: PdfRect,
)
