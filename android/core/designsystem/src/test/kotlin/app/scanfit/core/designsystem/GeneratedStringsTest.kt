package app.scanfit.core.designsystem

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the generated strings.xml files against drift from spec/strings (CI also runs `gen_strings.py --check`).
 * Pure JVM: parses the XML, no Android runtime needed.
 */
class GeneratedStringsTest {
    private val spec = File(checkNotNull(System.getProperty("scanfit.spec.dir")))
    private val res = File("src/main/res")

    private fun specKeys(lang: String): Set<String> {
        val obj: JsonObject = Json.parseToJsonElement(File(spec, "strings/$lang.json").readText()).jsonObject
        return obj.keys
            .filterNot { it.startsWith("_") }
            // iOS Info.plist values (permission prompts) go only to the iOS app target (gen_strings.py INFOPLIST_KEYS)
            .filterNot { it.startsWith("infoplist.") }
            .map { it.replace('.', '_') }
            .toSet()
    }

    private fun resourceNames(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val nodes = doc.documentElement.childNodes
        return (0 until nodes.length)
            .map { nodes.item(it) }
            .filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }

    @Test
    fun everySpecKeyHasAnAndroidResourceInEveryLanguage() {
        assertTrue(specKeys("en").size > 100)
        assertEquals(specKeys("en"), resourceNames("values").keys)
        assertEquals(specKeys("hi"), resourceNames("values-hi").keys)
    }

    @Test
    fun theTodoMarkerNeverReachesTheUi() {
        val hindi = resourceNames("values-hi")
        assertFalse(hindi.values.any { it.contains("TODO_HI") })
        assertEquals("होम", hindi.getValue("tab_home"))
    }

    @Test
    fun theLocalesConfigListsExactlyTheLanguagesWeShip() {
        val text = File("../../app/src/main/res/xml/locales_config.xml").readText()
        assertTrue(text.contains("android:name=\"en\"") && text.contains("android:name=\"hi\""))
    }
}
