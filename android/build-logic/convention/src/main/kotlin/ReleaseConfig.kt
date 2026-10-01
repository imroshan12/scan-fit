import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.StringReader
import java.util.Properties

// Release signing and versioning for :app (docs/RELEASE_CHECKLIST.md).

private const val SEMVER = """\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?"""
private const val MAX_VERSION_CODE = 2_100_000_000 // Google Play's ceiling

/** keystore.properties key -> the environment variable CI uses for the same value. */
private val UPLOAD_KEY_FIELDS =
    mapOf(
        "storeFile" to "SCANFIT_UPLOAD_STORE_FILE",
        "storePassword" to "SCANFIT_UPLOAD_STORE_PASSWORD",
        "keyAlias" to "SCANFIT_UPLOAD_KEY_ALIAS",
        "keyPassword" to "SCANFIT_UPLOAD_KEY_PASSWORD",
    )

/**
 * Signs the release build type with the Play **upload** key (Play App Signing holds the real app key).
 *
 * The key never lives in the repo: CI passes `SCANFIT_UPLOAD_*` environment variables, a developer keeps the same four
 * values in the git-ignored `android/keystore.properties` (template: `keystore.properties.example`). With neither, the
 * release build type stays unsigned. It can never fall back to the debug key, and `bundleRelease` refuses to run.
 */
internal fun Project.configureReleaseSigning(android: ApplicationExtension) {
    val credentials = uploadKeyCredentials()
    if (credentials != null) {
        val upload =
            android.signingConfigs.create("upload") {
                storeFile = rootProject.file(credentials.getValue("storeFile"))
                storePassword = credentials.getValue("storePassword")
                keyAlias = credentials.getValue("keyAlias")
                keyPassword = credentials.getValue("keyPassword")
            }
        android.buildTypes.getByName("release").signingConfig = upload
    }
    val signed = credentials != null
    tasks.matching { it.name == "bundleRelease" }.configureEach {
        doFirst {
            if (!signed) {
                throw GradleException(
                    "bundleRelease needs the upload key. Copy android/keystore.properties.example to " +
                        "android/keystore.properties and fill it in, or set the SCANFIT_UPLOAD_* environment " +
                        "variables (docs/RELEASE_CHECKLIST.md). Google Play rejects an unsigned bundle.",
                )
            }
        }
    }
}

private fun Project.uploadKeyCredentials(): Map<String, String>? {
    val propertiesFile = rootProject.layout.projectDirectory.file("keystore.properties")
    val fileText = providers.fileContents(propertiesFile).asText.orNull
    val fromFile = Properties().apply { fileText?.let { load(StringReader(it)) } }
    val found =
        UPLOAD_KEY_FIELDS
            .mapNotNull { (property, env) ->
                val value =
                    providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }
                        ?: fromFile.getProperty(property)?.takeIf { it.isNotBlank() }
                value?.let { property to it }
            }.toMap()
    if (found.isEmpty()) return null
    val missing = UPLOAD_KEY_FIELDS.keys - found.keys
    if (missing.isNotEmpty()) {
        throw GradleException(
            "Release signing is partly configured. Missing: ${missing.joinToString()} " +
                "(in keystore.properties or as SCANFIT_UPLOAD_* environment variables).",
        )
    }
    return found
}

/**
 * `scanfit.versionName` (default in gradle.properties) is the shared semver of a release; CI overrides it from the
 * tag with `-Pscanfit.versionName=1.0.0`. `scanfit.versionCode` is the CI run number: Play needs it to rise on
 * every upload.
 */
internal fun Project.configureVersioning(android: ApplicationExtension) {
    val name = providers.gradleProperty("scanfit.versionName").get()
    val code = providers.gradleProperty("scanfit.versionCode").orElse("1").get().toIntOrNull()
    val problem =
        when {
            !Regex(SEMVER).matches(name) -> "scanfit.versionName '$name' is not semver (1.2.3, 1.2.3-rc1)"
            code == null || code !in 1..MAX_VERSION_CODE -> "scanfit.versionCode must be 1..$MAX_VERSION_CODE"
            else -> null
        }
    if (problem != null) throw GradleException(problem)
    android.defaultConfig.versionName = name
    android.defaultConfig.versionCode = checkNotNull(code)
}
