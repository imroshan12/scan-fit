plugins {
    id("scanfit.android.feature")
}

dependencies {
    implementation(project(":core:presets"))
    implementation(libs.androidx.appcompat)
    testImplementation(project(":core:testing"))
}
