package app.scanfit.core.data.quota

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs every `save_quota_cases` script of spec/fixtures/cases.json (ALGORITHMS 14.2). */
class SaveQuotaConformanceTest {
    private val cases by lazy {
        val spec = File(checkNotNull(System.getProperty("scanfit.spec.dir")))
        Json.parseToJsonElement(File(spec, "fixtures/cases.json").readText()).jsonObject
            .getValue("save_quota_cases").jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int

    private fun JsonObject.pro() = get("pro")?.jsonPrimitive?.booleanOrNull ?: false

    @Test
    fun everyCaseHolds() {
        assertTrue(cases.size >= 8)
        for (c in cases) {
            val id = c.getValue("id").jsonPrimitive.content
            val start = c["start"]?.jsonObject
            val maxHashes = c["max_hashes"]?.jsonPrimitive?.int ?: SaveQuotaRules.MAX_HASHES
            var state = QuotaState(
                used = start?.get("used")?.jsonPrimitive?.int ?: 0,
                allowance = start?.get("allowance")?.jsonPrimitive?.int ?: 0,
                hashes = start?.get("hashes")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            )
            c.getValue("steps").jsonArray.forEachIndexed { index, element ->
                val step = element.jsonObject
                val where = "$id step $index"
                when (val op = step.getValue("op").jsonPrimitive.content) {
                    "total" -> state = SaveQuotaRules.total(state, step.int("total"))

                    "record" -> state = SaveQuotaRules.record(
                        state,
                        step.getValue("file").jsonPrimitive.content,
                        step.pro(),
                        maxHashes,
                    )

                    "merge" -> state = SaveQuotaRules.merge(state, step.int("remote_used"))

                    "decide" -> {
                        val files = step.getValue("files").jsonArray.map { it.jsonPrimitive.content }
                        val decision = SaveQuotaRules.decide(state, files, step.pro())
                        val expect = step.expect()
                        assertEquals(where, expect.getValue("allowed").jsonPrimitive.boolean, decision.allowed)
                        assertEquals(where, expect.int("new"), decision.newFiles)
                    }

                    "state" -> {
                        val expect = step.expect()
                        assertEquals(where, expect.int("used"), state.used)
                        assertEquals(where, expect.int("allowance"), state.allowance)
                        assertEquals(where, expect.int("remaining"), state.remaining)
                        assertEquals(
                            where,
                            expect.getValue("shows_left").jsonPrimitive.boolean,
                            SaveQuotaRules.showsLeft(state, step.pro()),
                        )
                    }

                    else -> error("$where: unknown op $op")
                }
            }
        }
    }

    private fun JsonObject.expect(): JsonObject = getValue("expect").jsonObject
}
