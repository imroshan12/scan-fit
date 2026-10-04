package app.scanfit.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** ALGORITHMS §10 conformance: every `search_*` case in spec/fixtures/cases.json, against the source presets. */
class ExamSearchTest {
    private val spec = File(checkNotNull(System.getProperty("scanfit.spec.dir")))
    private val cases = Json.parseToJsonElement(File(spec, "fixtures/cases.json").readText()).jsonObject

    /** The bundle's search inputs straight from the spec sources (spec/dist may be stale or release-signed). */
    private val bundle: PresetBundle by lazy {
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
                .mapValues { (_, v) -> PresetBundle.CategoryInfo(v.jsonObject.getValue("aliases").jsonArray.map { it.jsonPrimitive.content }) }
        val popular =
            Json
                .parseToJsonElement(File(spec, "presets/popular.json").readText())
                .jsonObject
                .getValue("popular")
                .jsonArray
                .map { it.jsonPrimitive.content }
        PresetBundle(2, 0, "", "", exams, categories, popular)
    }

    private fun section(name: String) = (cases.getValue(name) as JsonArray).map { it.jsonObject }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

    private fun category(key: String) = ExamCategory.entries.single { ExamSearch.categoryKey(it) == key }

    @Test
    fun everySearchBrowseAndPopularCaseMatchesTheReference() {
        val search = ExamSearch(bundle)
        val all = section("search_cases")
        assertTrue(all.size >= 20)
        for (case in all) {
            val id = case.str("id")
            val unverified = case["show_unverified"]?.jsonPrimitive?.boolean ?: false
            val got =
                when (case.str("kind")) {
                    "search" -> search.search(case.str("query").orEmpty(), case.str("category")?.let(::category), unverified)
                    "browse" -> search.browse(category(case.str("category").orEmpty()), unverified)
                    "popular" -> search.popular(case.getValue("n").jsonPrimitive.int, unverified)
                    else -> error("$id: unknown kind")
                }.map { it.id }
            val expected = case.getValue("expect_ids").jsonArray.map { it.jsonPrimitive.content }
            assertEquals("$id", expected, got)
        }
    }

    @Test
    fun everyLevelCaseMatches() {
        for (case in section("search_level_cases")) {
            assertEquals(
                "${case.str("id")}",
                case.getValue("level").jsonPrimitive.int,
                ExamSearch.level(case.str("q").orEmpty(), case.str("t").orEmpty()),
            )
        }
    }

    @Test
    fun everyNormalisationCaseMatches() {
        for (case in section("search_norm_cases")) {
            val expected = case.getValue("tokens").jsonArray.map { it.jsonPrimitive.content }
            assertEquals("${case.str("id")}", expected, ExamSearch.norm(case.str("text").orEmpty()))
        }
    }

    @Test
    fun theBundleCarriesTheSearchData() {
        assertEquals(ExamCategory.entries.size, bundle.categories.size)
        assertTrue(bundle.popular.isNotEmpty())
        assertTrue(bundle.exams.all { it.aliases.isNotEmpty() })
    }

    @Test
    fun anOlderBundleWithoutSearchDataStillSearchesByName() {
        val plain = bundle.copy(categories = emptyMap(), popular = emptyList())
        assertEquals("ssc_cgl", ExamSearch(plain).search("ssc cgl").first().id)
        assertTrue(ExamSearch(plain).popular(3).isEmpty())
    }

    @Test
    fun osaCountsAnAdjacentSwapAsOneEdit() {
        assertEquals(1, ExamSearch.osa("ibsp".codePoints().toArray(), "ibps".codePoints().toArray()))
        assertEquals(3, ExamSearch.osa(IntArray(0), "abc".codePoints().toArray()))
    }
}
