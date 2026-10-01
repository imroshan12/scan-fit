import com.android.build.api.dsl.LibraryExtension

plugins {
    id("com.android.library")
}

extensions.configure<LibraryExtension> {
    // :core:designsystem -> app.scanfit.core.designsystem, :feature:flow-photo -> app.scanfit.feature.flowphoto
    namespace = "app.scanfit" + project.path.replace(":", ".").replace("-", "")
    configureAndroid(this)
}

dependencies {
    "testImplementation"(libs.lib("junit"))
}
