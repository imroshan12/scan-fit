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
        applicationId = "com.roshtech.scanfit"
        // versionName/versionCode come from gradle.properties / -P flags (ReleaseConfig.kt in build-logic).
        // Free saves this build ships with (ALGORITHMS 14.2): CI test builds pass -Pscanfit.freeSavesTotal=200; local
        // and final builds keep the default. Parsed at runtime by QuotaConfig.fromBuildValue (bad values mean 20).
        val freeSaves = providers.gradleProperty("scanfit.freeSavesTotal").orElse("20").get()
        buildConfigField("String", "FREE_SAVES_TOTAL", "\"${freeSaves.replace("\\", "").replace("\"", "")}\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation(project(":core:data"))
    implementation(project(":feature:home"))
    implementation(project(":feature:exams"))
    implementation(project(":feature:flow-photo"))
    implementation(project(":feature:flow-ink"))
    implementation(project(":feature:kit"))
    implementation(project(":feature:custom"))
    implementation(project(":feature:checker"))
    implementation(project(":feature:pdf"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:paywall"))
    implementation(project(":core:billing"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
