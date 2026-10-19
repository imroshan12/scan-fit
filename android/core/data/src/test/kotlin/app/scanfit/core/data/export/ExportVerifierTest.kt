package app.scanfit.core.data.export

import app.scanfit.core.match.DocKind
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import app.scanfit.core.testing.TestPdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportVerifierTest {
    private val spec = SpecPresets.bundle.exams.first { it.id == "ibps_po" }.documents.first()

    @Test
    fun unchangedMatchingBaselineBytesPassButAlteredBytesDoNot() {
        val bytes = jpeg()
        assertTrue(ExportVerifier.verifies(bytes, bytes.copyOf(), spec, DocKind.PHOTO))
        assertFalse(ExportVerifier.verifies(bytes, bytes.copyOf().apply { this[lastIndex] = 1 }, spec, DocKind.PHOTO))
    }

    @Test
    fun equalBytesStillRequireTheSpecifiedDensityAndBaseline() {
        val wrongDensity = jpeg().apply { this[15] = 72 }
        assertFalse(ExportVerifier.verifies(wrongDensity, wrongDensity, spec, DocKind.PHOTO))
        val progressive = jpeg().apply { this[21] = 0xc2.toByte() }
        assertFalse(ExportVerifier.verifies(progressive, progressive, spec, DocKind.PHOTO))
    }

    @Test
    fun anInkSlotIsCheckedAgainstItsOwnRules() {
        // IBPS signature: 140x60 preferred, 10-20 KB. A signature-sized file passes; a photo-sized one does not.
        val signature = SpecPresets.bundle.exams.first { it.id == "ibps_po" }.documents.first { it.type.name == "SIGNATURE" }
        val ok = TestJpeg.make(140, 60, 16 * 1024)
        assertTrue(ExportVerifier.verifies(ok, ok, signature, DocKind.SIGNATURE))
        val photoSized = TestJpeg.make(200, 230, 16 * 1024)
        assertFalse(ExportVerifier.verifies(photoSized, photoSized, signature, DocKind.SIGNATURE))
    }

    @Test
    fun aPdfIsCheckedForItsSlotPagesAndPassword() {
        // JEE Main class 10 certificate: PDF, 50-300 KB.
        val slot = SpecPresets.bundle.exams.first { it.id == "jee_main" }.documents.first { it.type.name == "CLASS10_CERTIFICATE" }
        val made = TestPdf.make(2, 120 * 1024)
        val pdf = PdfExport(pages = 2, written = true)
        assertTrue(ExportVerifier.verifiesPdf(made, made.copyOf(), slot, pdf))
        assertFalse("altered", ExportVerifier.verifiesPdf(made, made.copyOf().apply { this[200] = 'x'.code.toByte() }, slot, pdf))
        assertFalse("page count", ExportVerifier.verifiesPdf(made, made, slot, pdf.copy(pages = 3)))
        val small = TestPdf.make(2, 20 * 1024)
        assertFalse("under the minimum", ExportVerifier.verifiesPdf(small, small, slot, pdf))
        val locked = TestPdf.make(2, 120 * 1024, encrypted = true)
        assertFalse("password", ExportVerifier.verifiesPdf(locked, locked, slot, pdf))
        val text = TestPdf.make(2, 120 * 1024, font = true)
        assertFalse("the app writes image pages only", ExportVerifier.verifiesPdf(text, text, slot, pdf))
        assertTrue("an unchanged PDF may have text", ExportVerifier.verifiesPdf(text, text, slot, pdf.copy(written = false)))
        assertFalse("a PDF is not a JPEG", ExportVerifier.verifies(made, made, slot, DocKind.PDF_DOCUMENT))
    }

    @Test
    fun aRequestPicksTheCheckForItsFile() {
        val slot = SpecPresets.bundle.exams.first { it.id == "jee_main" }.documents.first { it.type.name == "CLASS10_CERTIFICATE" }
        val made = TestPdf.make(1, 90 * 1024)
        val request = ExportRequest("jee_main", "JEE Main", slot, DocKind.PDF_DOCUMENT, made, pdf = PdfExport(1, written = true))
        assertTrue(ExportVerifier.verifies(request, made))
        assertEquals("application/pdf", request.mimeType)
        assertEquals("class10_certificate_JEE-Main_90kb.pdf", request.fileName)
    }

    private fun jpeg(): ByteArray {
        val header = intArrayOf(
            0xff, 0xd8, 0xff, 0xe0, 0, 16, 74, 70, 73, 70, 0, 1, 1, 1, 0, 200, 0, 200, 0, 0,
            0xff, 0xc0, 0, 17, 8, 0, 230, 0, 200, 3, 1, 0x11, 0, 2, 0x11, 0, 3, 0x11, 0,
            0xff, 0xda, 0, 2,
        )
        return ByteArray(35 * 1024).apply {
            header.forEachIndexed { index, value -> this[index] = value.toByte() }
            this[lastIndex - 1] = 0xff.toByte()
            this[lastIndex] = 0xd9.toByte()
        }
    }
}
