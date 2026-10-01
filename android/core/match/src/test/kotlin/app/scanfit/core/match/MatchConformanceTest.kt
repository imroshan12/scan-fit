package app.scanfit.core.match

import app.scanfit.core.inspect.ColorKind
import app.scanfit.core.inspect.DetectedFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs every `match_cases` entry of spec/fixtures/cases.json against the real presets. */
class MatchConformanceTest {
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    private fun JsonObject.list(k: String): List<String>? = (this[k] as? JsonArray)?.map { it.jsonPrimitive.content }

    private fun facts(file: JsonObject): FileFacts {
        val format =
            when (file.str("format")) {
                "jpeg" -> DetectedFormat.JPEG
                "png" -> DetectedFormat.PNG
                "pdf" -> DetectedFormat.PDF
                else -> error("format ${file.str("format")}")
            }
        val kind =
            when (file.str("doc")) {
                "photo" -> DocKind.PHOTO
                "signature" -> DocKind.SIGNATURE
                "thumb" -> DocKind.THUMB
                "declaration" -> DocKind.DECLARATION
                "fingers" -> DocKind.FINGERS
                "pdf_document" -> DocKind.PDF_DOCUMENT
                else -> error("doc ${file.str("doc")}")
            }
        val color =
            when (file["color"]?.jsonPrimitive?.content ?: "rgb") {
                "gray" -> ColorKind.GRAY
                "cmyk" -> ColorKind.CMYK
                else -> ColorKind.RGB
            }
        return FileFacts(
            format = format,
            kb = file.getValue("kb").jsonPrimitive.double,
            width = file["width"]?.jsonPrimitive?.int,
            height = file["height"]?.jsonPrimitive?.int,
            color = color,
            progressive = file["progressive"]?.jsonPrimitive?.boolean ?: false,
            docKind = kind,
        )
    }

    @Test
    fun everyMatchCaseHolds() {
        val cases = Cases.section("match_cases")
        assertTrue("expected the shared cases", cases.size >= 16)
        for (c in cases) {
            val id = c.str("id")
            val opts = c["options"] as? JsonObject
            val options =
                MatchOptions(
                    allowGrayscale = opts?.get("allow_grayscale")?.jsonPrimitive?.boolean ?: false,
                    popularity = opts?.list("popularity") ?: emptyList(),
                )
            val result = MatchEngine.match(facts(c.getValue("file").jsonObject), Cases.exams, options)
            val byExam = result.entries.groupBy { it.examId }

            fun verdicts(exam: String) = byExam[exam].orEmpty().map { it.verdict }

            c.list("expect_exact_includes")?.forEach {
                assertTrue(
                    "$id: $it should be EXACT, was ${verdicts(it)}",
                    Verdict.EXACT in verdicts(it),
                )
            }
            c.list("expect_exact_excludes")?.forEach {
                assertTrue(
                    "$id: $it must not be EXACT",
                    Verdict.EXACT !in verdicts(it),
                )
            }
            c.list("expect_accepted_includes")?.forEach {
                assertTrue(
                    "$id: $it should be ACCEPTED, was ${verdicts(it)}",
                    Verdict.ACCEPTED in verdicts(it),
                )
            }
            c.list("expect_not_includes")?.forEach {
                assertTrue(
                    "$id: $it must not be listed, was ${verdicts(it)}",
                    it !in byExam,
                )
            }
            c.list("expect_unverified_includes")?.forEach {
                assertTrue(
                    "$id: $it should be listed as unverified",
                    byExam[it].orEmpty().any { e ->
                        e.unverified
                    },
                )
            }
            c.list("expect_headline_excludes")?.forEach {
                assertTrue(
                    "$id: $it must not count in the headline",
                    it !in result.acceptedExamIds,
                )
            }
            (c["expect_near_miss"] as? JsonArray)?.forEach { nm ->
                val o = nm.jsonObject
                val entry = byExam[o.str("exam")].orEmpty().firstOrNull { it.verdict == Verdict.NEAR_MISS }
                assertTrue("$id: ${o.str("exam")} should be a near miss", entry != null)
                assertEquals("$id: fix", o.str("fix"), entry?.fix?.wire)
                assertEquals("$id: param", o.str("param"), entry?.failed?.single()?.wire)
            }
            c.list("expect_first_exact")?.let { expected ->
                val exact =
                    result.entries
                        .filter { it.verdict == Verdict.EXACT && !it.unverified }
                        .map { it.examId }
                        .distinct()
                assertEquals("$id: first exact", expected, exact.take(expected.size))
            }
        }
    }

    @Test
    fun theFourIbpsFamilyExamsMatchTheirOwnPhotoSlot() {
        val photo = FileFacts(DetectedFormat.JPEG, 35.0, 200, 230, ColorKind.RGB, false, DocKind.PHOTO)
        val result = MatchEngine.match(photo, Cases.exams)
        assertTrue(result.acceptedExamCount >= 10)
        assertTrue(
            "the headline never counts unverified exams",
            result.acceptedExamIds.none { id ->
                result.entries
                    .filter {
                        it.examId ==
                            id
                    }.all { it.unverified }
            },
        )
    }
}
