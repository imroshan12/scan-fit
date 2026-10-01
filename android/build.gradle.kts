import dev.detekt.gradle.Detekt

// Plugins are declared here (apply false) so the convention plugins in build-logic can apply them by id.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
}

// Files written by spec/tools/*.py. They are checked by `gen_*.py --check` in CI instead of by the linters.
val generatedKotlin = listOf("**/ScanFitTheme.kt", "**/AnalyticsEvent.kt")

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", *generatedKotlin.toTypedArray())
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("config/detekt/detekt.yml"))
    source.setFrom(
        fileTree(projectDir) {
            include("**/src/**/*.kt")
            exclude("**/build/**", *generatedKotlin.toTypedArray())
        },
    )
}

tasks.withType<Detekt>().configureEach {
    reports {
        html.required.set(false)
        checkstyle.required.set(false)
        markdown.required.set(false)
    }
}
