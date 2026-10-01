plugins {
    id("scanfit.jvm.library")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:inspect"))
    testImplementation(libs.kotlinx.serialization.json)
}
