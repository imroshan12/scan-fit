plugins {
    id("scanfit.android.library")
}

dependencies {
    api(project(":core:presets"))
    api(project(":core:vision"))
    api(libs.kotlinx.coroutines.android)
}
