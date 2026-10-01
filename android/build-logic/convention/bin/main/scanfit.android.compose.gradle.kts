import com.android.build.api.dsl.CommonExtension

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

extensions.configure<CommonExtension>("android") {
    buildFeatures.compose = true
}

dependencies {
    val bom = platform(libs.lib("androidx-compose-bom"))
    "implementation"(bom)
    "implementation"(libs.lib("androidx-compose-ui"))
    "implementation"(libs.lib("androidx-compose-ui-graphics"))
    "implementation"(libs.lib("androidx-compose-foundation"))
    "implementation"(libs.lib("androidx-compose-material3"))
    "implementation"(libs.lib("androidx-compose-ui-tooling-preview"))
    "debugImplementation"(libs.lib("androidx-compose-ui-tooling"))
}
