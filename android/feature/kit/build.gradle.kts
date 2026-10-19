plugins {
    id("scanfit.android.feature")
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:presets"))
    implementation(project(":core:match"))
    // Thumbnails and previews of My Kit originals (ALGORITHMS 13.1), decoded downsampled.
    implementation(project(":core:imaging"))
    testImplementation(project(":core:testing"))
}
