package app.scanfit.core.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RasterTest {
    @Test
    fun lumaUsesTheIntegerFormula() {
        val r = Raster.of(3, 1) { x, _ -> intArrayOf(0xFFFFFF, 0x000000, 0xFF0000)[x] }
        assertArrayEquals(byteArrayOf(255.toByte(), 0, 76), r.luma()) // (299*255+500)/1000 = 76
    }

    @Test
    fun cropFillsOutsideWithWhiteAndHandlesNegativeOrigins() {
        val r = Raster.of(4, 4) { _, _ -> 0x102030 }
        val c = r.crop(-1, -1, 3, 3)
        assertEquals(0xFF, c.r(0, 0)) // outside = white
        assertEquals(0x10, c.r(1, 1)) // original (0,0)
        assertEquals(0x20, c.g(2, 2))
        val far = r.crop(10, 10, 2, 2)
        assertTrue((0 until 2).all { y -> (0 until 2).all { x -> far.r(x, y) == 0xFF } })
        assertEquals(0x10, r.crop(2, 2, 4, 4).r(0, 0))
        assertEquals(0xFF, r.crop(2, 2, 4, 4).r(3, 3))
    }

    @Test
    fun invalidBuffersAreRejected() {
        for (bad in listOf({ Raster(0, 1, ByteArray(0)) }, { Raster(2, 2, ByteArray(5)) })) {
            try {
                bad()
                error("must reject")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }
}

class ResamplerTest {
    @Test
    fun sameSizeIsTheIdentity() {
        val r = noisyRaster(10, 8)
        assertTrue(Resampler.resize(r, 10, 8) === r)
    }

    @Test
    fun aFlatColourStaysFlatInBothDirections() {
        val flat = Raster.of(7, 5) { _, _ -> 0x336699 }
        for ((w, h) in listOf(3 to 2, 14 to 10, 7 to 9, 11 to 5, 1 to 1)) {
            val out = Resampler.resize(flat, w, h)
            assertEquals("${w}x$h", w to h, out.width to out.height)
            assertTrue(
                (0 until h).all { y ->
                    (0 until w).all { x ->
                        out.r(x, y) == 0x33 && out.g(x, y) == 0x66 &&
                            out.b(x, y) == 0x99
                    }
                },
            )
        }
    }

    @Test
    fun halvingAveragesTwoByTwoBlocksExactly() {
        val r = Raster.of(2, 2) { x, y -> if ((x + y) % 2 == 0) 0xFFFFFF else 0x000000 }
        assertEquals(128, Resampler.resize(r, 1, 1).r(0, 0)) // 127.5 rounds half up
    }

    @Test
    fun enlargingInterpolatesSmoothlyAndKeepsTheEnds() {
        val r = Raster.of(2, 1) { x, _ -> if (x == 0) 0x000000 else 0xFFFFFF }
        val big = Resampler.resize(r, 8, 1)
        assertEquals(0, big.r(0, 0))
        assertEquals(255, big.r(7, 0))
        assertTrue("monotonic", (1 until 8).all { big.r(it, 0) >= big.r(it - 1, 0) })
    }

    @Test
    fun nonIntegerShrinkWeightsPartialPixels() {
        val r = Raster.of(3, 1) { x, _ -> intArrayOf(0, 255, 0)[x] * 0x010101 }
        val out = Resampler.resize(r, 2, 1) // each output covers 1.5 source pixels
        assertEquals(85, out.r(0, 0)) // (0*1 + 255*0.5)/1.5
        assertEquals(85, out.r(1, 0))
    }
}

class OrientationTest {
    private fun corner(r: Raster) = listOf(
        r.r(0, 0),
        r.r(r.width - 1, 0),
        r.r(0, r.height - 1),
        r.r(
            r.width - 1,
            r.height - 1,
        ),
    )

    /** 3x2 picture with distinct corners: TL=10 TR=20 BL=30 BR=40. */
    private val base = Raster.of(3, 2) { x, y -> intArrayOf(10, 99, 20, 30, 99, 40)[y * 3 + x] * 0x010101 }

    @Test
    fun everyOrientationMapsTheCornersLikeExif() {
        val expected =
            mapOf(
                1 to listOf(10, 20, 30, 40),
                2 to listOf(20, 10, 40, 30),
                3 to listOf(40, 30, 20, 10),
                4 to listOf(30, 40, 10, 20),
                5 to listOf(10, 30, 20, 40),
                6 to listOf(30, 10, 40, 20),
                7 to listOf(40, 20, 30, 10),
                8 to listOf(20, 40, 10, 30),
            )
        for ((k, want) in expected) {
            val out = Orientation.apply(base, k)
            assertEquals("k=$k corners", want, corner(out))
            assertEquals("k=$k swaps width/height", k in 5..8, out.width == 2 && out.height == 3)
        }
    }

    @Test
    fun valuesOutsideOneToEightLeaveTheImageAlone() {
        assertTrue(Orientation.apply(base, 0) === base)
        assertTrue(Orientation.apply(base, 9) === base)
    }

    @Test
    fun applyingAnOrientationAndItsInverseRestoresTheImage() {
        val inverse = mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 5, 6 to 8, 7 to 7, 8 to 6)
        for ((k, inv) in inverse) {
            assertArrayEquals(
                "k=$k",
                base.rgb,
                Orientation.apply(Orientation.apply(base, k), inv).rgb,
            )
        }
    }
}
