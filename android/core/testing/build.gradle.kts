plugins {
    id("scanfit.android.library")
}

dependencies {
    api(project(":core:presets"))
    api(libs.kotlinx.coroutines.android)
}
