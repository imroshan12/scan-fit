package app.scanfit.core.data.draft

import app.scanfit.core.match.DocKind
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.Base64

@RunWith(Parameterized::class)
class DraftConformanceTest(private val id: String, private val case: JsonObject) {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun sharedRetainedDraftContract() = runTest {
        val root = folder.newFolder().canonicalFile
        val now = 1_800_000_000_000L
        val spec = SpecPresets.bundle.exams.first { it.id == "ibps_po" }.documents.first()
        val operation = case.getValue("operation").jsonPrimitive.content
        val bytes = TestJpeg.make(200, 230, 35 * 1024)
        var currentSpec = spec
        if (operation != "missing") {
            assertTrue(FileDraftStore(root, { now }).retain("ibps_po", spec, DocKind.PHOTO, bytes))
            val file = root.listFiles().orEmpty().single()
            val payload = when (operation) {
                "wrong_dpi" -> bytes.copyOf().apply { this[15] = 72 }

                "exif" -> bytes.take(2).toByteArray() + byteArrayOf(
                    0xff.toByte(), 0xe1.toByte(), 0, 8, 69, 120, 105, 102, 0, 0,
                ) + bytes.drop(2).toByteArray()

                else -> bytes
            }
            val created = when (operation) {
                "expired" -> now - FileDraftStore.TTL_MILLIS
                "future" -> now + 1
                else -> now
            }
            when (operation) {
                "corrupt" -> file.writeText("{invalid")

                "changed_spec" -> currentSpec = spec.copy(dpi = 300)

                "valid" -> Unit

                "expired", "future", "version", "wrong_dpi", "exif" -> file.writeText(
                    buildJsonObject {
                        put("version", if (operation == "version") 2 else 1)
                        put("created_at_ms", created)
                        put("jpeg", Base64.getEncoder().encodeToString(payload))
                    }.toString(),
                )

                else -> error("$id: unsupported operation $operation")
            }
        }
        val restored = FileDraftStore(root, { now }).read("ibps_po", currentSpec, DocKind.PHOTO)
        val expected = case.getValue("expect").jsonObject
        assertEquals(id, expected.getValue("available").jsonPrimitive.boolean, restored != null)
        val status = draftStatus(case.getValue("saved").jsonPrimitive.boolean, restored != null)
        assertEquals(id, expected.getValue("status").jsonPrimitive.content, status.name.lowercase())
        if (restored != null) assertArrayEquals(bytes, restored.bytes)
        if (restored == null) assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val spec = File(checkNotNull(System.getProperty("scanfit.spec.dir")))
            val cases = Json.parseToJsonElement(File(spec, "fixtures/cases.json").readText())
                .jsonObject.getValue("draft_cases").jsonArray
            require(cases.size == 11 && cases.map { it.jsonObject.getValue("id") }.toSet().size == 11)
            return cases.map { element ->
                val case = element.jsonObject
                arrayOf(case.getValue("id").jsonPrimitive.content, case)
            }
        }
    }
}
