plugins {
    id("scanfit.android.library")
}

android {
    testOptions {
        unitTests {
            // Robolectric native graphics gives real Skia JPEG encode/decode on the JVM (spike 5).
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:inspect"))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(project(":core:match"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
