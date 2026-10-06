package app.scanfit.core.match

import app.scanfit.core.inspect.ColorKind
import app.scanfit.core.inspect.DetectedFormat
import app.scanfit.core.model.DocType
import app.scanfit.core.model.SizeKb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs every `match_note_cases` entry of spec/fixtures/cases.json (ALGORITHMS 9.7 "Match note"). */
class MatchNoteConformanceTest {
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    private fun docType(wire: String): DocType = Json.decodeFromJsonElement(DocType.serializer(), JsonPrimitive(wire))

    private fun entry(e: JsonObject): MatchEntry {
        val size = e.getValue("size_kb").jsonObject
        val fix = e["fix"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
        return MatchEntry(
            examId = e.str("exam"),
            examName = e.str("name"),
            body = e.str("body"),
            docType = docType(e.str("doc")),
            verdict = Verdict.valueOf(e.str("verdict").uppercase()),
            unverified = e.getValue("unverified").jsonPrimitive.boolean,
            fix = fix?.let { wire -> FixAction.entries.single { it.wire == wire } },
            failed = emptyList(),
            issues = emptyList(),
            sizeKb = SizeKb(min = size["min"]?.jsonPrimitive?.doubleOrNull, max = size["max"]?.jsonPrimitive?.doubleOrNull),
        )
    }

    @Test
    fun everyMatchNoteCaseHolds() {
        val cases = Cases.section("match_note_cases")
        assertTrue("expected the shared cases", cases.size >= 5)
        for (c in cases) {
            val id = c.str("id")
            val note = MatchNote.of(MatchResult(c.getValue("entries").jsonArray.map { entry(it.jsonObject) }))
            val expect = c.getValue("expect").jsonObject
            assertEquals(id, expect.getValue("accepted").jsonPrimitive.int, note.accepted)
            assertEquals(id, expect.getValue("preview").jsonArray.map { it.jsonPrimitive.content }, note.preview.map { it.id })
            assertEquals(id, expect.getValue("more").jsonPrimitive.int, note.more)
            assertEquals(id, expect.getValue("likely_ok").jsonPrimitive.int, note.likelyOk)
            val fixes =
                expect.getValue("quick_fixes").jsonArray.map {
                    val f = it.jsonObject
                    QuickFix(
                        ExamRef(f.str("exam"), note.quickFixes.first { q -> q.exam.id == f.str("exam") }.exam.name),
                        docType(f.str("doc")),
                        Need(NeedKind.valueOf(f.str("need").uppercase()), f["kb"]?.jsonPrimitive?.int),
                    )
                }
            assertEquals(id, fixes, note.quickFixes)
            val groups =
                expect.getValue("groups").jsonArray.map {
                    it.jsonObject.str("body") to it.jsonObject.getValue("exams").jsonArray.map { e -> e.jsonPrimitive.content }
                }
            assertEquals(id, groups, note.groups.map { g -> g.body to g.entries.map { it.examId } })
        }
    }

    @Test
    fun headlineFallsBackFromAcceptedToLikelyOkToNone() {
        val cases = Cases.section("match_note_cases").associateBy { it.str("id") }

        fun headline(id: String) = MatchNote.of(MatchResult(cases.getValue(id).getValue("entries").jsonArray.map { entry(it.jsonObject) })).headline
        assertEquals(MatchNote.Headline.ACCEPTED, headline("preview_takes_three_and_counts_the_rest"))
        assertEquals(MatchNote.Headline.LIKELY_OK, headline("unverified_exams_are_likely_ok_not_accepted"))
        assertEquals(MatchNote.Headline.NONE, headline("nothing_matches"))
    }

    @Test
    fun aNearMissWithoutAUsableLimitIsNotOfferedAsAQuickFix() {
        val base = entry(Cases.section("match_note_cases").first().getValue("entries").jsonArray.first().jsonObject)
        val noMax = base.copy(verdict = Verdict.NEAR_MISS, fix = FixAction.COMPRESS_TO_TARGET, sizeKb = SizeKb(min = 10.0))
        val noFix = base.copy(examId = "other", verdict = Verdict.NEAR_MISS, fix = null)
        assertTrue(MatchNote.of(MatchResult(listOf(noMax, noFix))).quickFixes.isEmpty())
    }

    @Test
    fun realFitOutputIsAcceptedByItsOwnExam() {
        // An IBPS signature at 140x60 / 16 KB: IBPS PO accepts it and the note names it first among IBPS exams.
        val facts =
            FileFacts(
                format = DetectedFormat.JPEG,
                kb = 16.0,
                width = 140,
                height = 60,
                color = ColorKind.RGB,
                progressive = false,
                docKind = DocKind.SIGNATURE,
            )
        val note = MatchNote.of(MatchEngine.match(facts, Cases.exams))
        assertTrue(note.accepted > 1)
        assertTrue(note.groups.any { g -> g.body == "IBPS" && g.entries.any { it.examId == "ibps_po" } })
    }
}
