plugins {
    id("scanfit.android.feature")
}

dependencies {
    implementation(project(":core:presets"))
    implementation(project(":core:data"))
    testImplementation(project(":core:testing"))
}
