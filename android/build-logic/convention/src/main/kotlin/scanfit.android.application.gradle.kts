import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application")
}

extensions.configure<ApplicationExtension> {
    configureAndroid(this)
    defaultConfig.targetSdk = 36
    configureReleaseSigning(this)
    configureVersioning(this)
}

dependencies {
    "testImplementation"(libs.lib("junit"))
}
