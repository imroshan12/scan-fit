plugins {
    id("scanfit.android.library")
    id("scanfit.android.compose")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:match"))
    implementation(libs.androidx.compose.material.icons.core)
    testImplementation(libs.kotlinx.serialization.json)
}
