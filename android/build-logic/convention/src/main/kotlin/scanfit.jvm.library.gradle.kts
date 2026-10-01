import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

// Pure Kotlin/JVM module: no Android imports (core:model, core:match, core:inspect, ARCHITECTURE section 2).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("jacoco")
}

// Explicit (not the generated `java {}` accessor): accessor generation can be poisoned by a stale build cache.
extensions.configure<JavaPluginExtension> {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

configureKotlin()
configureTests()

// Engine coverage gate (CLAUDE.md rule 10): android/config/coverage/check_coverage.py reads this XML.
tasks.withType<JacocoReport>().configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
tasks.named("test") { finalizedBy("jacocoTestReport") }

dependencies {
    "testImplementation"(libs.lib("junit"))
}
