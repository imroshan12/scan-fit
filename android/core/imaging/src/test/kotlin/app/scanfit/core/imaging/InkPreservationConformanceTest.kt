package app.scanfit.core.imaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.scanfit.core.inspect.Inspector
import app.scanfit.core.inspect.JfifDensity
import app.scanfit.core.match.DocKind
import app.scanfit.core.match.FileFacts
import app.scanfit.core.match.MatchEngine
import app.scanfit.core.match.Verdict
import app.scanfit.core.model.DocType
import app.scanfit.core.model.PresetBundle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class InkPreservationConformanceTest {
    private fun points(case: JsonObject): Set<Pair<Int, Int>> = buildSet {
        for (line in case.getValue("lines").jsonArray) {
            val (start, end, row) = line.jsonArray.map { it.jsonPrimitive.int }
            for (column in start..end) add(column to row)
        }
        for (dot in case.getValue("dots").jsonArray) {
            val (column, row) = dot.jsonArray.map { it.jsonPrimitive.int }
            add(column to row)
        }
    }

    private fun source(case: JsonObject): Raster {
        val marks = points(case)
        return Raster.of(case.getValue("width").jsonPrimitive.int, case.getValue("height").jsonPrimitive.int) { column, row ->
            if (column to row in marks) 0x101010 else 0xF0F0F0
        }
    }

    private fun darkPoints(raster: Raster): Set<Pair<Int, Int>> = buildSet {
        for (row in 0 until raster.height) {
            for (column in 0 until raster.width) {
                if (raster.r(column, row) < 128) add(column to row)
            }
        }
    }

    @Test
    fun allSignatureCasesPreserveEveryThinStrokeAndDisconnectedMark() {
        val cases = Cases.section("ink_preservation_cases")
        assertEquals(3, cases.size)
        assertEquals(
            setOf("thin_stroke_and_dot", "three_thin_signatures", "disconnected_signature_marks"),
            cases.map { it.getValue("id").jsonPrimitive.content }.toSet(),
        )
        for (case in cases) {
            val label = case.getValue("id").jsonPrimitive.content
            val marks = points(case)
            val expected = case.getValue("expect_ink").jsonPrimitive.int
            assertTrue("$label must contain ink", expected > 0)
            assertEquals(label, expected, marks.size)
            val minX = marks.minOf { it.first }
            val minY = marks.minOf { it.second }
            val width = marks.maxOf { it.first } - minX + 1
            val height = marks.maxOf { it.second } - minY + 1
            val padding = roundHalfUp(0.08 * maxOf(width, height))
            val result = InkCleanup.clean(source(case), InkVariant.SIGNATURE)
            assertEquals("$label width includes every mark", width + 2 * padding, result.raster.width)
            assertEquals("$label height includes every mark", height + 2 * padding, result.raster.height)
            val dark = darkPoints(result.raster)
            assertEquals("$label exact dark count", expected, dark.size)
            assertEquals(label, marks.map { (column, row) -> column - minX + padding to row - minY + padding }.toSet(), dark)
            assertEquals(label, expected.toDouble() / (result.raster.width * result.raster.height), result.coverage, 0.0)
            assertEquals(label, InkQuality.OK, result.quality)
        }
    }

    @Test
    fun upscEseTripleSignatureSurvivesActualFitAndFinalBytesPassTheSlot() {
        val case = Cases.section("ink_preservation_cases").first { it.getValue("id").jsonPrimitive.content == "three_thin_signatures" }
        val examFile = File(Cases.spec, "presets/exams").walkTopDown().first { it.name == "upsc_ese.json" }
        val slot = PresetBundle.decodeExam(examFile.readText()).documents.first { it.type == DocType.TRIPLE_SIGNATURE }
        val outcome = FitPipeline(AndroidJpegEncoder).run(source(case), slot, Pipeline.SIGNATURE_CLEANUP)
        assertTrue("actual native fit must succeed: $outcome", outcome is PipelineOutcome.Success)
        val result = (outcome as PipelineOutcome.Success).result
        assertEquals(531, darkPoints(checkNotNull(result.ink).raster).size)
        val file = Inspector.inspect(result.fit.bytes)
        val verdict = MatchEngine.evaluate(slot, FileFacts.of(file, DocKind.SIGNATURE)).verdict
        assertTrue("final slot verdict: $verdict", verdict == Verdict.EXACT || verdict == Verdict.ACCEPTED)
        assertEquals("SOF0", file.sof)
        assertEquals("RGB", file.color.name)
        assertEquals(JfifDensity(1, slot.dpi ?: 200, slot.dpi ?: 200), file.jfif)
        assertTrue(!file.hasExif && !file.hasGps && !file.hasXmp)
        val decoded = checkNotNull(AndroidImageDecoder.decode(result.fit.bytes, 4000))
        val preparedMarks = darkPoints(result.prepared)
        val finalMarks = darkPoints(decoded)
        assertTrue("encoded output must contain ink", finalMarks.isNotEmpty())
        val rows = preparedMarks.map { it.second }.distinct().sorted()
        assertEquals("three lines and three disconnected dot rows", 6, rows.size)
        for (row in rows) {
            val center = (row + 0.5) * decoded.height / result.prepared.height
            val radius = maxOf(2.0, decoded.height.toDouble() / result.prepared.height)
            assertTrue("ink group at prepared row $row survives encoding", finalMarks.any { kotlin.math.abs(it.second + 0.5 - center) <= radius })
        }
    }
}
