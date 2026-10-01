import com.android.build.api.dsl.LibraryExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    id("com.android.library")
    id("jacoco")
}

extensions.configure<LibraryExtension> {
    // :core:designsystem -> app.scanfit.core.designsystem, :feature:flow-photo -> app.scanfit.feature.flowphoto
    namespace = "app.scanfit" + project.path.replace(":", ".").replace("-", "")
    configureAndroid(this)
    // Engine coverage gate (CLAUDE.md rule 10): `createDebugUnitTestCoverageReport` writes the XML that
    // android/config/coverage/check_coverage.py reads.
    buildTypes.getByName("debug").enableUnitTestCoverage = true
}

// Robolectric loads classes through its own sandbox class loader: JaCoCo must also instrument classes without a source location.
tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

dependencies {
    "testImplementation"(libs.lib("junit"))
}
