package app.scanfit.core.testing

import app.scanfit.core.model.PresetBundle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** The real presets, read from spec/ sources (exams, categories.json, popular.json) for view-model tests. */
object SpecPresets {
    val bundle: PresetBundle by lazy {
        val spec = File(checkNotNull(System.getProperty("scanfit.spec.dir")) { "scanfit.spec.dir not set" })
        val exams =
            File(spec, "presets/exams")
                .walkTopDown()
                .filter { it.isFile && it.extension == "json" }
                .sortedBy { it.path }
                .map { PresetBundle.decodeExam(it.readText()) }
                .toList()
        val categories =
            Json
                .parseToJsonElement(File(spec, "presets/categories.json").readText())
                .jsonObject
                .filterKeys { !it.startsWith("_") }
                .mapValues { (_, v) ->
                    val aliases = v.jsonObject.getValue("aliases").jsonArray
                    PresetBundle.CategoryInfo(aliases.map { it.jsonPrimitive.content })
                }
        val popular =
            Json
                .parseToJsonElement(File(spec, "presets/popular.json").readText())
                .jsonObject
                .getValue("popular")
                .jsonArray
                .map { it.jsonPrimitive.content }
        PresetBundle(2, 2, "2026-10-01", "", exams, categories, popular)
    }
}
