package app.scanfit.core.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOpsTest {
    @Test
    fun boxWidthsFollowTheBoxesForGaussFormula() {
        assertArrayEquals(intArrayOf(19, 19, 21), ImageOps.boxWidths(10.0))
        assertArrayEquals("sigma 1: ideal 2.24 -> wl 1, wu 3, m = 2", intArrayOf(1, 1, 3), ImageOps.boxWidths(1.0))
        assertTrue("always odd", (1..60).all { s -> ImageOps.boxWidths(s / 2.0).all { it % 2 == 1 } })
    }

    @Test
    fun blurKeepsAFlatPlaneFlatAndSpreadsAnImpulseSymmetrically() {
        val flat = IntArray(30 * 20) { 77 }
        assertArrayEquals(flat, ImageOps.gaussianApprox(flat, 30, 20, 3.0))
        val impulse = IntArray(31 * 31).also { it[15 * 31 + 15] = 255 }
        val blurred = ImageOps.gaussianApprox(impulse, 31, 31, 3.0)
        assertTrue("peak stays in the middle", blurred[15 * 31 + 15] == blurred.max())
        for (d in 1..6) {
            assertEquals("left/right symmetry at $d", blurred[15 * 31 + 15 - d], blurred[15 * 31 + 15 + d])
            assertEquals("up/down symmetry at $d", blurred[(15 - d) * 31 + 15], blurred[(15 + d) * 31 + 15])
        }
    }

    @Test
    fun tinySigmaLeavesThePlaneAlone() {
        val plane = IntArray(9) { it * 20 }
        assertArrayEquals(plane, ImageOps.gaussianApprox(plane, 3, 3, 0.2))
    }

    @Test
    fun sauvolaMarksADarkStrokeOnABrightBackground() {
        val w = 60
        val h = 40
        val n = IntArray(w * h) { if ((it / w) in 18..21) 20 else 250 }
        val m = ImageOps.sauvola(n, w, h, 15, 0.34, 128.0)
        assertEquals(1, m[20 * w + 30])
        assertEquals(0, m[5 * w + 30])
        assertEquals(0, m[35 * w + 10])
    }

    @Test
    fun openingKeepsBlocksRemovesSpecksAndKeepsBorderStrokes() {
        val w = 12
        val h = 12
        val m = IntArray(w * h)
        for (y in 2..5) for (x in 2..5) m[y * w + x] = 1 // 4x4 block survives
        m[9 * w + 9] = 1 // a lone pixel does not
        for (x in 0 until w) {
            m[0] = 1
            m[x] = 1
        } // a stroke on the top border
        val o = ImageOps.open3x3(m, w, h)
        assertEquals(1, o[3 * w + 3])
        assertEquals(0, o[9 * w + 9])
        assertEquals("a 1-px stroke is thinner than the 3x3 element and goes", 0, o[6])
        val thick = IntArray(w * h)
        for (y in 0..2) for (x in 0 until w) thick[y * w + x] = 1
        assertEquals("a 3-px stroke on the border survives", 1, ImageOps.open3x3(thick, w, h)[0])
    }

    @Test
    fun specksAreRemovedByComponentSizeUsingEightConnectivity() {
        val w = 10
        val h = 10
        val m = IntArray(w * h)
        m[1 * w + 1] = 1
        m[2 * w + 2] = 1
        m[3 * w + 3] = 1 // diagonal chain of 3: one 8-connected component
        for (x in 5..9) m[7 * w + x] = 1 // 5 pixels
        val out = ImageOps.removeSpecks(m, w, h, minSize = 4)
        assertEquals("3 < 4 removed", 0, out[2 * w + 2])
        assertEquals("5 >= 4 kept", 1, out[7 * w + 7])
        assertEquals("size equal to the limit is kept", 1, ImageOps.removeSpecks(m, w, h, minSize = 3)[2 * w + 2])
    }
}
