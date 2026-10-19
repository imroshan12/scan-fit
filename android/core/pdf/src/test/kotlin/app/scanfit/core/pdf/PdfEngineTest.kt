package app.scanfit.core.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.scanfit.core.imaging.AndroidImageDecoder
import app.scanfit.core.imaging.AndroidJpegEncoder
import app.scanfit.core.imaging.CropRect
import app.scanfit.core.imaging.Raster
import app.scanfit.core.model.SizeKb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Engine behaviour beyond the conformance cases: checks, progress, cancellation, turns, crop and colour. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfEngineTest {
    private val engine = PdfEngine(FixturePdfOpener(), AndroidImageDecoder::decode, AndroidJpegEncoder)
    private val limit = SizeKb(max = 200.0)

    private fun picture(
        w: Int,
        h: Int,
        color: Int = 0xC03030,
    ): ByteArray = AndroidJpegEncoder.encode(Raster.of(w, h) { x, _ -> if (x < w / 2) color else 0xF0F0F0 }, 90)

    private fun text(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    private suspend fun made(job: PdfJob): PdfMade = when (val outcome = engine.make(job)) {
        is PdfOutcome.Made -> outcome.pdf
        is PdfOutcome.Failed -> error("failed: ${outcome.error}")
    }

    private suspend fun error(job: PdfJob): PdfError? = (engine.make(job) as? PdfOutcome.Failed)?.error

    @Test
    fun anIdCardNeedsExactlyTwoPictures() = runTest {
        val one = PdfJob(PdfTool.ID_CARD, listOf(PdfSource.Image(picture(40, 30))), limit)
        assertEquals(PdfError.NO_PAGES, error(one))
        val pdf = PdfSource.Document(PdfCases.file("pdfs/text_letter_1p.pdf"))
        assertEquals(PdfError.NO_PAGES, error(PdfJob(PdfTool.ID_CARD, listOf(PdfSource.Image(picture(40, 30)), pdf), limit)))
    }

    @Test
    fun moreThanTwentyPagesAreRefusedBeforeAnyWork() = runTest {
        val pictures = List(21) { PdfSource.Image(ByteArray(0)) }
        assertEquals(PdfError.TOO_MANY_PAGES, error(PdfJob(PdfTool.IMAGES, pictures, limit)))
    }

    @Test
    fun undecodablePicturesAndUnreadablePdfsAreUnreadable() = runTest {
        assertEquals(PdfError.UNREADABLE, error(PdfJob(PdfTool.IMAGES, listOf(PdfSource.Image(ByteArray(10))), limit)))
        val damaged = PdfSource.Document("%PDF-1.4\nnot really".toByteArray())
        assertEquals(PdfError.UNREADABLE, error(PdfJob(PdfTool.COMPRESS, listOf(damaged), limit)))
    }

    @Test
    fun aSlotWithoutMaximumIsUnknown() = runTest {
        val job = PdfJob(PdfTool.EXAM, listOf(PdfSource.Image(picture(40, 30))), SizeKb(min = 50.0))
        assertEquals(PdfError.UNKNOWN_LIMIT, error(job))
    }

    @Test
    fun progressCoversPreparingAndEveryPage() = runTest {
        val events = mutableListOf<PdfProgress>()
        val job = PdfJob(PdfTool.IMAGES, listOf(PdfSource.Image(picture(60, 80)), PdfSource.Image(picture(80, 60))), limit)
        val pdf = when (val outcome = engine.make(job) { events += it }) {
            is PdfOutcome.Made -> outcome.pdf
            is PdfOutcome.Failed -> error("failed")
        }
        assertEquals(2, pdf.pages)
        assertTrue(PdfProgress.Preparing(0, 2) in events && PdfProgress.Preparing(1, 2) in events)
        assertTrue(PdfProgress.Fitting(1, 2) in events)
        // A wide picture gets a landscape page.
        assertTrue(text(pdf.bytes).contains("/MediaBox [0 0 595 842]"))
        assertTrue(text(pdf.bytes).contains("/MediaBox [0 0 842 595]"))
    }

    @Test
    fun cancellingStopsTheJob() = runTest {
        val job = PdfJob(PdfTool.IMAGES, List(3) { PdfSource.Image(picture(60, 80)) }, limit)
        try {
            coroutineScope { engine.make(job) { if (it is PdfProgress.Preparing && it.done == 1) cancel() } }
            fail("expected cancellation")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test
    fun turnsAndCropApplyBeforeLayout() = runTest {
        // 200×100 turned once is 100×200 (portrait); the crop keeps its top half: 100×100 in the editor's pixels.
        val source = PdfSource.Image(picture(200, 100), quarterTurns = 1, crop = CropRect(0, 0, 100, 100), cropBase = PixelSize(100, 200))
        val pdf = made(PdfJob(PdfTool.IMAGES, listOf(source), limit, PdfOptions(grayscale = false, cleanup = false)))
        assertTrue(text(pdf.bytes).contains("/Width 100 /Height 100"))
        assertTrue(text(pdf.bytes).contains("/MediaBox [0 0 595 842]"))
    }

    @Test
    fun colourIsKeptOnlyWithGrayscaleOff() = runTest {
        fun redness(preview: Raster): Int {
            var most = 0
            for (y in 0 until preview.height) for (x in 0 until preview.width) most = maxOf(most, preview.r(x, y) - preview.g(x, y))
            return most
        }
        // Black "text", a red stamp and paper: cleanup maps the darkest ink to black, so the stamp must not be the darkest.
        val page = Raster.of(120, 160) { x, _ -> if (x < 30) 0x101010 else if (x < 60) 0xC03030 else 0xF0F0F0 }
        val source = listOf(PdfSource.Image(AndroidJpegEncoder.encode(page, 90)))
        val colour = made(PdfJob(PdfTool.IMAGES, source, limit, PdfOptions(grayscale = false, cleanup = false)))
        val grey = made(PdfJob(PdfTool.IMAGES, source, limit, PdfOptions(grayscale = true, cleanup = false)))
        val cleaned = made(PdfJob(PdfTool.IMAGES, source, limit, PdfOptions(grayscale = false, cleanup = true)))
        assertTrue(redness(checkNotNull(colour.preview)) > 80)
        assertTrue(redness(checkNotNull(grey.preview)) <= 2)
        assertTrue(redness(checkNotNull(cleaned.preview)) > 40)
    }

    @Test
    fun anUnchangedPdfStillHasAPreviewAndItsPageCount() = runTest {
        val bytes = PdfCases.file("pdfs/marksheet_scan_2p.pdf")
        val pdf = made(PdfJob(PdfTool.COMPRESS, listOf(PdfSource.Document(bytes)), SizeKb(max = 2000.0)))
        assertEquals(PdfStrategy.UNCHANGED, pdf.strategy)
        assertEquals(2, pdf.pages)
        assertTrue(checkNotNull(pdf.preview).height <= PdfEngine.PREVIEW_LONG_SIDE.toInt())
    }
}
