plugins {
    id("scanfit.android.feature")
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:presets"))
    implementation(project(":core:match"))
    // Thumbnails and previews of My Kit originals (ALGORITHMS 13.1), decoded downsampled.
    implementation(project(":core:imaging"))
    // History previews of saved PDFs (ALGORITHMS 13.3): Android's PdfRenderer through the PDF engine's opener.
    implementation(project(":core:pdf"))
    testImplementation(project(":core:testing"))
}
