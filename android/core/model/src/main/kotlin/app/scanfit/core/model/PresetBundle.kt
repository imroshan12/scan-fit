package app.scanfit.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/** The signed bundle published as `presets.json` (see spec/tools/build_presets.py). */
@Serializable
data class PresetBundle(
    val schemaVersion: Int,
    val presetsVersion: Int,
    val generatedOn: String,
    val disclaimer: String,
    val exams: List<Exam>,
    /**
     * Search words per category, keyed by the category's JSON name (`banking`, `state_psc`), ALGORITHMS §10.
     * String keys, so a bundle that names a category this build does not know still decodes.
     */
    val categories: Map<String, CategoryInfo> = emptyMap(),
    /** Exam ids, most popular first (Home "Popular now", match-note and search ordering). */
    val popular: List<String> = emptyList(),
) {
    @Serializable
    data class CategoryInfo(
        val aliases: List<String> = emptyList(),
    )

    /** The two header fields every loader reads first, before decoding exams. */
    @Serializable
    data class Header(
        val schemaVersion: Int,
        val presetsVersion: Int,
    )

    companion object {
        /** The newest `schema_version` this build understands. A larger one needs an app update. */
        const val SUPPORTED_SCHEMA_VERSION = 2

        /**
         * snake_case JSON <-> camelCase Kotlin. Unknown keys are ignored (the Swift decoder does the same), so a
         * new optional field in the same schema version can never break one platform and not the other. Unknown
         * *enum values* still fail on both, which is correct: that needs a schema bump.
         */
        @OptIn(ExperimentalSerializationApi::class)
        val json: Json =
            Json {
                ignoreUnknownKeys = true
                namingStrategy = JsonNamingStrategy.SnakeCase
            }

        fun decodeHeader(text: String): Header = json.decodeFromString(Header.serializer(), text)

        fun decode(text: String): PresetBundle = json.decodeFromString(serializer(), text)

        /** Decodes a single preset file (`spec/presets/exams/<category>/<id>.json`). */
        fun decodeExam(text: String): Exam = json.decodeFromString(Exam.serializer(), text)
    }
}
