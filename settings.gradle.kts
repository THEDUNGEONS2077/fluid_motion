// settings.gradle.kts: the fluid_motion build: one app module.
// Plugins and libraries come from Google's and Maven Central's repositories only.
pluginManagement {
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
rootProject.name = "fluid_motion"
include(":app")
