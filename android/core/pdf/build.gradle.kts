plugins {
    id("scanfit.android.library")
}

android {
    testOptions {
        unitTests {
            // Robolectric native graphics: real Skia JPEG encode/decode for the engine tests.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":core:imaging"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
