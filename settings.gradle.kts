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
        // libadb-android and its spake2-android dependency are hosted on JitPack.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.MuntashirAkon(\\..*)?") }
        }
    }
}

rootProject.name = "ClosePaw"
include(":app")
