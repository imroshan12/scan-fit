import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

private const val COMPILE_SDK = 37
private const val MIN_SDK = 26

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

/** Project-wide Android settings shared by :app and every library module (ARCHITECTURE section 1). */
internal fun Project.configureAndroid(android: CommonExtension) {
    // compileSdk is what we compile against (current AndroidX releases require 37); targetSdk is the runtime
    // behaviour we opt into. Play requires Android 16 (API 36) targets from 31 Aug 2026, set in :app.
    android.compileSdk = COMPILE_SDK
    android.defaultConfig.minSdk = MIN_SDK
    android.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    android.compileOptions.targetCompatibility = JavaVersion.VERSION_17
    android.lint.warningsAsErrors = true
    android.lint.abortOnError = true
    android.lint.disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    android.testOptions.unitTests.isReturnDefaultValues = true
    configureKotlin()
    configureTests()
}

/** Kotlin settings for Android and plain JVM modules: JVM 17, and warnings fail the build (no new warnings). */
internal fun Project.configureKotlin() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(true)
        }
    }
}

/** Unit tests read the shared contract (presets, fixtures, signing vector) from the repo's spec/ directory. */
internal fun Project.configureTests() {
    val specDir = rootProject.layout.projectDirectory.dir("../spec")
    tasks.withType<Test>().configureEach {
        systemProperty("scanfit.spec.dir", specDir.asFile.absolutePath)
        inputs.dir(specDir).withPropertyName("spec").optional()
    }
}
