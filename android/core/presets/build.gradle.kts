plugins {
    id("scanfit.android.library")
    id("scanfit.android.embed-presets")
}

dependencies {
    api(project(":core:model"))
    implementation(libs.tink.android)
    implementation(libs.kotlinx.coroutines.android)
}
