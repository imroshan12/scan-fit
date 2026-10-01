package app.scanfit.core.imaging

import app.scanfit.core.model.DocType
import app.scanfit.core.model.Exam
import app.scanfit.core.model.PresetBundle
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Export names follow ALGORITHMS 9.8; mirrors `FitEngineTests.naming` on iOS. */
class ExportNamingTest {
    private fun exam(id: String): Exam {
        val file = File(Cases.spec, "presets/exams").walkTopDown().first { it.name == "$id.json" }
        return PresetBundle.decodeExam(file.readText())
    }

    @Test
    fun theShortExamNameDropsQualifiersAndSeparators() {
        assertEquals("IBPS-PO", ExportNaming.examShort("IBPS PO / MT"))
        assertEquals("SSC-CGL", ExportNaming.examShort("SSC CGL (Tier 1)"))
        assertEquals("A-B-C", ExportNaming.examShort("  A&B  C "))
    }

    @Test
    fun aGeneratedNameCarriesTypeExamSizeAndRoundedKb() {
        val ibps = exam("ibps_po")
        val signature = ibps.documents.first { it.type == DocType.SIGNATURE }
        assertEquals("signature_IBPS-PO_140x60_16kb.jpg", ExportNaming.fileName(ibps, signature, 140, 60, 16 * 1024 + 100))
    }

    @Test
    fun aPortalMandatedFileNameWinsOverTheGeneratedOne() {
        val jee = exam("jee_main")
        val photo = jee.documents.first { it.type == DocType.PHOTO }
        assertEquals("Photograph.jpg", ExportNaming.fileName(jee, photo, 1, 1, 1))
    }

    @Test
    fun everyDocTypeIsNamedAfterItsPresetId() {
        val exam = exam("ibps_po")
        val ids = DocType.serializer().descriptor
        for (type in DocType.entries) {
            val slot = Presets.inline(type = ids.getElementName(type.ordinal), dims = """{"mode":"exact","width":10,"height":10}""")
            val name = ExportNaming.fileName(exam, slot, 10, 10, 1024)
            assertEquals("${ids.getElementName(type.ordinal)}_IBPS-PO_10x10_1kb.jpg", name)
        }
    }
}
