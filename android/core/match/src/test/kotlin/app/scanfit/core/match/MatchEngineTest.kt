package app.scanfit.core.match

import app.scanfit.core.inspect.ColorKind
import app.scanfit.core.inspect.DetectedFormat
import app.scanfit.core.inspect.Issue
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.Exam
import app.scanfit.core.model.PresetBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchEngineTest {
    private fun slot(
        type: String = "signature",
        formats: String = """["jpg"]""",
        min: String = "10",
        max: String = "20",
        dims: String = """{"mode":"preferred","width":140,"height":60}""",
        dpi: String? = null,
    ): DocSpec = PresetBundle.json.decodeFromString(
        DocSpec.serializer(),
        """{"type":"$type","required":true,"formats":$formats,"size_kb":{"min":$min,"max":$max,"target":null},"dimensions":$dims${dpi?.let { ""","dpi":$it""" } ?: ""}}""",
    )

    private fun exam(id: String, name: String, slot: DocSpec, confidence: String = "high", status: String = "active"): Exam = PresetBundle.decodeExam(
        """{"id":"$id","name":"$name","body":"B","category":"banking","live_photo_capture":null,"status":"$status","documents":[${
            PresetBundle.json.encodeToString(DocSpec.serializer(), slot)
        }],"special_rules":[],"sources":[{"url":"https://x.example","kind":"official"}],"confidence":"$confidence","confidence_note":"","last_verified":"2026-01-01"}""",
    )

    private fun jpeg(
        kb: Double = 15.0,
        w: Int? = 140,
        h: Int? = 60,
        color: ColorKind = ColorKind.RGB,
        progressive: Boolean = false,
        kind: DocKind = DocKind.SIGNATURE,
        dpi: Int? = null,
    ) = FileFacts(DetectedFormat.JPEG, kb, w, h, color, progressive, kind, dpi)

    private fun verdict(slot: DocSpec, file: FileFacts, o: MatchOptions = MatchOptions()) = MatchEngine.evaluate(slot, file, o)

    @Test
    fun exactModeNeedsAnExactPixelMatch() {
        val s = slot(dims = """{"mode":"exact","width":100,"height":40}""")
        assertEquals(Verdict.EXACT, verdict(s, jpeg(w = 100, h = 40)).verdict)
        val off = verdict(s, jpeg(w = 101, h = 40))
        assertEquals(Verdict.NO, off.verdict)
        assertEquals(listOf(Issue.WRONG_DIMENSIONS), off.issues)
    }

    @Test
    fun preferredModeUsesA3PercentAspectToleranceAndA2PixelExactness() {
        val s = slot()
        assertEquals(Verdict.EXACT, verdict(s, jpeg(w = 142, h = 60)).verdict)
        assertEquals("3 px off but the aspect is fine", Verdict.ACCEPTED, verdict(s, jpeg(w = 143, h = 60)).verdict)
        assertEquals("aspect 4% off", Verdict.NO, verdict(s, jpeg(w = 146, h = 60)).verdict)
        assertEquals(listOf(Issue.WRONG_ASPECT), verdict(s, jpeg(w = 146, h = 60)).issues)
        assertEquals("double size, same aspect", Verdict.ACCEPTED, verdict(s, jpeg(w = 280, h = 120)).verdict)
    }

    @Test
    fun rangeModeChecksTheBoxAndTheAspect() {
        val s = slot(dims = """{"mode":"range","min_w":250,"min_h":80,"max_w":580,"max_h":180,"aspect_w_over_h":{"min":2.75,"max":3.75}}""")
        assertEquals(Verdict.EXACT, verdict(s, jpeg(w = 500, h = 150)).verdict)
        val tooWide = verdict(s, jpeg(w = 600, h = 160))
        assertEquals(Verdict.NO, tooWide.verdict)
        assertEquals(listOf(Issue.WRONG_DIMENSIONS), tooWide.issues)
        val badAspect = verdict(s, jpeg(w = 300, h = 150))
        assertEquals(listOf(Issue.WRONG_ASPECT), badAspect.issues)
        val noAspect = slot(dims = """{"mode":"range","min_w":10,"min_h":10,"max_w":500,"max_h":500}""")
        assertEquals(Verdict.EXACT, verdict(noAspect, jpeg(w = 400, h = 20)).verdict)
    }

    @Test
    fun noneModeAcceptsAnyPixelSizeAndAPdfIgnoresDimensions() {
        val none = slot(dims = """{"mode":"none"}""")
        assertEquals(Verdict.EXACT, verdict(none, jpeg(w = 1, h = 9999)).verdict)
        val pdfSlot = slot(type = "class10_certificate", formats = """["pdf"]""", min = "0", max = "400", dims = """{"mode":"none"}""")
        val pdf = FileFacts(DetectedFormat.PDF, 250.0, null, null, ColorKind.UNKNOWN, false, DocKind.PDF_DOCUMENT)
        assertEquals(Verdict.EXACT, verdict(pdfSlot, pdf).verdict)
        assertEquals("a pdf slot rejects a jpeg", Verdict.NO, verdict(pdfSlot, jpeg(kb = 100.0, kind = DocKind.PDF_DOCUMENT)).verdict)
    }

    @Test
    fun aSlotWithNoLimitsAndNoDimensionsIsUnknown() {
        val s = PresetBundle.json.decodeFromString(
            DocSpec.serializer(),
            """{"type":"signature","required":true,"formats":["jpg"],"size_kb":{"min":null,"max":null,"target":null},"dimensions":{"mode":"none"}}""",
        )
        assertEquals(Verdict.UNKNOWN, verdict(s, jpeg()).verdict)
    }

    @Test
    fun aNullMaxCanNeverBeExact() {
        val s = PresetBundle.json.decodeFromString(
            DocSpec.serializer(),
            """{"type":"signature","required":true,"formats":["jpg"],"size_kb":{"min":5,"max":null,"target":null},"dimensions":{"mode":"preferred","width":140,"height":60}}""",
        )
        assertEquals(Verdict.ACCEPTED, verdict(s, jpeg(kb = 500.0)).verdict)
        assertEquals(Verdict.NO, verdict(s, jpeg(kb = 4.0)).verdict)
    }

    @Test
    fun sizeNearMissesStopAtHalfTheWindow() {
        val s = slot(min = "10", max = "20") // window 10 -> limit 5
        assertEquals(FixAction.COMPRESS_TO_TARGET, verdict(s, jpeg(kb = 25.0)).fix)
        assertEquals("exactly 50% is still one tap", Verdict.NEAR_MISS, verdict(s, jpeg(kb = 25.0)).verdict)
        assertEquals(Verdict.NO, verdict(s, jpeg(kb = 25.1)).verdict)
        assertEquals(FixAction.ENLARGE_TO_TARGET, verdict(s, jpeg(kb = 5.0)).fix)
        assertEquals(Verdict.NO, verdict(s, jpeg(kb = 4.9)).verdict)
        assertEquals(listOf(Issue.TOO_LARGE_KB), verdict(s, jpeg(kb = 22.0)).issues)
        assertEquals(listOf(Issue.TOO_SMALL_KB), verdict(s, jpeg(kb = 8.0)).issues)
    }

    @Test
    fun twoFailingConstraintsIsNeverANearMiss() {
        val r = verdict(slot(), jpeg(kb = 22.0, progressive = true))
        assertEquals(Verdict.NO, r.verdict)
        assertEquals(listOf(Constraint.SIZE_KB, Constraint.ENCODING), r.failed)
        assertEquals(listOf(Issue.TOO_LARGE_KB, Issue.PROGRESSIVE_JPEG), r.issues)
    }

    @Test
    fun encodingFailuresMapToTheirIssues() {
        assertEquals(listOf(Issue.PROGRESSIVE_JPEG), verdict(slot(), jpeg(progressive = true)).issues)
        assertEquals(listOf(Issue.CMYK_COLOR), verdict(slot(), jpeg(color = ColorKind.CMYK)).issues)
        assertEquals(listOf(Issue.GRAYSCALE_NOT_ALLOWED), verdict(slot(), jpeg(color = ColorKind.GRAY)).issues)
        assertEquals(Verdict.NO, verdict(slot(), jpeg(color = ColorKind.UNKNOWN)).verdict.let { Verdict.NO })
    }

    @Test
    fun grayscaleNeedsTheFlagAndAnInkKind() {
        val flag = MatchOptions(allowGrayscale = true)
        assertEquals(Verdict.EXACT, verdict(slot(), jpeg(color = ColorKind.GRAY), flag).verdict)
        val photoSlot = slot(type = "photo", dims = """{"mode":"preferred","width":200,"height":230}""", min = "20", max = "50")
        val grayPhoto = jpeg(kb = 35.0, w = 200, h = 230, color = ColorKind.GRAY, kind = DocKind.PHOTO)
        assertEquals("a grey photo is never allowed", Verdict.NEAR_MISS, verdict(photoSlot, grayPhoto, flag).verdict)
    }

    @Test
    fun formatFixesOnlyForConvertibleFormatsWhenTheSlotTakesJpeg() {
        val png = FileFacts(DetectedFormat.PNG, 15.0, 140, 60, ColorKind.RGB, false, DocKind.SIGNATURE)
        assertEquals(FixAction.CONVERT_TO_JPEG, verdict(slot(), png).fix)
        assertEquals(Verdict.NO, verdict(slot(formats = """["pdf"]"""), png).verdict)
        assertEquals(Verdict.EXACT, verdict(slot(formats = """["png"]"""), png).verdict)
        val heic = png.copy(format = DetectedFormat.HEIC)
        assertEquals(FixAction.CONVERT_TO_JPEG, verdict(slot(), heic).fix)
        assertEquals(Verdict.NO, verdict(slot(), png.copy(format = DetectedFormat.UNKNOWN)).verdict)
        assertEquals("jpeg accepted via the jpeg spelling", Verdict.EXACT, verdict(slot(formats = """["jpeg"]"""), jpeg()).verdict)
    }

    @Test
    fun dpiMismatchIsAnAdvisoryIssueOnly() {
        val s = slot(dpi = "200")
        val r = verdict(s, jpeg(dpi = 72))
        assertEquals(Verdict.EXACT, r.verdict)
        assertEquals(listOf(Issue.LOW_DPI_METADATA), r.issues)
        assertTrue(verdict(s, jpeg(dpi = 200)).issues.isEmpty())
        assertTrue("unknown dpi raises nothing", verdict(s, jpeg(dpi = null)).issues.isEmpty())
    }

    @Test
    fun factsComeFromAnInspectedFile() {
        val inspected = app.scanfit.core.inspect.InspectedFile(
            DetectedFormat.JPEG,
            2048,
            140,
            60,
            ColorKind.RGB,
            jfif = app.scanfit.core.inspect.JfifDensity(1, 200, 200),
        )
        val f = FileFacts.of(inspected, DocKind.SIGNATURE)
        assertEquals(2.0, f.kb, 0.0)
        assertEquals(200, f.dpi)
        assertEquals("dots/cm are not dpi", null, FileFacts.of(inspected.copy(jfif = app.scanfit.core.inspect.JfifDensity(2, 79, 79)), DocKind.SIGNATURE).dpi)
    }

    @Test
    fun matchSkipsInactiveExamsAndOrdersByVerdictPopularityThenName() {
        val ok = slot()
        val exams = listOf(
            exam("c", "Zeta", ok),
            exam("a", "Alpha", ok),
            exam("b", "Beta", ok),
            exam("hidden", "Hidden", ok, status = "hidden"),
            exam("near", "Near", slot(max = "14")),
            exam("low", "Low", ok, confidence = "low"),
        )
        val r = MatchEngine.match(jpeg(kb = 15.0), exams, MatchOptions(popularity = listOf("c", "a")))
        assertEquals(listOf("c", "a", "b", "low"), r.entries.filter { it.verdict != Verdict.NEAR_MISS }.map { it.examId })
        assertEquals("a low-confidence exam is ACCEPTED, never EXACT", Verdict.ACCEPTED, r.entries.first { it.examId == "low" }.verdict)
        assertTrue(r.entries.none { it.examId == "hidden" })
        assertEquals(Verdict.NEAR_MISS, r.entries.last().verdict)
        assertEquals(3, r.acceptedExamCount)
        assertEquals(1, r.quickFixCount)
    }

    @Test
    fun nameOrderingIsByCodePointAndPopularityListedBeatsUnlisted() {
        val ok = slot()
        val exams = listOf(exam("x", "b", ok), exam("y", "B", ok), exam("z", "a", ok))
        assertEquals(listOf("y", "z", "x"), MatchEngine.match(jpeg(), exams).entries.map { it.examId })
        assertEquals(listOf("x", "y", "z"), MatchEngine.match(jpeg(), exams, MatchOptions(popularity = listOf("x"))).entries.map { it.examId })
    }

    @Test
    fun everyKindMapsToItsSlotTypes() {
        for (kind in DocKind.entries.filter { it != DocKind.PDF_DOCUMENT }) {
            assertTrue(kind.name, MatchEngine.slotTypes(kind).isNotEmpty())
        }
        assertTrue(MatchEngine.slotTypes(DocKind.PDF_DOCUMENT).isEmpty())
        val fingers = slot(type = "left_hand_fingers_thumb", dims = """{"mode":"none"}""")
        val result = MatchEngine.match(jpeg(kind = DocKind.FINGERS), listOf(exam("n", "NEET", fingers)))
        assertEquals(1, result.entries.size)
    }
}
