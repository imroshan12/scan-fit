package app.scanfit.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs every `custom_size_cases` entry of spec/fixtures/cases.json (ALGORITHMS 11). */
class CustomSizeConformanceTest {
    private val spec = File(checkNotNull(System.getProperty("scanfit.spec.dir")))
    private val cases =
        Json.parseToJsonElement(File(spec, "fixtures/cases.json").readText()).jsonObject
            .getValue("custom_size_cases").jsonArray.map { it.jsonObject }

    private fun JsonObject.number(key: String) = this[key]?.takeIf { it != JsonNull }?.jsonPrimitive

    private fun fields(f: JsonObject) = CustomSizeFields(
        doc = DocType.entries.first { it.wire == f.getValue("doc").jsonPrimitive.content },
        unit = if (f["unit"]?.jsonPrimitive?.content == "cm") SizeUnit.CM else SizeUnit.PX,
        width = f.number("width")?.doubleOrNull,
        height = f.number("height")?.doubleOrNull,
        minKb = f.number("min_kb")?.intOrNull,
        maxKb = f.number("max_kb")?.intOrNull,
        dpi = f.number("dpi")?.intOrNull,
    )

    @Test
    fun everyCustomSizeCaseHolds() {
        assertTrue("expected the shared cases", cases.size >= 15)
        for (c in cases) {
            val id = c.getValue("id").jsonPrimitive.content
            val expect = c.getValue("expect").jsonObject
            val parse = c["parse"]?.jsonPrimitive?.content
            if (parse != null) {
                assertEquals(id, expect.getValue("text").jsonPrimitive.contentOrNull, CustomSize.parse(parse)?.text)
                continue
            }
            val input = fields(c.getValue("fields").jsonObject)
            val errors = expect["errors"]?.jsonArray?.map { it.jsonPrimitive.content }
            if (errors != null) {
                assertEquals(id, errors, input.errors().map { it.wire })
                assertNull(id, input.toSize())
                continue
            }
            val size = checkNotNull(input.toSize()) { "$id should be valid: ${input.errors()}" }
            assertEquals(id, expect.getValue("text").jsonPrimitive.content, size.text)
            assertEquals("$id round-trips", size, CustomSize.parse(size.text))
            val slot = expect.getValue("slot").jsonObject
            val docSpec = size.spec
            assertEquals(id, slot.getValue("type").jsonPrimitive.content, docSpec.type.wire)
            assertEquals(id, slot.getValue("mode").jsonPrimitive.content, docSpec.dimensions.mode.name.lowercase())
            assertEquals(id, slot.number("width")?.int, docSpec.dimensions.width)
            assertEquals(id, slot.number("height")?.int, docSpec.dimensions.height)
            assertEquals(id, slot.number("min_kb")?.int?.toDouble(), docSpec.sizeKb.min)
            assertEquals(id, slot.getValue("max_kb").jsonPrimitive.int.toDouble(), docSpec.sizeKb.max)
            assertEquals(id, slot.getValue("dpi").jsonPrimitive.int, docSpec.dpi)
            assertEquals(id, listOf(FileFormat.JPG), docSpec.formats)
        }
    }

    @Test
    fun recentSizesKeepTheLastFiveDistinctNewestFirst() {
        var recent = emptyList<String>()
        listOf("a", "b", "c", "a", "d", "e", "f").forEach { recent = CustomSize.remember(recent, it) }
        assertEquals(listOf("f", "e", "d", "a", "c"), recent)
    }
}
