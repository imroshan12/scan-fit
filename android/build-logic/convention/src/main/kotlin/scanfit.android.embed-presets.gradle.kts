import com.android.build.api.variant.AndroidComponentsExtension

// Wires the signed presets snapshot into the assets of every variant of this module.
val specDir = rootProject.layout.projectDirectory.dir("../spec")

extensions.configure<AndroidComponentsExtension<*, *, *>>("androidComponents") {
    onVariants { variant ->
        val keyName = if (variant.buildType == "release") "prod_public_key.b64" else "dev_public_key.b64"
        val embed =
            tasks.register<EmbedPresetsTask>("embed${variant.name.replaceFirstChar { it.uppercase() }}Presets") {
                presets.from(specDir.file("dist/presets.json"))
                signature.from(specDir.file("dist/presets.json.sig"))
                publicKey.from(specDir.file("signing/$keyName"))
                devPublicKey.from(specDir.file("signing/dev_public_key.b64"))
                releaseBuild.set(variant.buildType == "release")
            }
        variant.sources.assets?.addGeneratedSourceDirectory(embed, EmbedPresetsTask::outputDir)
    }
}
