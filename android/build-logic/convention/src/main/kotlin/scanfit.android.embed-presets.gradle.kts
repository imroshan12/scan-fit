import com.android.build.api.variant.AndroidComponentsExtension

// Wires the presets snapshot into the assets of every variant of this module.
val specDir = rootProject.layout.projectDirectory.dir("../spec")

extensions.configure<AndroidComponentsExtension<*, *, *>>("androidComponents") {
    onVariants { variant ->
        val embed =
            tasks.register<EmbedPresetsTask>("embed${variant.name.replaceFirstChar { it.uppercase() }}Presets") {
                presets.from(specDir.file("dist/presets.json"))
            }
        variant.sources.assets?.addGeneratedSourceDirectory(embed, EmbedPresetsTask::outputDir)
    }
}
