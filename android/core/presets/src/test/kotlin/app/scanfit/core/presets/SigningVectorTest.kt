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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs spec/fixtures/signing/vector.json, the cross-platform Ed25519 test vector (ROADMAP Phase 0).
 * The valid case must verify; every other case must be rejected for the stated reason.
 */
class SigningVectorTest {
    private val dir = File(checkNotNull(System.getProperty("scanfit.spec.dir")), "fixtures/signing")
    private val vector: JsonObject = Json.parseToJsonElement(File(dir, "vector.json").readText()).jsonObject

    private fun bytes(name: String) = File(dir, name).readBytes()

    private fun text(name: String) = File(dir, name).readText()

    private fun key(name: String) = checkNotNull(PresetPublicKey.fromBase64(text(name))) { "$name is not a valid key" }

    private fun cases(name: String): JsonArray = vector.getValue(name).jsonArray

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    @Test
    fun vectorIsUsableAndMatchesThisBuild() {
        assertEquals(
            PresetBundle.SUPPORTED_SCHEMA_VERSION,
            vector.getValue("supported_schema_version").jsonPrimitive.int,
        )
        assertTrue(cases("verify_cases").size >= 8)
        assertTrue(cases("load_cases").size >= 8)
        val verdicts =
            cases("verify_cases")
                .map {
                    it.jsonObject
                        .getValue("valid")
                        .jsonPrimitive.content
                }.toSet()
        assertEquals(setOf("true", "false"), verdicts)
        val expectedReasons = cases("load_cases").map { it.jsonObject.str("expect") }.toSet() - "ok"
        val declared =
            vector
                .getValue("supported_failure_reasons")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()
        assertEquals(declared, expectedReasons)
        assertEquals(declared, PresetLoadError.entries.map { it.reason }.toSet())
    }

    @Test
    fun rawVerificationMatchesTheVector() {
        for (case in cases("verify_cases").map { it.jsonObject }) {
            val id = case.str("id")
            val ok =
                PresetVerifier.isValid(
                    bytes(case.str("bundle")),
                    text(case.str("signature")),
                    key(case.str("public_key")),
                )
            assertEquals(
                id,
                case
                    .getValue("valid")
                    .jsonPrimitive.content
                    .toBooleanStrict(),
                ok,
            )
        }
    }

    @Test
    fun fullLoaderOrderMatchesTheVector() {
        for (case in cases("load_cases").map { it.jsonObject }) {
            val id = case.str("id")
            val result =
                PresetBundleLoader.load(
                    bundle = bytes(case.str("bundle")),
                    signatureText = text(case.str("signature")),
                    publicKey = key(case.str("public_key")),
                    currentVersion = case.getValue("current_version").jsonPrimitive.int,
                )
            val actual =
                when (result) {
                    is PresetLoadResult.Success -> "ok"
                    is PresetLoadResult.Failure -> result.error.reason
                }
            assertEquals(id, case.str("expect"), actual)
        }
    }

    @Test
    fun aSingleFlippedBitIsRejected() {
        val flipped = bytes("sample_presets.json").also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        val result = PresetBundleLoader.load(flipped, text("sample_presets.json.sig"), key("dev_public_key.b64"), 0)
        assertEquals(PresetLoadResult.Failure(PresetLoadError.BAD_SIGNATURE), result)
    }

    @Test
    fun keysThatAreNot32Base64BytesAreRefused() {
        assertNull(PresetPublicKey.fromBase64(""))
        assertNull(PresetPublicKey.fromBase64("not base64!"))
        assertNull(
            PresetPublicKey.fromBase64(
                java.util.Base64
                    .getEncoder()
                    .encodeToString(ByteArray(31) { 1 }),
            ),
        )
        assertNotNull(PresetPublicKey.fromBase64(text("dev_public_key.b64")))
    }
}
