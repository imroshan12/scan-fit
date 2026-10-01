plugins {
    id("scanfit.jvm.library")
}

dependencies {
    api(project(":core:model"))
    testImplementation(libs.kotlinx.serialization.json)
}
