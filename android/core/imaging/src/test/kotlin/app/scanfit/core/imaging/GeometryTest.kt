package app.scanfit.core.imaging

import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.PresetBundle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

object Presets {
    fun slot(preset: String, doc: String): DocSpec {
        val exam = File(Cases.spec, "presets/exams").walkTopDown().first { it.name == "$preset.json" }.readText()
        return PresetBundle.decodeExam(exam).documents.first { it.type.name.lowercase() == doc }
    }

    fun spec(json: String): DocSpec = PresetBundle.json.decodeFromString(DocSpec.serializer(), json)

    fun inline(type: String = "signature", min: String = "10", max: String = "20", target: String = "16", dims: String, extra: String = ""): DocSpec = spec("""{"type":"$type","required":true,"formats":["jpg"],"size_kb":{"min":$min,"max":$max,"target":$target},"dimensions":$dims$extra}""")
}

class GeometryTest {
    private fun JsonObject.n(k: String) = getValue(k).jsonPrimitive.int

    @Test
    fun theSharedGeometryCasesAllHold() {
        val cases = Cases.section("geometry_cases")
        assertTrue(cases.size >= 8)
        for (c in cases) {
            val id = c.getValue("id").jsonPrimitive.content
            val spec = Presets.slot(c.getValue("preset").jsonPrimitive.content, c.getValue("doc").jsonPrimitive.content)
            val src = c.getValue("source") as JsonObject
            val sw = src.n("w")
            val sh = src.n("h")
            val e = c.getValue("expect") as JsonObject
            (e["crop"] as? JsonObject)?.let { crop ->
                val a = checkNotNull(Geometry.targetAspect(spec))
                assertEquals("$id: crop", CropRect(crop.n("x"), crop.n("y"), crop.n("w"), crop.n("h")), Geometry.defaultCrop(sw, sh, a))
            }
            (e["pad"] as? JsonObject)?.let { pad ->
                val a = checkNotNull(Geometry.targetAspect(spec))
                assertEquals("$id: pad", PadPlan(pad.n("w"), pad.n("h"), pad.n("x"), pad.n("y")), Geometry.padToAspect(sw, sh, a))
            }
            (e["start"] as? JsonObject)?.let { start ->
                assertEquals("$id: start", Size(start.n("w"), start.n("h")), Geometry.startSize(spec, sw, sh))
            }
        }
    }

    @Test
    fun rangeStartClampsTheHeightIntoTheBox() {
        val s = Presets.inline(dims = """{"mode":"range","min_w":100,"min_h":100,"max_w":300,"max_h":120,"aspect_w_over_h":{"min":0.5,"max":0.5}}""")
        // a = 0.5: W0 = 200, H0 = 400 > maxH, so H = 120 and W = round(60) = 60, which is then clamped up to minW = 100
        assertEquals(Size(100, 120), Geometry.startSize(s, 1000, 1000))
        val noAspect = Presets.inline(dims = """{"mode":"range","min_w":100,"min_h":50,"max_w":300,"max_h":150}""")
        assertEquals("aspect from the box midpoints", 200.0 / 100.0, checkNotNull(Geometry.targetAspect(noAspect)), 1e-9)
        assertEquals(Size(200, 100), Geometry.startSize(noAspect, 1000, 1000))
    }

    @Test
    fun noneModeKeepsTheAspectAndNeverUpscales() {
        val sig = Presets.inline(dims = """{"mode":"none"}""")
        assertEquals(Size(1000, 500), Geometry.startSize(sig, 2000, 1000))
        assertEquals(Size(500, 1000), Geometry.startSize(sig, 1000, 2000))
        assertEquals(Size(300, 100), Geometry.startSize(sig, 300, 100))
        assertNull(Geometry.targetAspect(sig))
        val doc = Presets.inline(type = "class10_certificate", dims = """{"mode":"none"}""")
        assertEquals(Size(1600, 800), Geometry.startSize(doc, 4000, 2000))
    }

    @Test
    fun scaleIsMeasuredFromTheStartSizeAndNeverBelowOnePixel() {
        assertEquals(Size(119, 51), Geometry.scaled(Size(140, 60), 0.85))
        assertEquals(Size(175, 75), Geometry.scaled(Size(140, 60), 1.25))
        assertEquals(Size(1, 1), Geometry.scaled(Size(2, 2), 0.01))
    }

    @Test
    fun upscaleCapsFollowTheMode() {
        val start = Size(140, 60)
        val preferred = Presets.inline(dims = """{"mode":"preferred","width":140,"height":60}""")
        assertTrue(Geometry.withinUpscaleCap(preferred, start, Size(280, 120)))
        assertFalse(Geometry.withinUpscaleCap(preferred, start, Size(281, 120)))
        val exact = Presets.inline(dims = """{"mode":"exact","width":140,"height":60}""")
        assertFalse(Geometry.withinUpscaleCap(exact, start, Size(141, 60)))
        val range = Presets.inline(dims = """{"mode":"range","min_w":10,"min_h":10,"max_w":500,"max_h":200,"aspect_w_over_h":{"min":2,"max":3}}""")
        assertTrue(Geometry.withinUpscaleCap(range, start, Size(500, 200)))
        assertFalse(Geometry.withinUpscaleCap(range, start, Size(501, 200)))
        val none = Presets.inline(dims = """{"mode":"none"}""")
        assertTrue(Geometry.withinUpscaleCap(none, start, Size(2000, 5)))
        assertFalse(Geometry.withinUpscaleCap(none, start, Size(2001, 5)))
    }

    @Test
    fun onlyPhotosCropEverythingElsePads() {
        assertTrue(FitProfile.cropsToAspect(app.scanfit.core.model.DocType.PHOTO))
        assertTrue(FitProfile.cropsToAspect(app.scanfit.core.model.DocType.POSTCARD_PHOTO))
        for (t in app.scanfit.core.model.DocType.entries.filter { it.name != "PHOTO" && it.name != "POSTCARD_PHOTO" }) assertFalse(t.name, FitProfile.cropsToAspect(t))
    }
}
