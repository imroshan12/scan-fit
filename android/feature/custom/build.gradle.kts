plugins {
    id("scanfit.android.feature")
}

dependencies {
    implementation(project(":core:data"))
    testImplementation(project(":core:testing"))
}
