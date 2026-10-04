plugins {
    id("scanfit.android.library")
}

dependencies {
    api(project(":core:imaging"))
    implementation(libs.kotlinx.coroutines.android)
    // Bundled models: on-device, no Play services download, nothing leaves the phone (CLAUDE.md rule 2).
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.segmentation.selfie)
}
