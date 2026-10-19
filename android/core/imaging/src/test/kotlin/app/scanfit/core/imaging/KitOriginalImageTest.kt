package app.scanfit.core.imaging

import app.scanfit.core.inspect.ColorKind
import app.scanfit.core.inspect.Inspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The My Kit original (ALGORITHMS 13.1): bounded, baseline RGB, metadata stripped. */
class KitOriginalImageTest {
    @Test
    fun aLargePictureIsKeptAt2400OnTheLongSideAsACleanBaselineJpeg() {
        val bytes = requireNotNull(KitOriginalImage.encode(Raster.of(3000, 2000) { x, _ -> x and 0xFF }, ImageIoEncoder))
        val file = Inspector.inspect(bytes)
        assertEquals(2400 to 1600, file.width to file.height)
        assertEquals("SOF0", file.sof)
        assertEquals(ColorKind.RGB, file.color)
        assertFalse(file.hasExif || file.hasGps || file.hasXmp)
    }

    @Test
    fun aSmallPictureKeepsItsSize() {
        val bytes = requireNotNull(KitOriginalImage.encode(Raster.of(800, 600) { _, y -> y and 0xFF }, ImageIoEncoder))
        val file = Inspector.inspect(bytes)
        assertEquals(800 to 600, file.width to file.height)
        assertTrue(bytes.size > 0)
    }

    @Test
    fun anEncoderThatDoesNotMakeAJpegKeepsNothing() {
        assertNull(KitOriginalImage.encode(Raster.of(10, 10) { _, _ -> 0 }) { _, _ -> byteArrayOf(1, 2, 3) })
    }
}
