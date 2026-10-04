package app.scanfit.feature.flowphoto

import app.scanfit.core.testing.SpecPresets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoExportVerifierTest {
    private val spec = SpecPresets.bundle.exams.first { it.id == "ibps_po" }.documents.first()

    @Test
    fun unchangedMatchingBaselineBytesPassButAlteredBytesDoNot() {
        val bytes = jpeg()
        assertTrue(PhotoExportVerifier.verifies(bytes, bytes.copyOf(), spec))
        assertFalse(PhotoExportVerifier.verifies(bytes, bytes.copyOf().apply { this[lastIndex] = 1 }, spec))
    }

    @Test
    fun equalBytesStillRequireTheSpecifiedDensityAndBaseline() {
        val wrongDensity = jpeg().apply { this[15] = 72 }
        assertFalse(PhotoExportVerifier.verifies(wrongDensity, wrongDensity, spec))
        val progressive = jpeg().apply { this[21] = 0xc2.toByte() }
        assertFalse(PhotoExportVerifier.verifies(progressive, progressive, spec))
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
