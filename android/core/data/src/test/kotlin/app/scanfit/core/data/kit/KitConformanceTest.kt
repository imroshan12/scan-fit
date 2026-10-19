package app.scanfit.core.data.kit

import app.scanfit.core.imaging.KitOriginalImage
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DocType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs `kit_size_cases`, `kit_history_cases` and `kit_id_cases` of spec/fixtures/cases.json (ALGORITHMS 13). */
class KitConformanceTest {
    private val cases by lazy {
        val spec = File(checkNotNull(System.getProperty("scanfit.spec.dir")))
        Json.parseToJsonElement(File(spec, "fixtures/cases.json").readText()).jsonObject
    }

    private fun section(key: String): List<JsonObject> = cases.getValue(key).jsonArray.map { it.jsonObject }.also { assertTrue(key, it.size >= 5) }

    private val JsonObject.id get() = getValue("id").jsonPrimitive.content

    @Test
    fun everySizeCaseHolds() {
        for (c in section("kit_size_cases")) {
            val expect = c.getValue("expect").jsonObject
            val size = KitOriginalImage.storedSize(c.getValue("width").jsonPrimitive.int, c.getValue("height").jsonPrimitive.int)
            assertEquals(c.id, expect.getValue("width").jsonPrimitive.int to expect.getValue("height").jsonPrimitive.int, size)
        }
    }

    @Test
    fun everyHistoryCaseHolds() {
        for (c in section("kit_history_cases")) {
            val entries = c.getValue("entries").jsonArray.map {
                val e = it.jsonObject
                ExportRecord(
                    e.id, e.getValue("created_at_ms").jsonPrimitive.long, "ibps_po", "IBPS PO", DocType.PHOTO,
                    DocKind.PHOTO, "photo.jpg", 1, 1, 1,
                )
            }
            val pruned = ExportHistory.prune(
                entries,
                c.getValue("now_ms").jsonPrimitive.long,
                c.getValue("max_entries").jsonPrimitive.int,
            )
            val expect = c.getValue("expect").jsonObject
            fun ids(key: String) = expect.getValue(key).jsonArray.map { it.jsonPrimitive.content }
            assertEquals(c.id, ids("kept"), pruned.map { it.record.id })
            assertEquals(c.id, ids("shareable"), pruned.filter { it.shareable }.map { it.record.id })
        }
    }

    @Test
    fun everyIdCaseHolds() {
        for (c in section("kit_id_cases")) {
            assertEquals(c.id, c.getValue("expect").jsonPrimitive.boolean, KitIds.isValid(c.id))
        }
    }

    @Test
    fun newIdsAreValid() {
        repeat(100) { assertTrue(KitIds.isValid(KitIds.newId())) }
    }
}
