plugins {
    id("scanfit.android.library")
    id("scanfit.android.compose")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:match"))
    // Rasters for image editors (FreeCropEditor).
    api(project(":core:imaging"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.material.icons.core)
    testImplementation(libs.kotlinx.serialization.json)
}
