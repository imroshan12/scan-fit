plugins {
    id("scanfit.android.feature")
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:presets"))
    implementation(project(":core:imaging"))
    implementation(project(":core:match"))
    implementation(libs.androidx.activity.compose)
    testImplementation(project(":core:testing"))
}
