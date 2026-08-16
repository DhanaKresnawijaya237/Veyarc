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

rootProject.name = "VeyarcAndroid"
include(
    ":app",
    ":automation-android",
    ":core-engine",
    ":core-model",
    ":core-recording",
    ":data",
    ":feature-assistant",
    ":feature-editor",
    ":feature-recorder",
    ":feature-runner",
)
