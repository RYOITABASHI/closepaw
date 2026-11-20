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
        // SPIKE (spike/shizuku-start, never merges): libadb-android + its spake2-android dep are
        // JitPack-only. Scoped so nothing else can resolve from JitPack.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.MuntashirAkon(\\..*)?") }
        }
    }
}

rootProject.name = "ClosePaw"
include(":app")
