package app.scanfit.core.match

import app.scanfit.core.inspect.DetectedFormat
import app.scanfit.core.model.DimensionMode
import app.scanfit.core.model.Dimensions
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.DocType
import app.scanfit.core.model.FileFormat
import app.scanfit.core.model.PresetBundle
import app.scanfit.core.model.SizeKb
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs every `checker_cases` entry (ALGORITHMS 12) and checks the exam-slot list against the real presets. */
class CheckerConformanceTest {
    @Test
    fun everyCheckerCaseHolds() {
        val cases = Cases.section("checker_cases")
        assertTrue(cases.size >= 8)
        for (c in cases) {
            val id = c.getValue("id").jsonPrimitive.content
            val formats = c.getValue("slot_formats").jsonArray.map { FileFormat.valueOf(it.jsonPrimitive.content.uppercase()) }
            val slot = DocSpec(DocType.SIGNATURE, true, formats, SizeKb(max = 20.0), Dimensions(DimensionMode.NONE))
            val status =
                CheckerStatus.of(
                    DetectedFormat.valueOf(c.getValue("format").jsonPrimitive.content.uppercase()),
                    slot,
                    Verdict.valueOf(c.getValue("verdict").jsonPrimitive.content.uppercase()),
                    c.getValue("unverified").jsonPrimitive.boolean,
                    c["encrypted"]?.jsonPrimitive?.boolean ?: false,
                )
            assertEquals(id, c.getValue("expect").jsonPrimitive.content.uppercase(), status.name)
        }
    }

    private val bundle by lazy {
        PresetBundle(
            schemaVersion = 1,
            presetsVersion = 1,
            generatedOn = "2026-10-15",
            disclaimer = "",
            exams = Cases.exams,
            popular = listOf("ibps_po", "sbi_po"),
        )
    }

    @Test
    fun targetsListEverySlotOfTheKindPopularFirst() {
        val signatures = CheckerTargets.of(bundle, DocKind.SIGNATURE)
        assertEquals("ibps_po", signatures.first().exam.id)
        assertEquals("sbi_po", signatures[1].exam.id)
        assertTrue(signatures.all { it.slot.type == DocType.SIGNATURE || it.slot.type == DocType.TRIPLE_SIGNATURE })
        val neet = CheckerTargets.of(bundle, DocKind.FINGERS).filter { it.exam.id == "neet_ug" }.map { it.slot.type }
        assertEquals(
            "an exam with two matching slots appears twice",
            listOf(DocType.LEFT_HAND_FINGERS_THUMB, DocType.RIGHT_HAND_FINGERS_THUMB),
            neet,
        )
        assertTrue("a triple signature is a signature slot", signatures.any { it.exam.id == "upsc_cse" })
        assertTrue("no photo slots", CheckerTargets.of(bundle, DocKind.PHOTO).none { it.exam.id == "ssc_cgl" })
    }

    @Test
    fun aQueryUsesTheExamSearch() {
        val found = CheckerTargets.of(bundle, DocKind.PHOTO, "sbi po")
        assertEquals("sbi_po", found.first().exam.id)
    }
}
