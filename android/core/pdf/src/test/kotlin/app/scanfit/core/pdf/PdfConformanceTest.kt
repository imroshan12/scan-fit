package app.scanfit.core.pdf

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** The pure parts of the PDF engine against spec/fixtures/cases.json (ALGORITHMS 9.10): layout, writer bytes, fit. */
class PdfConformanceTest {
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int

    private fun JsonObject.size(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.int }.let { PixelSize(it[0], it[1]) }

    private fun texts(vararg v: Double) = v.map(PdfWriter::num)

    private fun rect(r: PdfRect) = texts(r.x, r.y, r.width, r.height)

    @Test
    fun layoutCasesHold() {
        val cases = PdfCases.section("pdf_layout_cases")
        assertTrue(cases.size >= 5)
        for (c in cases) {
            val id = c.getValue("id").jsonPrimitive.content
            val expect = c.getValue("expect").jsonObject
            val dpis = PdfLayout.DPI_LADDER
            val (page, rects, pixels) =
                when (c.getValue("kind").jsonPrimitive.content) {
                    "image" -> {
                        val (size, placement) = PdfLayout.imagePage(c.int("w"), c.int("h"))
                        Triple(size, listOf(placement.rect), dpis.associateWith { listOf(PdfLayout.pixels(c.int("w"), c.int("h"), placement.scale, it)) })
                    }

                    "id_card" -> {
                        val front = c.size("front")
                        val back = c.size("back")
                        val (size, placements) = PdfLayout.idCardPage(front, back)
                        Triple(
                            size,
                            placements.map { it.rect },
                            dpis.associateWith { dpi ->
                                listOf(front, back).zip(placements).map { (side, p) -> PdfLayout.pixels(side.width, side.height, p.scale, dpi) }
                            },
                        )
                    }

                    else -> {
                        val size = PdfSize(c.getValue("w_pt").jsonPrimitive.double, c.getValue("h_pt").jsonPrimitive.double)
                        Triple(size, listOf(PdfRect(0.0, 0.0, size.width, size.height)), dpis.associateWith { listOf(PdfLayout.pdfPagePixels(size, it)) })
                    }
                }
            assertEquals(id, expect.getValue("page").jsonArray.map { it.jsonPrimitive.content }, texts(page.width, page.height))
            assertEquals(id, expect.getValue("rects").jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.content } }, rects.map(::rect))
            val expectedPixels = expect.getValue("pixels").jsonObject
            for (dpi in dpis) {
                val want = expectedPixels.getValue("$dpi").jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.int } }
                assertEquals("$id@$dpi", want, pixels.getValue(dpi).map { listOf(it.width, it.height) })
            }
        }
    }

    @Test
    fun writerCasesAreByteIdentical() {
        val cases = PdfCases.section("pdf_writer_cases")
        assertTrue(cases.size >= 5)
        for (c in cases) {
            val id = c.getValue("id").jsonPrimitive.content
            val pages = c.getValue("pages").jsonArray.map { writerPage(it.jsonObject) }
            val pad = c["pad_to"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long
            val bytes = if (pad != null) PdfWriter.writePadded(pages, pad) else PdfWriter.write(pages)
            val expect = c.getValue("expect").jsonObject
            assertEquals(id, expect.getValue("size").jsonPrimitive.int, bytes.size)
            assertEquals(id, expect.getValue("sha256").jsonPrimitive.content, sha256(bytes))
            // The measured size equals the written one (the fit relies on it).
            val geometry = pages.map { p -> PageGeometry(p.size, p.images.map { PlacedPixels(it.width, it.height, it.rect) }) }
            if (pad == null) assertEquals(id, bytes.size.toLong(), PdfWriter.size(geometry, pages.map { p -> p.images.map { it.jpeg.size } }))
        }
    }

    private fun writerPage(page: JsonObject): WriterPage {
        val images = page.getValue("images").jsonArray.map { it.jsonObject }
        fun jpeg(o: JsonObject) = PdfCases.file(o.getValue("file").jsonPrimitive.content)
        fun px(o: JsonObject) = PixelSize(o.int("w"), o.int("h"))
        return when (page.getValue("kind").jsonPrimitive.content) {
            "image" -> {
                val img = images.single()
                val (size, placement) = PdfLayout.imagePage(img.int("w"), img.int("h"))
                WriterPage(size, listOf(WriterImage(jpeg(img), img.int("w"), img.int("h"), placement.rect)))
            }

            "id_card" -> {
                val (size, placements) = PdfLayout.idCardPage(px(images[0]), px(images[1]))
                WriterPage(size, images.zip(placements).map { (img, p) -> WriterImage(jpeg(img), img.int("w"), img.int("h"), p.rect) })
            }

            else -> {
                val size = PdfSize(page.getValue("w_pt").jsonPrimitive.double, page.getValue("h_pt").jsonPrimitive.double)
                val img = images.single()
                WriterPage(size, listOf(WriterImage(jpeg(img), img.int("w"), img.int("h"), PdfRect(0.0, 0.0, size.width, size.height))))
            }
        }
    }

    @Test
    fun fitCasesHold() {
        val cases = PdfCases.section("pdf_fit_cases")
        assertTrue(cases.size >= 5)
        for (c in cases) {
            val id = c.getValue("id").jsonPrimitive.content
            val expect = c.getValue("expect").jsonObject
            val window = PdfWindow.of(PdfCases.sizeKb(c.getValue("size_kb").jsonObject))
            if (window == null) {
                assertEquals(id, "unknown_limit", expect.getValue("error").jsonPrimitive.content)
                continue
            }
            val sizes = c.getValue("sizes").jsonObject.entries.associate { (dpi, list) -> dpi.toInt() to list.jsonArray.map { it.jsonPrimitive.long } }
            val choice = PdfFit.choose(window, sizes)
            if (expect.containsKey("error")) {
                assertNull(id, choice)
                assertEquals(id, "cannot_reach", expect.getValue("error").jsonPrimitive.content)
                continue
            }
            checkNotNull(choice) { id }
            assertEquals(id, expect.int("dpi"), choice.dpi)
            assertEquals(id, expect.int("quality"), choice.quality)
            assertEquals(id, expect["pad_to"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long, choice.padTo)
            assertEquals(id, expect.getValue("strategy").jsonPrimitive.content, choice.strategy.wire)
        }
    }

    @Test
    fun numbersHaveAtMostTwoDecimalsAndNoTrailingZeros() {
        assertEquals(listOf("29.75", "30.5", "595", "0.33", "0", "100.1"), texts(29.75, 30.5, 595.0, 0.333, 0.001, 100.1))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
