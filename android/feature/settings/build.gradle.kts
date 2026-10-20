plugins {
    id("scanfit.android.feature")
}

dependencies {
    implementation(project(":core:presets"))
    implementation(project(":core:data"))
    implementation(project(":core:config"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    testImplementation(project(":core:testing"))
}
