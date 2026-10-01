import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Embeds the signed presets snapshot into the app's assets (ARCHITECTURE section 6, ROADMAP Phase 0).
 *
 * Before copying, it verifies `presets.json.sig` against the public key being embedded, so a stale or
 * wrongly-signed snapshot fails the build instead of shipping. Debug builds embed the public DEV key,
 * release builds the production key (spec/signing/README.md) and refuse the DEV key: anyone can sign with it.
 */
@CacheableTask
abstract class EmbedPresetsTask : DefaultTask() {
    // InputFiles (not InputFile): a missing file must reach the action so we can print a useful message.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val presets: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val signature: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val publicKey: ConfigurableFileCollection

    /** The public DEV key: a release build must never embed it, even if it was copied into prod_public_key.b64. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val devPublicKey: ConfigurableFileCollection

    @get:Input
    abstract val releaseBuild: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun embed() {
        val presetsFile = presets.singleFile
        val sigFile = signature.singleFile
        val keyFile = publicKey.singleFile
        missingInputMessage(presetsFile, sigFile, keyFile)?.let { throw GradleException(it) }
        val bytes = presetsFile.readBytes()
        val sig = Base64.getDecoder().decode(sigFile.readText().trim())
        val key = Base64.getDecoder().decode(keyFile.readText().trim())
        refusalMessage(keyFile, key, bytes, sig)?.let { throw GradleException(it) }
        val out =
            outputDir
                .get()
                .asFile
                .resolve("presets")
                .apply { mkdirs() }
        out.resolve("presets.json").writeBytes(bytes)
        out.resolve("presets.json.sig").writeText(sigFile.readText().trim())
        out.resolve("presets_public_key.b64").writeText(keyFile.readText().trim())
    }

    /** Why this snapshot must not be embedded, or null when it may be. */
    private fun refusalMessage(
        keyFile: File,
        key: ByteArray,
        presetsBytes: ByteArray,
        sig: ByteArray,
    ): String? {
        val dev = devPublicKey.files.singleOrNull { it.exists() }
        return when {
            releaseBuild.get() && dev != null && dev.readText().trim() == keyFile.readText().trim() -> {
                "${keyFile.name} is the public DEV key (anyone can sign presets with it). Release builds must " +
                    "embed the production key: python3 spec/tools/build_presets.py --genkey (spec/signing/README.md)."
            }

            !isValidEd25519(key, presetsBytes, sig) -> {
                "spec/dist/presets.json does not verify against ${keyFile.name}. Re-run spec/tools/build_all.sh " +
                    "(debug) or sign with the production key (release). Refusing to embed it."
            }

            else -> {
                null
            }
        }
    }

    private fun missingInputMessage(
        presetsFile: File,
        sigFile: File,
        keyFile: File,
    ): String? = when {
        !keyFile.exists() -> {
            "${keyFile.path} is missing. Release builds must embed the production public key " +
                "(see spec/signing/README.md)."
        }

        !presetsFile.exists() || !sigFile.exists() -> {
            "spec/dist/presets.json(.sig) not found. Run spec/tools/build_all.sh first."
        }

        else -> {
            null
        }
    }

    private fun isValidEd25519(
        rawPublicKey: ByteArray,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean {
        require(rawPublicKey.size == ED25519_KEY_BYTES) { "Ed25519 public key must be 32 bytes" }
        // The JDK (15+) verifies Ed25519 on the build machine; wrap the raw key in an X.509 SubjectPublicKeyInfo.
        val spkiPrefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spkiPrefix + rawPublicKey))
        return Signature.getInstance("Ed25519").run {
            initVerify(publicKey)
            update(data)
            runCatching { verify(signature) }.getOrDefault(false)
        }
    }

    private companion object {
        const val ED25519_KEY_BYTES = 32
    }
}
