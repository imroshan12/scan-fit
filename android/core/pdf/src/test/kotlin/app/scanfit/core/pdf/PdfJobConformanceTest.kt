package app.scanfit.core.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.scanfit.core.imaging.AndroidImageDecoder
import app.scanfit.core.imaging.AndroidJpegEncoder
import app.scanfit.core.inspect.DetectedFormat
import app.scanfit.core.inspect.Inspector
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.minutes

/**
 * Every `pdf_job_cases` entry (ALGORITHMS 9.10) with the real Skia encoder and decoder; PDF pages come from the
 * fixture renderer because Robolectric cannot run `PdfRenderer`. Includes the Phase 3 exit gate: the sample PDFs
 * compress into each target.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfJobConformanceTest {
    private val engine = PdfEngine(FixturePdfOpener(), AndroidImageDecoder::decode, AndroidJpegEncoder)

    @Test
    fun everyJobCaseHolds() = runTest(timeout = 10.minutes) {
        val cases = PdfCases.section("pdf_job_cases")
        assertTrue(cases.size >= 8)
        for (c in cases) {
            val id = c.getValue("id").jsonPrimitive.content
            val sources =
                c.getValue("sources").jsonArray.map { it.jsonObject }.map { s ->
                    s["pdf"]?.let { PdfSource.Document(PdfCases.file(it.jsonPrimitive.content)) }
                        ?: PdfSource.Image(PdfCases.file(s.getValue("image").jsonPrimitive.content))
                }
            val options = c.getValue("options").jsonObject
            val job =
                PdfJob(
                    checkNotNull(PdfTool.of(c.getValue("tool").jsonPrimitive.content)) { id },
                    sources,
                    PdfCases.sizeKb(c.getValue("size_kb").jsonObject),
                    PdfOptions(options.getValue("grayscale").jsonPrimitive.boolean, options.getValue("cleanup").jsonPrimitive.boolean),
                )
            val expect = c.getValue("expect").jsonObject
            val outcome = engine.make(job)
            expect["error"]?.let {
                assertEquals(id, it.jsonPrimitive.content, (outcome as? PdfOutcome.Failed)?.error?.wire)
                continue
            }
            val made = (outcome as? PdfOutcome.Made)?.pdf ?: error("$id: $outcome")
            val file = Inspector.inspect(made.bytes)
            val kb = made.bytes.size / 1024.0
            assertEquals(id, DetectedFormat.PDF, file.format)
            assertFalse(id, file.pdfEncrypted)
            assertEquals(id, expect.getValue("pages").jsonPrimitive.int, made.pages)
            assertEquals(id, made.pages, file.pdfPages)
            assertTrue("$id: $kb KB", kb >= expect.getValue("kb_min").jsonPrimitive.double)
            assertTrue("$id: $kb KB", kb <= expect.getValue("kb_max").jsonPrimitive.double)
            expect["image_only"]?.let { assertEquals(id, it.jsonPrimitive.boolean, file.pdfImageOnly) }
            expect["strategy"]?.let { assertEquals(id, it.jsonPrimitive.content, made.strategy.wire) }
            if (expect["same_bytes"]?.jsonPrimitive?.boolean == true) {
                assertArrayEquals(id, (sources.single() as PdfSource.Document).bytes, made.bytes)
            }
            assertTrue(id, made.preview != null)
        }
    }
}
