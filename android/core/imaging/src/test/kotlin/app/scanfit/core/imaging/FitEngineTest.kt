package app.scanfit.core.imaging

import app.scanfit.core.inspect.Inspector
import app.scanfit.core.model.DimensionMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class FitEngineTest {
    private val engine = FitEngine(ImageIoEncoder)
    private val preferred = Presets.inline(type = "photo", min = "20", max = "50", target = "38", dims = """{"mode":"preferred","width":200,"height":230}""")

    private fun ok(o: FitOutcome) = (o as? FitOutcome.Success)?.result ?: error("expected success, got $o")

    private fun decodes(b: ByteArray) = ImageIO.read(ByteArrayInputStream(b)) != null

    private fun inWindow(r: FitResult, min: Double, max: Double) = assertTrue("${r.kb} KB not in [$min, $max]", r.kb in min..max)

    /** A mostly-white page with thin strokes: tiny as a JPEG, like a clean signature. */
    private fun sparse(w: Int, h: Int) = Raster.of(w, h) { x, y -> if ((x / 3 + y / 5) % 17 == 0) 0x000000 else 0xFFFFFF }

    @Test
    fun aDetailedPhotoLandsInsideTheWindowNearTheTarget() {
        val r = ok(engine.fit(noisyRaster(200, 230, noise = 120), preferred))
        inWindow(r, 21.0, 49.0)
        assertEquals(200 to 230, r.width to r.height)
        assertEquals(FitStrategy.QUALITY_SEARCH, r.strategy)
        assertTrue("a q95 hit needs one encode, a search at most 8: ${r.encodes}", r.encodes in 1..8)
        assertTrue(decodes(r.bytes))
    }

    @Test
    fun theResultIsBaselineRgbWithJfifDpiAndNoExif() {
        val f = Inspector.inspect(ok(engine.fit(noisyRaster(200, 230), preferred)).bytes)
        assertEquals("SOF0", f.sof)
        assertEquals(3, f.components)
        assertEquals(200, f.jfif?.xDensity)
        assertEquals(1, f.jfif?.units)
        assertTrue(!f.hasExif && !f.hasXmp && !f.hasIcc)
    }

    @Test
    fun theSlotDpiIsWrittenWhenPresent() {
        val s = Presets.inline(type = "left_thumb", min = "20", max = "50", target = "38", dims = """{"mode":"preferred","width":240,"height":240}""", extra = ""","dpi":300""")
        assertEquals(300, Inspector.inspect(ok(engine.fit(noisyRaster(240, 240, 100), s)).bytes).jfif?.xDensity)
    }

    @Test
    fun anImpossibleWindowIsTooDetailedForFixedSizes() {
        val tight = Presets.inline(type = "photo", min = "5", max = "7", target = "6", dims = """{"mode":"preferred","width":200,"height":230}""")
        assertEquals(FitOutcome.Failure(FitError.TOO_DETAILED), engine.fit(noisyRaster(200, 230, noise = 255), tight))
        val exact = Presets.inline(type = "photo", min = "5", max = "7", target = "6", dims = """{"mode":"exact","width":200,"height":230}""")
        assertEquals(FitOutcome.Failure(FitError.TOO_DETAILED), engine.fit(noisyRaster(200, 230, noise = 255), exact))
    }

    @Test
    fun noneModeShrinksUntilItFitsAndReportsDownscale() {
        val none = Presets.inline(type = "photo", min = "10", max = "40", target = "25", dims = """{"mode":"none"}""")
        val r = ok(engine.fit(noisyRaster(1200, 1600, noise = 100), none))
        assertEquals(FitStrategy.DOWNSCALE, r.strategy)
        inWindow(r, 11.0, 39.0)
        assertTrue("shrunk below the 1200x1600 start", r.height < 1600)
        assertEquals("aspect kept", 0.75, r.width.toDouble() / r.height, 0.01)
        assertTrue(maxOf(r.width, r.height) >= 600)
    }

    @Test
    fun noneModeGivesUpAtTheFloor() {
        val none = Presets.inline(type = "photo", min = "5", max = "8", target = "6", dims = """{"mode":"none"}""")
        assertEquals(FitOutcome.Failure(FitError.TOO_DETAILED), engine.fit(noisyRaster(1200, 1600, noise = 255), none))
        val inkSmall = Presets.inline(type = "signature", min = "5", max = "8", target = "6", dims = """{"mode":"none"}""")
        assertEquals("ink floor is 400 px, still too detailed here", FitOutcome.Failure(FitError.TOO_DETAILED), engine.fit(noisyRaster(1000, 800, noise = 255), inkSmall))
    }

    @Test
    fun rangeModeStaysInsideTheBoxWhenShrinking() {
        val gate = Presets.slot("gate", "photo")
        val r = ok(engine.fit(noisyRaster(400, 516, noise = 200), gate))
        assertTrue(r.width in 200..530 && r.height in 260..690)
        inWindow(r, 5.0, 600.0)
        val tooDetailed = Presets.inline(type = "photo", min = "5", max = "6", target = "5.5", dims = """{"mode":"range","min_w":200,"min_h":260,"max_w":530,"max_h":690,"aspect_w_over_h":{"min":0.66,"max":0.89}}""")
        val detailed = noisyRaster(400, 516, noise = 255)
        assertEquals(FitOutcome.Failure(FitError.TOO_DETAILED), engine.fit(detailed, tooDetailed))
    }

    @Test
    fun aSparsePictureUpscalesThenPadsToTheTarget() {
        val sig = Presets.inline(min = "10", max = "20", target = "16", dims = """{"mode":"preferred","width":140,"height":60}""")
        val almostBlank = Raster.of(420, 180) { x, y -> if (y == 90 && x in 100..300) 0x000000 else 0xFFFFFF }
        val r = ok(engine.fit(almostBlank, sig))
        assertEquals(FitStrategy.UPSCALE_THEN_PAD, r.strategy)
        inWindow(r, 11.0, 19.0)
        assertTrue("never above 2x", r.width <= 280 && r.height <= 120)
        assertTrue("grew past the preferred size", r.width > 140)
        assertEquals("aspect kept", 140.0 / 60.0, r.width.toDouble() / r.height, 0.03)
        assertEquals("padding lands on the target", 16.0, r.kb, 0.01)
        assertTrue(decodes(r.bytes))
    }

    @Test
    fun exactModeCanOnlyPadAndNeverChangesPixels() {
        val exact = Presets.inline(min = "12", max = "20", target = "16", dims = """{"mode":"exact","width":100,"height":40}""")
        val r = ok(engine.fit(sparse(250, 100), exact))
        assertEquals(FitStrategy.PAD, r.strategy)
        assertEquals(100 to 40, r.width to r.height)
        assertEquals(16.0, r.kb, 0.01)
    }

    @Test
    fun padOnlyKeepsThePreferredSize() {
        val sig = Presets.inline(min = "10", max = "20", target = "16", dims = """{"mode":"preferred","width":140,"height":60}""")
        val r = ok(engine.fit(sparse(420, 180), sig, FitOptions(minFill = MinFillStrategy.PAD_ONLY)))
        assertEquals(FitStrategy.PAD, r.strategy)
        assertEquals(140 to 60, r.width to r.height)
    }

    @Test
    fun aModerateSparsePictureUpscalesWithoutPadding() {
        // Sweep stroke density until one start size is too small but the next ladder step lands inside the window.
        val sig = Presets.inline(min = "6", max = "9", target = "7.5", dims = """{"mode":"preferred","width":140,"height":60}""")
        val strategies = (3..40 step 3).map { gap ->
            val picture = Raster.of(420, 180) { x, y -> if ((x / 2 + y / 3) % gap == 0) 0x000000 else 0xFFFFFF }
            ok(engine.fit(picture, sig)).strategy
        }.toSet()
        assertTrue("saw $strategies", FitStrategy.UPSCALE in strategies || FitStrategy.QUALITY_SEARCH in strategies)
        assertTrue(strategies.all { it != FitStrategy.DOWNSCALE })
    }

    @Test
    fun aWindowNarrowerThanTwoKbUsesTheWholeWindowAsItsGoal() {
        val narrow = Presets.inline(min = "10", max = "11", target = "10.5", dims = """{"mode":"preferred","width":140,"height":60}""")
        val r = ok(engine.fit(sparse(420, 180), narrow))
        inWindow(r, 10.0, 11.0)
    }

    @Test
    fun noTargetMeansTheMidpointOfTheWindow() {
        val s = Presets.spec("""{"type":"photo","required":true,"formats":["jpg"],"size_kb":{"min":20,"max":40,"target":null},"dimensions":{"mode":"preferred","width":200,"height":230}}""")
        inWindow(ok(engine.fit(noisyRaster(200, 230, noise = 150), s)), 21.0, 39.0)
    }

    @Test
    fun anOpenMinimumUsesZeroAsTheLowerBound() {
        val s = Presets.spec("""{"type":"photo","required":true,"formats":["jpg"],"size_kb":{"min":null,"max":50,"target":35},"dimensions":{"mode":"none"}}""")
        val r = ok(engine.fit(noisyRaster(600, 800, noise = 40), s))
        assertTrue(r.kb <= 50.0)
    }

    @Test
    fun unknownLimitsAndNonJpegSlotsAreTypedErrors() {
        val noMax = Presets.spec("""{"type":"signature","required":true,"formats":["jpg"],"size_kb":{"min":null,"max":null,"target":null},"dimensions":{"mode":"none"}}""")
        assertEquals(FitOutcome.Failure(FitError.UNKNOWN_LIMIT), engine.fit(noisyRaster(10, 10), noMax))
        val pdf = Presets.spec("""{"type":"class10_certificate","required":true,"formats":["pdf"],"size_kb":{"min":null,"max":400,"target":null},"dimensions":{"mode":"none"}}""")
        assertEquals(FitOutcome.Failure(FitError.UNSUPPORTED_FORMAT), engine.fit(noisyRaster(10, 10), pdf))
    }

    @Test
    fun anEncoderThatProducesGarbageIsSurfacedNotExported() {
        val bad = FitEngine { _, _ -> byteArrayOf(1, 2, 3) }
        assertEquals(FitOutcome.Failure(FitError.ENCODING_FAILED), bad.fit(noisyRaster(10, 10), preferred))
    }

    @Test
    fun fitIsDeterministic() {
        val a = ok(engine.fit(noisyRaster(200, 230, 90), preferred))
        val b = ok(engine.fit(noisyRaster(200, 230, 90), preferred))
        assertArrayEquals(a.bytes, b.bytes)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotNull(a.strategy.wire)
    }

    @Test
    fun spotlessSanityTheStartSizeOfPreferredIsTheSpecSize() {
        assertEquals(DimensionMode.PREFERRED, preferred.dimensions.mode)
        assertEquals(Size(200, 230), Geometry.startSize(preferred, 999, 999))
    }
}
