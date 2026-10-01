package app.scanfit.core.imaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.scanfit.core.inspect.Inspector
import app.scanfit.core.model.PresetBundle
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Phase 1 exit gate: "Fit of the photo fixture <= 800 ms on a mid device" (`max_ms_midrange` of `ibps_photo_from_phone`).
 *
 * This runs on the build machine under Robolectric, which is **much faster than a mid-range phone**: passing here is a regression
 * guard (the budget is read from cases.json), not proof for a device. The device number comes from a Macrobenchmark in Phase 5.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FitBenchmarkTest {
    @Test
    fun theIbpsPhotoFixtureFitsWithinTheMidRangeBudgetOnTheBuildMachine() {
        val case = Cases.section("fit_cases").first { it.getValue("id").jsonPrimitive.content == "ibps_photo_from_phone" }
        val budgetMs = case.getValue("expect").jsonObject.getValue("max_ms_midrange").jsonPrimitive.int
        val bytes = Cases.image(case.getValue("input").jsonPrimitive.content)
        val original = Inspector.inspect(bytes)
        val exam = File(Cases.spec, "presets/exams").walkTopDown().first { it.name == "ibps_po.json" }
        val spec = PresetBundle.decodeExam(exam.readText()).documents.first { it.type.name == "PHOTO" }
        val crop = case.getValue("crop").jsonObject
        val pipeline = FitPipeline(AndroidJpegEncoder)

        fun once(): Long {
            val start = System.nanoTime()
            val decoded = checkNotNull(AndroidImageDecoder.decode(bytes, AndroidImageDecoder.longSideCap(230)))
            val rect = CropRect(crop.getValue("x").jsonPrimitive.int, crop.getValue("y").jsonPrimitive.int, crop.getValue("w").jsonPrimitive.int, crop.getValue("h").jsonPrimitive.int)
                .scaledTo(checkNotNull(original.width), checkNotNull(original.height), decoded)
            check(pipeline.run(decoded, spec, Pipeline.PLAIN, rect) is PipelineOutcome.Success)
            return (System.nanoTime() - start) / NANOS_PER_MS
        }

        repeat(WARMUPS) { once() } // JIT and Robolectric sandbox warm-up
        val times = List(RUNS) { once() }.sorted()
        val median = times[RUNS / 2]
        println("BENCH ibps_photo_from_phone median=${median}ms runs=$times budget=${budgetMs}ms (build machine, not a device)")
        assertTrue("median $median ms exceeds the $budgetMs ms mid-device budget even on the build machine: $times", median <= budgetMs)
    }

    private companion object {
        const val WARMUPS = 2
        const val RUNS = 5
        const val NANOS_PER_MS = 1_000_000L
    }
}
