// A feature module (ARCHITECTURE section 2): Compose UI + Hilt ViewModels. It depends on core:* only.
plugins {
    id("scanfit.android.library")
    id("scanfit.android.compose")
    id("scanfit.android.hilt")
}

dependencies {
    "implementation"(project(":core:designsystem"))
    "implementation"(project(":core:model"))
    "implementation"(libs.lib("androidx-lifecycle-runtime-compose"))
    "implementation"(libs.lib("androidx-lifecycle-viewmodel-compose"))
    "implementation"(libs.lib("androidx-hilt-navigation-compose"))
    "implementation"(libs.lib("androidx-compose-material-icons-core"))
    "implementation"(libs.lib("kotlinx-coroutines-android"))
    "testImplementation"(libs.lib("kotlinx-coroutines-test"))
    "testImplementation"(libs.lib("turbine"))
}
