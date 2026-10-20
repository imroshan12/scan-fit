plugins {
    id("scanfit.android.library")
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)
}
