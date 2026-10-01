plugins {
    `kotlin-dsl`
}

group = "app.scanfit.buildlogic"

// An IDE that imports this project with its own (older) Gradle compiles build-logic too. Per-version output directories keep two
// Gradles from overwriting each other's classes and generated accessors (which breaks plugin loading with confusing errors).
layout.buildDirectory.set(layout.projectDirectory.dir("build/gradle-${gradle.gradleVersion}"))

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.kotlin.composeGradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
}
