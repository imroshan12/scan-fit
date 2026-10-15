package app.scanfit.core.data.export

import app.scanfit.core.model.DocType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs every `save_all_cases` entry of spec/fixtures/cases.json (ALGORITHMS 1.6.2). */
class SaveAllPlanConformanceTest {
    private fun JsonObject.types(key: String): List<DocType> = (getValue(key) as JsonArray).map { e -> DocType.entries.first { it.name.lowercase() == e.jsonPrimitive.content } }

    @Test
    fun everySaveAllCaseHolds() {
        val spec = File(checkNotNull(System.getProperty("scanfit.spec.dir")))
        val cases =
            Json.parseToJsonElement(File(spec, "fixtures/cases.json").readText()).jsonObject
                .getValue("save_all_cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 5)
        for (c in cases) {
            val id = c.getValue("id").jsonPrimitive.content
            val plan =
                SaveAllPlan.of(
                    c.types("documents"),
                    c.types("ready").toSet(),
                    c.types("saved").toSet(),
                    c.getValue("handwriting_confirmed").jsonPrimitive.boolean,
                )
            val expect = c.getValue("expect").jsonObject
            assertEquals(id, expect.types("included"), plan.included)
            assertEquals(id, expect.types("held"), plan.held)
        }
    }
}
