// Pure Kotlin/JVM module: no Android imports (core:model, core:match, core:inspect, ARCHITECTURE section 2).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

configureKotlin()
configureTests()

dependencies {
    "testImplementation"(libs.lib("junit"))
}
