plugins {
    id("scanfit.android.library")
    id("scanfit.android.embed-presets")
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
}
