pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ScanFit"

// Module graph: ARCHITECTURE section 2. feature:* depend on core:* only, never on each other.
include(":app")
include(
    ":core:designsystem",
    ":core:model",
    ":core:presets",
    ":core:imaging",
    ":core:match",
    ":core:inspect",
    ":core:pdf",
    ":core:vision",
    ":core:data",
    ":core:billing",
    ":core:ads",
    ":core:analytics",
    ":core:config",
    ":core:testing",
)
include(
    ":feature:home",
    ":feature:exams",
    ":feature:flow-photo",
    ":feature:flow-ink",
    ":feature:coach",
    ":feature:checker",
    ":feature:custom",
    ":feature:pdf",
    ":feature:kit",
    ":feature:paywall",
    ":feature:settings",
)
