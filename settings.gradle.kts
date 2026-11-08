// The phone app: a core, the one interface plugins implement, the plugins, and the app.
// Which module may depend on which is ADR-CVM-003's, and `checkLayers` holds the build to it.
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

rootProject.name = "claude-voice-mobile"
include(":core", ":plugin-api", ":app", ":plugins:status")
