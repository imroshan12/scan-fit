plugins {
    id("scanfit.android.feature")
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:presets"))
    implementation(project(":core:match"))
    testImplementation(project(":core:testing"))
}
