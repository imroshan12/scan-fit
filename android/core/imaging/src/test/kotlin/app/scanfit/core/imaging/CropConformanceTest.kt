package app.scanfit.core.imaging

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `crop_cases` (ALGORITHMS 9.6): auto-framing and the crop screen's move/zoom, computed by spec/tools/photo_crop.py. */
class CropConformanceTest {
    private fun JsonObject.n(k: String) = getValue(k).jsonPrimitive.int

    private fun JsonObject.d(k: String) = getValue(k).jsonPrimitive.double

    private fun JsonObject.obj(k: String) = getValue(k) as JsonObject

    private fun JsonObject.rect() = CropRect(n("x"), n("y"), n("w"), n("h"))

    @Test
    fun everySharedCropCaseHolds() {
        val cases = Cases.section("crop_cases")
        assertTrue(cases.size >= 18)
        assertTrue("resize is covered", cases.count { it["op"]?.jsonPrimitive?.content == "resize" } >= 6)
        for (c in cases) {
            val id = c.getValue("id").jsonPrimitive.content
            val img = c.obj("image")
            val (aw, ah) = (c.getValue("aspect") as JsonArray).map { it.jsonPrimitive.int }
            val aspect = aw.toDouble() / ah
            val expect = c.obj("expect")
            val actual =
                when (val op = c.getValue("op").jsonPrimitive.content) {
                    "frame" -> {
                        val f = c.obj("face")
                        val coverage = c["coverage"]?.jsonPrimitive?.double ?: AutoFraming.DEFAULT_COVERAGE
                        val framing =
                            AutoFraming.frame(Face(f.d("x"), f.d("y"), f.d("w"), f.d("h")), img.n("w"), img.n("h"), aspect, coverage)
                        assertEquals("$id: coverage_adjusted", expect.getValue("coverage_adjusted").jsonPrimitive.boolean, framing.coverageAdjusted)
                        framing.crop
                    }

                    "move" -> CropAdjust.move(c.obj("rect").rect(), c.d("dx"), c.d("dy"), img.n("w"), img.n("h"))

                    "zoom" -> CropAdjust.zoom(c.obj("rect").rect(), c.d("factor"), aspect, img.n("w"), img.n("h"))

                    "resize" -> {
                        val corner = CropCorner.of(c.getValue("corner").jsonPrimitive.content)
                        CropAdjust.resize(c.obj("rect").rect(), corner, c.d("dx"), c.d("dy"), img.n("w"), img.n("h"))
                    }

                    else -> error("$id: unknown op $op")
                }
            assertEquals(id, expect.obj("rect").rect(), actual)
        }
    }

    @Test
    fun zoomKeepsTheAspectAndStaysInside() {
        val aspect = 200.0 / 230.0
        var rect = CropRect(100, 100, 400, 460)
        for (f in listOf(1.3, 0.7, 2.5, 0.2, 1.1, 9.0, 0.05)) {
            rect = CropAdjust.zoom(rect, f, aspect, 1000, 1400)
            assertTrue("$rect inside", rect.x >= 0 && rect.y >= 0 && rect.x + rect.w <= 1000 && rect.y + rect.h <= 1400)
            assertEquals("aspect kept for $rect", aspect, rect.w.toDouble() / rect.h, 0.02)
            assertTrue("short side >= 64: $rect", minOf(rect.w, rect.h) >= 64)
        }
    }

    @Test
    fun aTinyImageCapsTheMinimumAtTheImage() {
        val r = CropAdjust.zoom(CropRect(0, 0, 40, 46), 10.0, 200.0 / 230.0, 40, 50)
        assertTrue("fits a 40x50 image: $r", r.w <= 40 && r.h <= 50)
    }

    @Test
    fun aBadZoomFactorIsRejected() {
        try {
            CropAdjust.zoom(CropRect(0, 0, 10, 10), 0.0, 1.0, 100, 100)
            error("must reject")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun rotateTurnsClockwise() {
        // 2x1: red, blue -> 1x2: red on top (the left column becomes the top row), blue below
        val src = Raster.of(2, 1) { x, _ -> if (x == 0) 0xFF0000 else 0x0000FF }
        val out = CropAdjust.rotateClockwise(src)
        assertEquals(1, out.width)
        assertEquals(2, out.height)
        assertEquals(255, out.r(0, 0))
        assertEquals(255, out.b(0, 1))
    }
}
