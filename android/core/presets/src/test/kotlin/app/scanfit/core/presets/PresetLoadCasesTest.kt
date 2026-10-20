package app.scanfit.core.presets

import app.scanfit.core.model.PresetBundle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs spec/fixtures/presets_load/cases.json: the format checks every presets file goes through on both apps.
 * Each case must load or be refused for the stated reason.
 */
class PresetLoadCasesTest {
    private val dir = File(checkNotNull(System.getProperty("scanfit.spec.dir")), "fixtures/presets_load")
    private val cases: JsonObject = Json.parseToJsonElement(File(dir, "cases.json").readText()).jsonObject

    private fun bytes(name: String) = File(dir, name).readBytes()

    private fun loadCases(): JsonArray = cases.getValue("load_cases").jsonArray

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    @Test
    fun casesAreUsableAndMatchThisBuild() {
        assertEquals(
            PresetBundle.SUPPORTED_SCHEMA_VERSION,
            cases.getValue("supported_schema_version").jsonPrimitive.int,
        )
        assertTrue(loadCases().size >= 8)
        val expectedReasons = loadCases().map { it.jsonObject.str("expect") }.toSet() - "ok"
        val declared =
            cases
                .getValue("supported_failure_reasons")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()
        assertEquals(declared, expectedReasons)
        assertEquals(declared, PresetLoadError.entries.map { it.reason }.toSet())
    }

    @Test
    fun theFormatChecksMatchTheCases() {
        for (case in loadCases().map { it.jsonObject }) {
            val result =
                PresetBundleLoader.load(
                    bundle = bytes(case.str("bundle")),
                    currentVersion = case.getValue("current_version").jsonPrimitive.int,
                )
            val actual =
                when (result) {
                    is PresetLoadResult.Success -> "ok"
                    is PresetLoadResult.Failure -> result.error.reason
                }
            assertEquals(case.str("id"), case.str("expect"), actual)
        }
    }

    @Test
    fun aBundleCutShortIsRefused() {
        val good = bytes("sample_presets.json")
        val result = PresetBundleLoader.load(good.copyOf(good.size / 2), 0)
        assertEquals(PresetLoadResult.Failure(PresetLoadError.PARSE), result)
    }
}
