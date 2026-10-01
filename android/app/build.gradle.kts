plugins {
    id("scanfit.android.application")
    id("scanfit.android.compose")
    id("scanfit.android.hilt")
}

android {
    namespace = "app.scanfit"

    defaultConfig {
        // Placeholder id: it is permanent once published on Play. Confirm it (and the iOS bundle id in
        // ios/project.yml) before the first upload.
        applicationId = "app.scanfit"
        versionCode = 1
        versionName = "0.1.0" // shared semver per release (ARCHITECTURE section 12)
    }

    androidResources {
        localeFilters += listOf("en", "hi") // ship only the languages we translate: smaller APK
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:presets"))
    implementation(project(":feature:home"))
    implementation(project(":feature:kit"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
}
