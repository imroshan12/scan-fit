package app.scanfit.core.match

import app.scanfit.core.model.Exam
import app.scanfit.core.model.PresetBundle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

object Cases {
    val spec = File(checkNotNull(System.getProperty("scanfit.spec.dir")) { "scanfit.spec.dir not set" })
    private val root: JsonObject = Json.parseToJsonElement(File(spec, "fixtures/cases.json").readText()).jsonObject

    fun section(name: String): List<JsonObject> = (root.getValue(name) as JsonArray).map { it.jsonObject }

    /** Every exam preset in spec/presets (the source of truth the bundle is built from). */
    val exams: List<Exam> by lazy {
        File(spec, "presets/exams")
            .walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.path }
            .map { PresetBundle.decodeExam(it.readText()) }
            .toList()
    }
}
