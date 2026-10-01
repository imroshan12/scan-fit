package app.scanfit.core.imaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.scanfit.core.inspect.Inspector
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The production Android codec under Robolectric's native graphics (real Skia): the shared decode cases + encoder behaviour. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AndroidCodecTest {
    private fun JsonObject.n(k: String) = getValue(k).jsonPrimitive.int

    @Test
    fun everySharedDecodeCaseHolds() {
        val cases = Cases.section("decode_cases")
        assertTrue(cases.size >= 10)
        for (c in cases) {
            val id = c.getValue("id").jsonPrimitive.content
            val bytes = Cases.image(c.getValue("input").jsonPrimitive.content)
            val cap = AndroidImageDecoder.longSideCap(c["target_max_dim"]?.jsonPrimitive?.int ?: 0)
            val raster = checkNotNull(AndroidImageDecoder.decode(bytes, cap)) { "$id: decodes" }
            val e = c.getValue("expect") as JsonObject
            assertEquals("$id: size", e.n("width") to e.n("height"), raster.width to raster.height)
            val tol = (e["tolerance"]?.jsonPrimitive?.int) ?: continue
            val samples =
                mapOf(
                    "TL" to (raster.width / 4 to raster.height / 4),
                    "TR" to (raster.width * 3 / 4 to raster.height / 4),
                    "BL" to (raster.width / 4 to raster.height * 3 / 4),
                    "BR" to (raster.width * 3 / 4 to raster.height * 3 / 4),
                )
            for ((name, p) in samples) {
                val want = (e.getValue(name) as JsonArray).jsonArray.map { it.jsonPrimitive.int }
                val got = listOf(raster.r(p.first, p.second), raster.g(p.first, p.second), raster.b(p.first, p.second))
                assertTrue(
                    "$id $name: got $got want $want",
                    got.zip(want).all { (a, b) ->
                        kotlin.math.abs(a - b) <= tol
                    },
                )
            }
        }
    }

    @Test
    fun garbageDoesNotDecode() {
        assertNull(AndroidImageDecoder.decode(byteArrayOf(1, 2, 3, 4), 100))
    }

    @Test
    fun theLongSideCapIsMaxOfTwiceTheTargetAnd1600() {
        assertEquals(1600, AndroidImageDecoder.longSideCap(230))
        assertEquals(2000, AndroidImageDecoder.longSideCap(1000))
    }

    @Test
    fun theEncoderProducesRealBaselineJpegsWhoseSizeGrowsWithQuality() {
        val r = noisyRaster(200, 230, noise = 120)
        val sizes = listOf(35, 65, 95).map { AndroidJpegEncoder.encode(r, it).size }
        assertTrue("size grows with quality: $sizes", sizes[0] < sizes[1] && sizes[1] < sizes[2])
        val f = Inspector.inspect(AndroidJpegEncoder.encode(r, 80))
        assertEquals(200 to 230, f.width to f.height)
        assertEquals("SOF0", f.sof)
        assertEquals(3, f.components)
        assertNotNull(f.jfif)
    }

    @Test
    fun decodeThenEncodeKeepsThePicture() {
        val src = noisyRaster(120, 80, noise = 30)
        val back = checkNotNull(AndroidImageDecoder.decode(AndroidJpegEncoder.encode(src, 100), 1600))
        assertEquals(120 to 80, back.width to back.height)
        val diff =
            (0 until 80 step 7).sumOf { y ->
                (0 until 120 step 5).sumOf { x ->
                    kotlin.math.abs(src.r(x, y) - back.r(x, y))
                }
            } /
                (12 * 24)
        assertTrue("mean error $diff", diff < 12)
    }

    @Test
    fun theFitEngineWorksWithTheRealEncoder() {
        val spec =
            Presets.inline(
                type = "photo",
                min = "20",
                max = "50",
                target = "38",
                dims = """{"mode":"preferred","width":200,"height":230}""",
            )
        val r =
            (
                FitEngine(
                    AndroidJpegEncoder,
                ).fit(noisyRaster(200, 230, noise = 120), spec) as FitOutcome.Success
                ).result
        assertTrue("${r.kb} KB", r.kb in 21.0..49.0)
        val f = Inspector.inspect(r.bytes)
        assertEquals("SOF0", f.sof)
        assertTrue(!f.hasExif && !f.hasIcc)
    }
}
