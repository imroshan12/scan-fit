package app.scanfit.core.imaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real-image ink cleanup (fixtures decoded by the production Android decoder) plus synthetic cases with known answers. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class InkCleanupTest {
    /** Paper with a diagonal shadow gradient and a thick dark pen line across the middle. */
    private fun shadowedLine(
        w: Int = 400,
        h: Int = 200,
        ink: Int = 0x1C225A,
    ): Raster = Raster.of(w, h) { x, y ->
        val shade = 235 - (x * 70 / w) // left bright, right in shadow
        if (y in 90..99 && x in 40..360) ink else (shade shl 16) or (shade shl 8) or shade
    }

    private fun blackAndWhiteOnly(r: Raster) = (0 until r.height).all { y ->
        (0 until r.width).all { x ->
            r.r(x, y) ==
                r.g(x, y) &&
                r.g(x, y) == r.b(x, y) &&
                (r.r(x, y) == 0 || r.r(x, y) == 255)
        }
    }

    private fun medianLuma(r: Raster): Int = r.luma().map { it.toInt() and 0xFF }.sorted()[r.width * r.height / 2]

    @Test
    fun aSignatureOnShadowedPaperBecomesBlackOnWhite() {
        val out = InkCleanup.clean(shadowedLine(), InkVariant.SIGNATURE)
        assertEquals(InkQuality.OK, out.quality)
        assertTrue("only pure black and white", blackAndWhiteOnly(out.raster))
        assertEquals("the shadow is gone", 255, medianLuma(out.raster))
        val ink =
            (0 until out.raster.height).sumOf { y ->
                (0 until out.raster.width).count { x ->
                    out.raster.r(x, y) ==
                        0
                }
            }
        assertTrue("the pen line survived: $ink px", ink > 2500)
    }

    @Test
    fun theTrimKeepsEightPercentOfTheLongSideAsMargin() {
        val out = InkCleanup.clean(shadowedLine(), InkVariant.SIGNATURE).raster
        // line 321 px wide x 10 px tall -> padding round(0.08 * 321) = 26 px on every side
        assertEquals(321 + 2 * 26, out.width)
        assertEquals(10 + 2 * 26, out.height)
    }

    @Test
    fun documentCleanupKeepsTheInkColourDarkened() {
        val out = InkCleanup.clean(shadowedLine(), InkVariant.DOCUMENT).raster
        val x = out.width / 2
        val y = (0 until out.height).first { out.r(x, it) != 255 || out.g(x, it) != 255 }
        assertEquals("0x1C * 0.6 rounded half up", 17, out.r(x, y))
        assertEquals("0x22 * 0.6", 20, out.g(x, y))
        assertEquals("0x5A * 0.6", 54, out.b(x, y))
    }

    @Test
    fun crispBlackAndTheInkFactorAreOptions() {
        val crisp = InkCleanup.clean(shadowedLine(), InkVariant.DOCUMENT, InkOptions(crispBlack = true)).raster
        assertTrue(blackAndWhiteOnly(crisp))
        val darker =
            InkCleanup
                .clean(
                    shadowedLine(),
                    InkVariant.SIGNATURE,
                    InkOptions(crispBlack = false, inkFactor = 0.3),
                ).raster
        val x = darker.width / 2
        val y = (0 until darker.height).first { darker.r(x, it) != 255 || darker.g(x, it) != 255 }
        assertEquals(8, darker.r(x, y)) // 0x1C = 28 * 0.3 = 8.4 -> 8
    }

    @Test
    fun uniformPaperHasNoInkAndIsTooFaint() {
        val paper = Raster.of(300, 200) { _, _ -> 0xE8E4DA }
        val out = InkCleanup.clean(paper, InkVariant.SIGNATURE)
        assertEquals(InkQuality.TOO_FAINT, out.quality)
        assertEquals(0.0, out.coverage, 0.0)
        assertEquals("nothing to trim to: the full white canvas", 300 to 200, out.raster.width to out.raster.height)
        assertEquals(255, medianLuma(out.raster))
    }

    @Test
    fun denseStrokesAreTooDark() {
        // two thirds of the stroke area is ink; the gate is measured over the trimmed image including its 8% margin
        val dense =
            Raster.of(300, 200) { x, y ->
                if (x in 50..250 && y in 40..160 &&
                    (x / 3) % 3 != 2
                ) {
                    0x000000
                } else {
                    0xF0F0F0
                }
            }
        assertEquals(InkQuality.TOO_DARK, InkCleanup.clean(dense, InkVariant.SIGNATURE).quality)
    }

    @Test
    fun specksAreDroppedButStrokesRemain() {
        val speckled =
            Raster.of(400, 200) { x, y ->
                val line = y in 90..99 && x in 40..360
                val speck = (x == 20 && y == 20) || (x == 380 && y == 30) // single dark pixels far from the stroke
                if (line || speck) 0x000000 else 0xF0F0F0
            }
        val out = InkCleanup.clean(speckled, InkVariant.SIGNATURE).raster
        assertEquals("specks do not stretch the trim box", 321 + 2 * 26, out.width)
    }

    @Test
    fun thumbCleanupIsGreyscaleSquareAndCentredOnTheInk() {
        val ridges =
            Raster.of(300, 300) { x, y ->
                val dx = x - 120
                val dy = y - 150
                val d = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toInt()
                if (d < 70 && d % 8 < 4) 0x28328C else 0xEAE6DC
            }
        val out = InkCleanup.clean(ridges, InkVariant.THUMB)
        assertEquals(out.raster.width, out.raster.height)
        assertTrue("square about 1.16 x the ink box", out.raster.width in 150..175)
        assertTrue(
            (0 until out.raster.height).all { y ->
                (0 until out.raster.width).all { x ->
                    out.raster.r(x, y) ==
                        out.raster.g(x, y) &&
                        out.raster.g(x, y) == out.raster.b(x, y)
                }
            },
        )
        assertTrue(
            "ridges stay as grey levels, not a 2-colour mask",
            (0 until out.raster.width).map { out.raster.r(it, out.raster.height / 2) }.toSet().size > 2,
        )
        assertEquals(InkQuality.OK, out.quality)
    }

    @Test
    fun aBlankPageIsAFaintThumb() {
        val out = InkCleanup.clean(Raster.of(100, 100) { _, _ -> 0xFFFFFF }, InkVariant.THUMB)
        assertEquals(InkQuality.TOO_FAINT, out.quality)
        assertEquals(100 to 100, out.raster.width to out.raster.height)
    }

    @Test
    fun theRealPaperShadowFixtureCleansUp() {
        val src = checkNotNull(AndroidImageDecoder.decode(Cases.image("signature_paper_shadow.jpg"), 1600))
        assertEquals(1600 to 933, src.width to src.height)
        val out = InkCleanup.clean(src, InkVariant.SIGNATURE)
        assertEquals(InkQuality.OK, out.quality)
        assertEquals(255, medianLuma(out.raster))
        assertTrue("trimmed well below the page", out.raster.width < src.width && out.raster.height < src.height)
        assertTrue(blackAndWhiteOnly(out.raster))
    }

    @Test
    fun theRealThumbFixtureKeepsItsRidgesAsGreyLevels() {
        val src = checkNotNull(AndroidImageDecoder.decode(Cases.image("thumb_ink.jpg"), 1600))
        val out = InkCleanup.clean(src, InkVariant.THUMB)
        assertEquals(out.raster.width, out.raster.height)
        assertTrue(out.raster.width in 200..1600)
        assertTrue(
            "many grey levels",
            out.raster
                .luma()
                .map { it.toInt() and 0xFF }
                .toSet()
                .size > 8,
        )
    }
}
