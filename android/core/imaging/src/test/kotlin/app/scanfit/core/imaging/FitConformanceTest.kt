package app.scanfit.core.imaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.scanfit.core.inspect.Inspector
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.PresetBundle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** Runs every `fit_cases` entry of spec/fixtures/cases.json end to end with the production Android codec (ALGORITHMS 9.9). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FitConformanceTest {
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content

    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.int

    private fun JsonObject.dbl(k: String) = this[k]?.jsonPrimitive?.double

    private fun JsonObject.range(k: String) = (this[k] as? JsonArray)?.jsonArray?.map { it.jsonPrimitive.double }

    private fun examFile(id: String) = File(Cases.spec, "presets/exams").walkTopDown().first { it.name == "$id.json" }

    private fun luma(r: Raster) = r.luma().map { it.toInt() and 0xFF }

    @Test
    fun everyFitCaseMeetsItsExpectations() {
        val cases = Cases.section("fit_cases")
        assertTrue(cases.size >= 11)
        val pipeline = FitPipeline(AndroidJpegEncoder)
        for (c in cases) {
            val id = c.str("id")!!
            val bytes = Cases.image(c.str("input")!!)
            val original = Inspector.inspect(bytes)
            val exam = c.str("preset")?.let { PresetBundle.decodeExam(examFile(it).readText()) }
            val spec: DocSpec =
                c["spec"]?.let { PresetBundle.json.decodeFromString(DocSpec.serializer(), it.toString()) }
                    ?: exam!!.documents.first { it.type.name.lowercase() == c.str("doc") }
            val d = spec.dimensions
            val largest = listOfNotNull(d.width, d.height, d.maxW, d.maxH).maxOrNull() ?: 0
            val decoded =
                checkNotNull(
                    AndroidImageDecoder.decode(bytes, AndroidImageDecoder.longSideCap(largest)),
                ) { "$id: decode" }
            val crop =
                (c["crop"] as? JsonObject)?.let {
                    CropRect(
                        it.int("x")!!,
                        it.int("y")!!,
                        it.int("w")!!,
                        it.int("h")!!,
                    ).scaledTo(original.width!!, original.height!!, decoded)
                }
            val kind =
                when (c.str("pipeline")) {
                    "signature_cleanup" -> Pipeline.SIGNATURE_CLEANUP
                    "thumb_cleanup" -> Pipeline.THUMB_CLEANUP
                    "document_cleanup" -> Pipeline.DOCUMENT_CLEANUP
                    else -> Pipeline.PLAIN
                }
            val minFill =
                if ((c["options"] as? JsonObject)?.str("min_fill_strategy") ==
                    "pad_only"
                ) {
                    MinFillStrategy.PAD_ONLY
                } else {
                    MinFillStrategy.UPSCALE_THEN_PAD
                }
            val outcome = pipeline.run(decoded, spec, kind, crop, FitOptions(minFill = minFill))
            val e = c.getValue("expect").jsonObject
            val result = (outcome as? PipelineOutcome.Success)?.result?.fit ?: error("$id: fit failed with $outcome")

            val f = Inspector.inspect(result.bytes)
            e.str("format")?.let {
                assertTrue(
                    "$id: $it",
                    it == "jpeg_baseline" && f.format.name == "JPEG" && f.sof == "SOF0",
                )
            }
            e.str("color")?.let { assertEquals("$id: color", it, f.color.name.lowercase()) }
            e.dbl("kb_min")?.let { assertTrue("$id: ${f.kb} KB >= $it", f.kb >= it) }
            e.dbl("kb_max")?.let { assertTrue("$id: ${f.kb} KB <= $it", f.kb <= it) }
            val w = f.width!!
            val h = f.height!!
            val tw = e.int("width")
            val th = e.int("height")
            if (tw != null && th != null) {
                if (e["allow_upscaled_preferred"]?.jsonPrimitive?.content == "true") {
                    assertTrue("$id: ${w}x$h is 1x..2x of ${tw}x$th", w in tw..tw * 2 && h in th..th * 2)
                    assertTrue("$id: aspect kept (${w}x$h)", abs(w * th - h * tw) <= maxOf(tw, th))
                } else {
                    assertEquals("$id: size", tw to th, w to h)
                }
            }
            e.range("w_range")?.let { assertTrue("$id: width $w in $it", w >= it[0] && w <= it[1]) }
            e.range("h_range")?.let { assertTrue("$id: height $h in $it", h >= it[0] && h <= it[1]) }
            e.range("aspect_range")?.let {
                assertTrue(
                    "$id: aspect ${w.toDouble() / h} in $it",
                    w.toDouble() / h >= it[0] && w.toDouble() / h <= it[1],
                )
            }
            e.dbl("aspect")?.let { assertEquals("$id: aspect", it, w.toDouble() / h, e.dbl("aspect_tol") ?: 0.02) }
            e.str("exif")?.let { assertTrue("$id: no EXIF/XMP/ICC", !f.hasExif && !f.hasXmp && !f.hasIcc) }
            e.int("dpi")?.let {
                assertEquals("$id: dpi", it, f.jfif?.xDensity)
                assertEquals("$id: dpi y", it, f.jfif?.yDensity)
                assertEquals("$id: units", 1, f.jfif?.units)
            }
            e.str("export_filename")?.let {
                assertEquals("$id: file name", it, ExportNaming.fileName(exam!!, spec, w, h, result.bytes.size))
            }
            val back =
                checkNotNull(AndroidImageDecoder.decode(result.bytes, 4000)) { "$id: the written bytes must decode" }
            e.dbl("background_mean_min")?.let {
                assertTrue(
                    "$id: median luma >= $it",
                    luma(back).sorted()[back.width * back.height / 2] >= it,
                )
            }
            e.dbl("ink_pixels_min_pct")?.let { pct ->
                val ink = luma(back).count { it < 128 } * 100.0 / (back.width * back.height)
                assertTrue("$id: ink $ink% >= $pct%", ink >= pct)
            }
        }
    }
}
