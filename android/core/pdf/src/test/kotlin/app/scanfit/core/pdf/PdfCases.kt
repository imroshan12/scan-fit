package app.scanfit.core.pdf

import app.scanfit.core.model.SizeKb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Loads spec/fixtures/cases.json and fixture files for the PDF conformance tests. */
object PdfCases {
    val spec = File(checkNotNull(System.getProperty("scanfit.spec.dir")) { "scanfit.spec.dir not set" })
    private val root: JsonObject = Json.parseToJsonElement(File(spec, "fixtures/cases.json").readText()).jsonObject

    fun section(name: String): List<JsonObject> = (root.getValue(name) as JsonArray).map { it.jsonObject }

    fun file(path: String): ByteArray = File(spec, "fixtures/$path").readBytes()

    fun sizeKb(o: JsonObject): SizeKb = SizeKb(o.number("min"), o.number("max"), o.number("target"))

    private fun JsonObject.number(key: String): Double? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.double

    fun JsonElement?.isNull() = this == null || this is JsonNull
}
