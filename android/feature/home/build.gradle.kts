plugins {
    id("scanfit.android.feature")
}

dependencies {
    implementation(project(":core:presets"))
    testImplementation(project(":core:testing"))
}
