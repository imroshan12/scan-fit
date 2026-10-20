import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Embeds the presets snapshot (`spec/dist/presets.json`) into the app's assets (ARCHITECTURE section 6). Presets are
 * not signed (user decision 2026-10-20): the app checks the format when it loads them, and `build_presets.py` has
 * already validated every exam. This task only refuses a missing or obviously broken file, so it never ships.
 */
@CacheableTask
abstract class EmbedPresetsTask : DefaultTask() {
    // InputFiles (not InputFile): a missing file must reach the action so we can print a useful message.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val presets: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun embed() {
        val presetsFile = presets.singleFile
        if (!presetsFile.exists()) {
            throw GradleException("spec/dist/presets.json not found. Run spec/tools/build_all.sh first.")
        }
        val bytes = presetsFile.readBytes()
        val text = bytes.decodeToString()
        if (!text.trimStart().startsWith("{") || HEADER.any { it !in text }) {
            throw GradleException("spec/dist/presets.json is not a presets bundle. Re-run spec/tools/build_all.sh.")
        }
        // Start from an empty folder: builds from before 2026-10-20 also wrote a signature and a key here.
        val root = outputDir.get().asFile.apply { deleteRecursively() }
        val out = root.resolve("presets").apply { mkdirs() }
        out.resolve("presets.json").writeBytes(bytes)
    }

    private companion object {
        val HEADER = listOf("\"schema_version\":", "\"presets_version\":", "\"exams\":")
    }
}
