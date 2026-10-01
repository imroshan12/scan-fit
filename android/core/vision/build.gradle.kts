plugins {
    id("scanfit.android.library")
    // Pure interfaces and value types for the ML wrappers; the ML Kit implementations land in Phase 2 (with fakes in :core:testing).
}

dependencies {
    api(project(":core:imaging"))
    implementation(libs.kotlinx.coroutines.android)
}
