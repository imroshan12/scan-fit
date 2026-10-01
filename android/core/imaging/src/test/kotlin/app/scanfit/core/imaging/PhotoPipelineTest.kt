package app.scanfit.core.imaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class AutoFramingTest {
    @Test
    fun aCentredFaceGetsTheDocumentedCrop() {
        // face 200x240 at (400,500); coverage 0.675 -> crop height round(1.25*240/0.675) = 444, width round(444*200/230) = 386
        val f = AutoFraming.frame(Face(400.0, 500.0, 200.0, 240.0), 1000, 1400, 200.0 / 230.0)
        assertEquals(CropRect(307, 426, 386, 444), f.crop)
        assertTrue(!f.coverageAdjusted)
    }

    @Test
    fun theFaceTakesTheTargetShareOfTheCropAndTheCrownSitsTenPercentFromTheTop() {
        val face = Face(300.0, 400.0, 300.0, 360.0)
        val crop = AutoFraming.frame(face, 2000, 2000, 0.8).crop
        assertEquals(0.675, 1.25 * face.h / crop.h, 0.01)
        assertEquals(0.10, (face.y - 0.125 * face.h - crop.y) / crop.h, 0.01)
        assertEquals("horizontally centred on the face", face.x + face.w / 2, crop.x + crop.w / 2.0, 1.0)
    }

    @Test
    fun aPresetCoverageOverridesTheDefault() {
        val face = Face(400.0, 500.0, 200.0, 240.0)
        val tight = AutoFraming.frame(face, 2000, 2000, 0.8, coverage = 0.75).crop
        val loose = AutoFraming.frame(face, 2000, 2000, 0.8, coverage = 0.60).crop
        assertTrue("higher coverage = tighter crop", tight.h < loose.h)
    }

    @Test
    fun aCropThatWouldLeaveTheImageIsShiftedBackInside() {
        val f = AutoFraming.frame(Face(10.0, 20.0, 200.0, 240.0), 1000, 1400, 0.8)
        assertEquals(0, f.crop.x)
        assertEquals(0, f.crop.y)
        assertTrue(!f.coverageAdjusted)
    }

    @Test
    fun aFaceTooBigForTheImageShrinksTheCropAndFlagsIt() {
        val f = AutoFraming.frame(Face(50.0, 100.0, 900.0, 1000.0), 1000, 1200, 0.8)
        assertTrue(f.coverageAdjusted)
        assertTrue(f.crop.w <= 1000 && f.crop.h <= 1200)
        assertEquals("aspect kept", 0.8, f.crop.w.toDouble() / f.crop.h, 0.01)
        val wide = AutoFraming.frame(Face(0.0, 0.0, 400.0, 100.0), 300, 1000, 2.0)
        assertTrue("width forced the shrink", wide.crop.w <= 300 && wide.coverageAdjusted)
    }

    @Test
    fun badInputIsRejected() {
        for (bad in listOf({ AutoFraming.frame(Face(0.0, 0.0, 1.0, 1.0), 0, 10, 1.0) }, { AutoFraming.frame(Face(0.0, 0.0, 1.0, 1.0), 10, 10, 0.0) })) {
            try {
                bad()
                error("must reject")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }
}

class BackgroundWhiteningTest {
    private fun gray(w: Int, h: Int, v: Int) = Raster.of(w, h) { _, _ -> (v shl 16) or (v shl 8) or v }

    @Test
    fun backgroundGoesWhiteAndThePersonIsUntouched() {
        val img = gray(40, 10, 100)
        val mask = ByteArray(40 * 10) { if (it % 40 < 20) 255.toByte() else 0 }
        val out = BackgroundWhitening.composite(img, mask)
        assertEquals("deep inside the person: unchanged", 100, out.r(5, 5))
        assertEquals("deep inside the background: white", 255, out.r(35, 5))
        val edge = out.r(20, 5)
        assertTrue("the boundary is feathered: $edge", edge in 101..254)
    }

    @Test
    fun anAllBackgroundMaskWhitensEverythingAndAnAllPersonMaskChangesNothing() {
        val img = noisyRaster(16, 16)
        assertTrue(BackgroundWhitening.composite(img, ByteArray(256)).rgb.all { it == (-1).toByte() })
        assertTrue(BackgroundWhitening.composite(img, ByteArray(256) { -1 }).rgb.contentEquals(img.rgb))
    }

    @Test
    fun aWrongSizedMaskIsRejected() {
        try {
            BackgroundWhitening.composite(gray(4, 4, 0), ByteArray(3))
            error("must reject")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}

class NameDateStripLayoutTest {
    private val fixed = TextMeasurer { text, size, _ -> text.length * size * 0.6f }

    @Test
    fun theStripIsEighteenPercentOfTheHeightAndSizesFollowTheSpec() {
        val l = NameDateStrip.layout("Asha Rao", "01/02/2003", 200, 230, fixed)
        assertEquals(41, l.stripHeight) // round(0.18 * 230)
        assertEquals(189, l.stripTop)
        assertEquals(Size(200, 189), l.photoArea)
        assertEquals("ASHA RAO", l.lines[0].text)
        assertEquals(0.38f * 41, l.lines[0].sizePx, 0.01f)
        assertEquals("01/02/2003", l.lines[1].text)
        assertEquals(0.32f * 41, l.lines[1].sizePx, 0.01f)
        assertTrue(l.lines.all { it.centerX == 100f })
        assertTrue("name above date", l.lines[0].baselineY < l.lines[1].baselineY)
        assertTrue("both baselines inside the strip", l.lines.all { it.baselineY > l.stripTop && it.baselineY < 230 })
    }

    @Test
    fun aLongNameShrinksFirstAndOnlyThenTruncatesWithAnEllipsis() {
        val base = 0.38f * 41
        val shrinks = NameDateStrip.layout("A".repeat(22), "01/02/2003", 200, 230, fixed).lines[0]
        assertTrue("shrunk but not below 60%: ${shrinks.sizePx}", shrinks.sizePx < base && shrinks.sizePx >= base * 0.6f - 0.01f)
        assertEquals("A".repeat(22), shrinks.text)
        val cut = NameDateStrip.layout("A".repeat(60), "01/02/2003", 200, 230, fixed).lines[0]
        assertEquals(base * 0.6f, cut.sizePx, 0.01f)
        assertTrue(cut.text.endsWith("…") && cut.text.length < 60)
        assertTrue("it fits after truncation", fixed.width(cut.text, cut.sizePx, true) <= 200 * 0.92f)
    }

    @Test
    fun anEmptyNameStillProducesAValidLayout() {
        assertEquals("…", NameDateStrip.layout("", "01/02/2003", 200, 230, TextMeasurer { _, _, _ -> 9999f }).lines[0].text)
        assertEquals("", NameDateStrip.layout("", "01/02/2003", 200, 230, fixed).lines[0].text)
    }

    @Test
    fun thePhotoIsLetterboxedNotStretched() {
        val l = NameDateStrip.layout("A", "01/02/2003", 200, 230, fixed)
        val photo = Raster.of(200, 230) { _, _ -> 0x646464 }
        val out = NameDateStrip.placePhoto(photo, l, 200, 230)
        assertEquals(200 to 230, out.width to out.height)
        assertEquals("white bar on the left (photo is 164 wide, centred)", 255, out.r(5, 100))
        assertEquals(100, out.r(100, 100))
        assertEquals("the strip area is white", 255, out.r(100, 200))
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AndroidStripRendererTest {
    @Test
    fun theStripGetsBlackTextAndTheSizeIsUnchanged() {
        val photo = Raster.of(200, 230) { _, _ -> 0x808080 }
        val out = AndroidStripRenderer.withStrip(photo, "Asha Rao", "01/02/2003")
        assertEquals(200 to 230, out.width to out.height)
        val dark = (189 until 230).sumOf { y -> (0 until 200).count { x -> out.r(x, y) < 100 } }
        assertTrue("text pixels in the strip: $dark", dark > 40)
        val outside = (0 until 180).sumOf { y -> (40 until 160).count { x -> out.r(x, y) < 100 } }
        assertEquals("no text above the strip", 0, outside)
        val xs = (189 until 230).flatMap { y -> (0 until 200).filter { x -> out.r(x, y) < 100 } }
        assertTrue("text is centred", xs.average() in 80.0..120.0)
    }

    @Test
    fun theMeasurerGrowsWithSizeAndBoldIsWider() {
        assertTrue(AndroidStripRenderer.width("MMMM", 20f, false) > AndroidStripRenderer.width("MMMM", 10f, false))
        assertTrue(AndroidStripRenderer.width("MMMM", 20f, true) >= AndroidStripRenderer.width("MMMM", 20f, false))
    }
}
