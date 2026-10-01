package app.scanfit.core.inspect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Loads spec/fixtures/cases.json and fixture images for the conformance tests. */
object Cases {
    val spec = File(checkNotNull(System.getProperty("scanfit.spec.dir")) { "scanfit.spec.dir not set" })
    private val root: JsonObject = Json.parseToJsonElement(File(spec, "fixtures/cases.json").readText()).jsonObject

    fun section(name: String): List<JsonObject> = (root.getValue(name) as JsonArray).map { it.jsonObject }

    fun image(name: String): ByteArray = File(spec, "fixtures/images/$name").readBytes()

    @Suppress("unused")
    fun sectionNames() = root.keys.filter { root.getValue(it) is JsonArray }.also { root.getValue(it.first()).jsonArray }
}
