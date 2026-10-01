/**
 * Precompiled [scanfit.android.embed-presets.gradle.kts][Scanfit_android_embed_presets_gradle] script plugin.
 *
 * @see Scanfit_android_embed_presets_gradle
 */
public
class Scanfit_android_embedPresetsPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Scanfit_android_embed_presets_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
