package app.scanfit.core.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ALGORITHMS 9.6: confidence -> hard 0/255 mask at 0.5, resampled bilinearly to the raster size first. */
class PersonMaskTest {
    private fun alpha(b: Byte) = b.toInt() and 0xFF

    @Test
    fun sameSizeIsAHardThresholdAtOneHalf() {
        val out = PersonMask.fromConfidence(floatArrayOf(0f, 0.49f, 0.5f, 0.98f), 4, 1, 4, 1)
        assertArrayEquals(intArrayOf(0, 0, 255, 255), out.map(::alpha).toIntArray())
    }

    @Test
    fun aSmallerModelMaskIsResampledToTheRaster() {
        // left column person, right column background -> the left half of an 8x4 raster is person
        val out = PersonMask.fromConfidence(floatArrayOf(1f, 0f, 1f, 0f), 2, 2, 8, 4)
        assertEquals(32, out.size)
        for (y in 0 until 4) {
            assertEquals(255, alpha(out[y * 8]))
            assertEquals(255, alpha(out[y * 8 + 3]))
            assertEquals(0, alpha(out[y * 8 + 4]))
            assertEquals(0, alpha(out[y * 8 + 7]))
        }
    }

    @Test
    fun onlyZeroAnd255AreProduced() {
        val conf = FloatArray(16 * 16) { (it % 17) / 16f }
        val out = PersonMask.fromConfidence(conf, 16, 16, 37, 23)
        assertTrue(out.all { alpha(it) == 0 || alpha(it) == 255 })
    }

    @Test
    fun aWrongSizedConfidenceIsRejected() {
        try {
            PersonMask.fromConfidence(FloatArray(3), 2, 2, 4, 4)
            error("must reject")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
