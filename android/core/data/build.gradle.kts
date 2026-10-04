plugins {
    id("scanfit.android.library")
    id("scanfit.android.hilt")
}

dependencies {
    api(libs.androidx.datastore.preferences)
    api(libs.kotlinx.coroutines.android)
    // Export (ALGORITHMS 1.6): naming, re-inspection and the slot verdict of the written file.
    api(project(":core:match"))
    implementation(project(":core:imaging"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.serialization.json)
    // Test-only: presets and fakes. :core:testing depends on this module's main code, not on its tests (no cycle).
    testImplementation(project(":core:testing"))
}
