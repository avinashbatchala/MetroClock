pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven { setUrl("https://www.jitpack.io") }
        mavenLocal()
    }
}
include(":app")

// MetroSuite shared builds (composite build; each stays an independent Gradle build).
// When this app is built outside MetroSuite these directories are absent, so guard them.
listOf("../../design", "../../shared/live-tile-contract").forEach { path ->
    if (file(path).exists()) {
        includeBuild(path)
    }
}
