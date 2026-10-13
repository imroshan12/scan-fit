package app.scanfit.core.imaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.scanfit.core.inspect.Inspector
import app.scanfit.core.match.DocKind
import app.scanfit.core.match.FileFacts
import app.scanfit.core.match.MatchEngine
import app.scanfit.core.match.Verdict
import app.scanfit.core.model.DocType
import app.scanfit.core.model.PresetBundle
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
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Runs every `thumb_preservation_cases` entry of spec/fixtures/cases.json (ALGORITHMS 9.5 thumb): a parametric print
 * drawn on paper must keep its heavily inked areas dark, stay whole inside the square and leave the paper white.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ThumbPreservationConformanceTest {
    private data class Box(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
    )

    private fun JsonObject.numbers(key: String): List<Double>? = this[key]?.jsonArray?.map { it.jsonPrimitive.double }

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.int

    private fun paper(
        case: JsonObject,
        x: Int,
    ): Int {
        val paper = checkNotNull(case.numbers("paper"))
        val w = checkNotNull(case.int("width"))
        return roundHalfUp(paper[0] + (paper[1] - paper[0]) * x / (w - 1))
    }

    /** The print described by a case (the generator is part of the contract, see the section's cases). */
    private fun source(case: JsonObject): Raster {
        val shape = checkNotNull(case.numbers("print"))
        val ridge = checkNotNull(case.numbers("ridge"))
        val rim = case.numbers("rim")
        val core = case.numbers("core")
        return Raster.of(checkNotNull(case.int("width")), checkNotNull(case.int("height"))) { x, y ->
            var v = paper(case, x)
            val ux = (x - shape[0]) / shape[2]
            val uy = (y - shape[1]) / shape[3]
            val r = sqrt(ux * ux + uy * uy)
            if (r <= 1) {
                val sy = (y - shape[1]) * shape[2] / shape[3]
                val d = sqrt((x - shape[0]) * (x - shape[0]) + sy * sy)
                if (floor(2 * d / ridge[0]).toInt() % 2 == 0) {
                    v = (if (rim != null && r > rim[0]) rim[1] else ridge[1]).toInt()
                }
            }
            if (core != null) {
                val cx = (x - core[0]) / core[2]
                val cy = (y - core[1]) / core[3]
                if (cx * cx + cy * cy <= 1) v = core[4].toInt()
            }
            (v shl 16) or (v shl 8) or v
        }
    }

    private fun count(
        r: Raster,
        test: (Int) -> Boolean,
    ): Int {
        var n = 0
        for (y in 0 until r.height) for (x in 0 until r.width) if (test(r.r(x, y))) n++
        return n
    }

    private fun box(
        r: Raster,
        ink: (Int, Int) -> Boolean,
    ): Box? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until r.height) {
            for (x in 0 until r.width) {
                if (!ink(x, r.r(x, y))) continue
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
            }
        }
        return if (maxX < 0) null else Box(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    @Test
    fun everyThumbCaseKeepsThePrintDarkWholeAndOnWhitePaper() {
        val cases = Cases.section("thumb_preservation_cases")
        assertTrue(cases.size >= 3)
        for (case in cases) {
            val id = case.getValue("id").jsonPrimitive.content
            val expect = case.getValue("expect").jsonObject
            val src = source(case)
            val result = InkCleanup.clean(src, InkVariant.THUMB)
            val out = result.raster
            assertEquals("$id: square", out.width, out.height)
            val delta = checkNotNull(expect.int("ink_delta"))
            val visible = checkNotNull(expect.int("visible_below"))
            val tolerance = checkNotNull(expect.int("box_tolerance"))
            val srcBox = checkNotNull(box(src) { x, v -> v < paper(case, x) - delta })
            val outBox = checkNotNull(box(out) { _, v -> v < visible })
            assertTrue(
                "$id: the whole print is kept: source $srcBox, output $outBox",
                abs(outBox.w - srcBox.w) <= tolerance && abs(outBox.h - srcBox.h) <= tolerance,
            )
            val core = case.numbers("core")
            val maxMean = expect.int("core_max_mean")
            if (core != null && maxMean != null) {
                val dx = outBox.x - srcBox.x
                val dy = outBox.y - srcBox.y
                var sum = 0
                var n = 0
                for (y in 0 until out.height) {
                    for (x in 0 until out.width) {
                        val ux = (x - dx - core[0]) / (CORE_SHRINK * core[2])
                        val uy = (y - dy - core[1]) / (CORE_SHRINK * core[3])
                        if (ux * ux + uy * uy <= 1) {
                            sum += out.r(x, y)
                            n++
                        }
                    }
                }
                assertTrue("$id: the heavy-ink core stays dark (mean ${if (n > 0) sum / n else -1})", n > 0 && sum / n <= maxMean)
            }
            val inkBelow = expect.int("ink_below")
            val inkMin = expect.int("ink_min")
            if (inkBelow != null && inkMin != null) {
                val ink = count(out) { it < inkBelow }
                assertTrue("$id: $ink ink pixels darker than $inkBelow, want ≥ $inkMin", ink >= inkMin)
            }
            val border = checkNotNull(expect.int("border"))
            val clear = checkNotNull(expect.int("border_clear_above"))
            var touching = 0
            for (y in 0 until out.height) {
                for (x in 0 until out.width) {
                    val edge = x < border || y < border || x >= out.width - border || y >= out.height - border
                    if (edge && out.r(x, y) < clear) touching++
                }
            }
            assertEquals("$id: ink on the output border (the print was clipped)", 0, touching)
            val white = count(out) { it >= WHITE }.toDouble() / (out.width * out.height)
            assertTrue("$id: white fraction $white", white >= expect.getValue("white_min_fraction").jsonPrimitive.double)
            assertEquals("$id: quality", InkQuality.OK, result.quality)
        }
    }

    @Test
    fun aHeavyCoreThumbSurvivesTheIbpsFitAndItsFinalBytesPassTheSlot() {
        val case = Cases.section("thumb_preservation_cases").first { it.getValue("id").jsonPrimitive.content == "heavy_core_under_shadow" }
        val examFile = File(Cases.spec, "presets/exams").walkTopDown().first { it.name == "ibps_po.json" }
        val slot = PresetBundle.decodeExam(examFile.readText()).documents.first { it.type == DocType.LEFT_THUMB }
        val outcome = FitPipeline(AndroidJpegEncoder).run(source(case), slot, Pipeline.THUMB_CLEANUP)
        assertTrue("actual native fit must succeed: $outcome", outcome is PipelineOutcome.Success)
        val result = (outcome as PipelineOutcome.Success).result
        val verdict = MatchEngine.evaluate(slot, FileFacts.of(Inspector.inspect(result.fit.bytes), DocKind.THUMB)).verdict
        assertTrue("final slot verdict: $verdict", verdict == Verdict.EXACT || verdict == Verdict.ACCEPTED)
        val decoded = checkNotNull(AndroidImageDecoder.decode(result.fit.bytes, MAX_DECODE))
        val scale = decoded.width.toDouble() * decoded.height / (result.prepared.width * result.prepared.height)
        val dark = count(decoded) { it < DARK }
        val preparedDark = count(result.prepared) { it < DARK }
        assertTrue("dark core survives encoding: $dark", dark >= SURVIVING * preparedDark * scale)
    }

    private companion object {
        const val CORE_SHRINK = 0.7
        const val WHITE = 250
        const val DARK = 100
        const val SURVIVING = 0.6
        const val MAX_DECODE = 4000
    }
}
