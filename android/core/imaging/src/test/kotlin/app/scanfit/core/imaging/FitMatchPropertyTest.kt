package app.scanfit.core.imaging

import app.scanfit.core.inspect.Inspector
import app.scanfit.core.match.DocKind
import app.scanfit.core.match.FileFacts
import app.scanfit.core.match.MatchEngine
import app.scanfit.core.match.Verdict
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.DocType
import app.scanfit.core.model.FileFormat
import app.scanfit.core.model.PresetBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Property (ALGORITHMS 4 / TESTING 1): for any source and any real preset slot, a file produced by the fit pipeline is accepted
 * by that slot as EXACT or ACCEPTED, or the pipeline fails with a typed error. Never a silent miss.
 */
class FitMatchPropertyTest {
    private val pipeline = FitPipeline(ImageIoEncoder)

    private fun allSlots(): List<DocSpec> {
        val exams = File(Cases.spec, "presets/exams").walkTopDown().filter { it.extension == "json" }.map { PresetBundle.decodeExam(it.readText()) }
        val seen = HashSet<String>()
        return exams.flatMap { it.documents }
            .filter { (FileFormat.JPG in it.formats || FileFormat.JPEG in it.formats) && it.sizeKb.max != null }
            // one representative per distinct shape: the property is about the numbers, not the exam name
            .filter { seen.add("${it.type}|${it.sizeKb}|${it.dimensions}|${it.dpi}") }.toList()
    }

    private fun kind(type: DocType) = when (type) {
        DocType.PHOTO, DocType.POSTCARD_PHOTO -> DocKind.PHOTO
        DocType.SIGNATURE, DocType.TRIPLE_SIGNATURE -> DocKind.SIGNATURE
        DocType.LEFT_THUMB, DocType.THUMB_IMPRESSION -> DocKind.THUMB
        DocType.HANDWRITTEN_DECLARATION -> DocKind.DECLARATION
        else -> DocKind.PHOTO
    }

    /** Dark pen strokes on pale paper with a shadow gradient. */
    private fun pen(w: Int, h: Int) = Raster.of(w, h) { x, y ->
        val shade = 238 - x * 60 / w
        if ((x / 4 + y / 6) % 11 == 0 && x in w / 8..w * 7 / 8 && y in h / 4..h * 3 / 4) 0x1A1F55 else (shade shl 16) or (shade shl 8) or shade
    }

    private fun sources(): List<Pair<String, Raster>> = listOf(
        "noisy portrait" to noisyRaster(600, 800, noise = 80, seed = 3),
        "smooth landscape" to noisyRaster(640, 360, noise = 5, seed = 9),
        "tiny" to noisyRaster(90, 120, noise = 40, seed = 5),
        "pen drawing" to pen(500, 220),
    )

    @Test
    fun everyFitResultIsAcceptedByTheSlotItWasMadeFor() {
        val slots = allSlots()
        assertTrue("expected a broad set of distinct slots, got ${slots.size}", slots.size >= 20)
        var successes = 0
        var failures = 0
        for (slot in slots) {
            for ((name, source) in sources()) {
                val label = "${slot.type} ${slot.sizeKb} ${slot.dimensions.mode} <- $name"
                when (val outcome = pipeline.run(source, slot)) {
                    is PipelineOutcome.Failure -> {
                        assertEquals("$label: only TOO_DETAILED is an acceptable failure", FitError.TOO_DETAILED, outcome.error)
                        failures++
                    }

                    is PipelineOutcome.Success -> {
                        successes++
                        val facts = FileFacts.of(Inspector.inspect(outcome.result.fit.bytes), kind(slot.type))
                        val verdict = MatchEngine.evaluate(slot, facts)
                        assertTrue(
                            "$label: the slot must accept its own fit output, got ${verdict.verdict} ${verdict.failed} " +
                                "(${facts.kb} KB ${facts.width}x${facts.height})",
                            verdict.verdict == Verdict.EXACT || verdict.verdict == Verdict.ACCEPTED,
                        )
                    }
                }
            }
        }
        assertTrue("the property must not pass vacuously: $successes ok / $failures failed", successes > failures * 3 && successes >= 60)
    }

    @Test
    fun inkPipelinesAlsoProduceFilesTheirSlotsAccept() {
        val slots = allSlots().filter { it.type == DocType.SIGNATURE || it.type == DocType.LEFT_THUMB || it.type == DocType.HANDWRITTEN_DECLARATION }
        assertTrue(slots.isNotEmpty())
        for (slot in slots) {
            val kind = when (slot.type) {
                DocType.SIGNATURE -> Pipeline.SIGNATURE_CLEANUP
                DocType.LEFT_THUMB -> Pipeline.THUMB_CLEANUP
                else -> Pipeline.DOCUMENT_CLEANUP
            }
            val outcome = pipeline.run(pen(500, 220), slot, kind)
            if (outcome is PipelineOutcome.Success) {
                val verdict = MatchEngine.evaluate(slot, FileFacts.of(Inspector.inspect(outcome.result.fit.bytes), kind(slot.type)))
                assertTrue("${slot.type} ${slot.sizeKb}: ${verdict.verdict} ${verdict.failed}", verdict.verdict == Verdict.EXACT || verdict.verdict == Verdict.ACCEPTED)
            }
        }
    }
}
