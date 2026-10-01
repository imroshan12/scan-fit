package app.scanfit.core.model

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Proves the Kotlin model matches the JSON schema: every preset file in spec/ decodes on its own. */
class PresetDecodingTest {
    private val specDir = File(checkNotNull(System.getProperty("scanfit.spec.dir")))

    private fun presetFiles() =
        File(specDir, "presets/exams")
            .walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.path }
            .toList()

    private fun exam(path: String) = PresetBundle.decodeExam(File(specDir, path).readText())

    @Test
    fun everyPresetDecodesAndItsIdEqualsItsFileName() {
        val files = presetFiles()
        assertTrue("expected at least 55 presets, found ${files.size}", files.size >= 55)
        for (file in files) {
            val exam = PresetBundle.decodeExam(file.readText())
            assertEquals(file.nameWithoutExtension, exam.id)
            assertTrue("${exam.id} has documents", exam.documents.isNotEmpty())
            assertTrue("${exam.id} has sources", exam.sources.isNotEmpty())
        }
    }

    @Test
    fun ibpsPoMatchesTheValuesInThePresetFile() {
        val exam = exam("presets/exams/banking/ibps_po.json")
        assertEquals("IBPS PO / MT", exam.name)
        assertEquals(ExamCategory.BANKING, exam.category)
        assertEquals(true, exam.livePhotoCapture)
        assertEquals(Confidence.HIGH, exam.confidence)
        val photo = exam.documents.first { it.type == DocType.PHOTO }
        assertEquals(SizeKb(min = 20.0, max = 50.0, target = 38.0), photo.sizeKb)
        assertEquals(DimensionMode.PREFERRED, photo.dimensions.mode)
        assertEquals(200, photo.dimensions.width)
        assertEquals(230, photo.dimensions.height)
        val declaration = exam.documents.first { it.type == DocType.HANDWRITTEN_DECLARATION }
        assertNull("not transcribed yet: the UI must say 'copy from notice'", declaration.declarationText)
    }

    @Test
    fun rangeDimensionsDecode() {
        val photo = exam("presets/exams/entrance/gate.json").documents.first { it.type == DocType.PHOTO }
        assertEquals(DimensionMode.RANGE, photo.dimensions.mode)
        assertNotNull(photo.dimensions.minW)
        assertNotNull(photo.dimensions.maxH)
    }

    @Test
    fun lowConfidenceIsFlaggedUnverified() {
        val exams = presetFiles().map { PresetBundle.decodeExam(it.readText()) }
        val low = exams.filter { it.confidence == Confidence.LOW }
        assertTrue(low.isNotEmpty())
        assertTrue(low.all { it.isUnverified })
        assertFalse(exams.filter { it.confidence != Confidence.LOW }.any { it.isUnverified })
    }

    @Test
    fun anUnknownDocumentTypeFailsInsteadOfBeingSilentlyDropped() {
        val json = """{"type":"hologram","required":true,"formats":["jpg"],
            "size_kb":{"min":1,"max":2,"target":null},"dimensions":{"mode":"none"}}"""
        try {
            PresetBundle.json.decodeFromString(DocSpec.serializer(), json)
            fail("an unknown enum value must not decode")
        } catch (_: SerializationException) {
            // expected: Swift's Codable fails the same way
        }
    }

    @Test
    fun unknownKeysAreIgnoredLikeOnIos() {
        val json = """{"schema_version":2,"presets_version":5,"future_field":{"a":1}}"""
        assertEquals(PresetBundle.Header(2, 5), PresetBundle.decodeHeader(json))
    }
}
