package app.scanfit.core.data.export

import app.scanfit.core.match.DocKind
import app.scanfit.core.testing.FakeExportDestinations
import app.scanfit.core.testing.FakeUserPreferences
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class ExportConformanceTest(
    private val id: String,
    private val case: JsonObject,
) {
    @Test
    fun sharedExportLifecycleHolds() = runTest {
        val operation = case.getValue("operation").jsonPrimitive.content
        val destination = FakeExportDestinations(operation, requiresPicker = operation == "cancel")
        val prefs = FakeUserPreferences()
        val exam = SpecPresets.bundle.exams.first { it.id == "ibps_po" }
        val spec = exam.documents.first()
        val normal = TestJpeg.make(200, 230, 35 * 1024)
        val bytes = when (operation) {
            "wrong_dpi" -> normal.copyOf().apply { this[15] = 72 }

            "exif" -> normal.take(2).toByteArray() + byteArrayOf(
                0xff.toByte(), 0xe1.toByte(), 0, 8, 69, 120, 105, 102, 0, 0,
            ) + normal.drop(2).toByteArray()

            "success", "cancel", "write_failed", "read_failed", "corrupt", "publish_failed" -> normal

            else -> error("$id: unknown export operation $operation")
        }
        val result = DocumentExporter(destination, prefs).export(ExportRequest(exam.id, exam.name, spec, DocKind.PHOTO, bytes))
        val expect = case.getValue("expect").jsonObject
        assertEquals(id, expect.getValue("state").jsonPrimitive.content, result.state.name.lowercase())
        assertEquals(id, expect.getValue("delete").jsonPrimitive.boolean, "delete" in destination.events)
        assertEquals(id, expect.getValue("record").jsonPrimitive.boolean, prefs.savedDocuments.first().isNotEmpty())
        if (operation == "success") assertEquals(listOf("create", "write", "read", "publish"), destination.events)
        if (operation == "cancel") assertTrue(destination.events.isEmpty())
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val spec = File(checkNotNull(System.getProperty("scanfit.spec.dir")))
            val cases = Json.parseToJsonElement(File(spec, "fixtures/cases.json").readText())
                .jsonObject.getValue("export_cases").jsonArray
            require(cases.size >= 8)
            return cases.map { element ->
                val case = element.jsonObject
                arrayOf(case.getValue("id").jsonPrimitive.content, case)
            }
        }
    }
}
