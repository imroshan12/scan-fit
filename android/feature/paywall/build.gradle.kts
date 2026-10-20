plugins {
    id("scanfit.android.feature")
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:config"))
    testImplementation(project(":core:testing"))
}
