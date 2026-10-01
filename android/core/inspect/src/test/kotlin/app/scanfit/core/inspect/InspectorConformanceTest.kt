package app.scanfit.core.inspect

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs every `inspect_cases` entry of spec/fixtures/cases.json (the shared conformance contract). */
class InspectorConformanceTest {
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    @Test
    fun everyInspectCaseMatchesTheSpec() {
        val cases = Cases.section("inspect_cases")
        assertTrue("expected the shared cases to be present", cases.size >= 16)
        for (case in cases) {
            val id = case.str("id")
            val name = case.str("input")
            val e = case.getValue("expect") as JsonObject
            val f = Inspector.inspect(Cases.image(name), name)
            assertEquals("$id: format", e.str("format"), f.format.name.lowercase())
            e["color"]?.let { assertEquals("$id: color", it.jsonPrimitive.content, f.color.name.lowercase()) }
            e["progressive"]?.let { assertEquals("$id: progressive", it.jsonPrimitive.boolean, f.progressive) }
            e["width"]?.let { assertEquals("$id: width", it.jsonPrimitive.int, f.width) }
            e["height"]?.let { assertEquals("$id: height", it.jsonPrimitive.int, f.height) }
            e["exif_orientation"]?.let { assertEquals("$id: orientation", it.jsonPrimitive.int, f.exifOrientation) }
            e["has_exif"]?.let { assertEquals("$id: has_exif", it.jsonPrimitive.boolean, f.hasExif) }
            e["has_gps"]?.let { assertEquals("$id: has_gps", it.jsonPrimitive.boolean, f.hasGps) }
            e["has_icc"]?.let { assertEquals("$id: has_icc", it.jsonPrimitive.boolean, f.hasIcc) }
            val issues = e.getValue("issues").jsonArray.map { it.jsonPrimitive.content }
            assertEquals("$id: issues", issues, f.issues.map { it.name })
        }
    }
}
