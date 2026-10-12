pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.github.jengelman.gradle.plugins.shadow") version "8.1.1"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content {
                includeGroupByRegex("com\\.github\\..*")
            }
        }
    }
}

rootProject.name = "HaloLinux"

include(":host:linux")
include(":adapters:inference")
include(":adapters:audio")
include(":adapters:storage")
include(":protocol:schemas")
include(":runtime:core")
include(":runtime:events")
include(":runtime:lifecycle")
include(":runtime:observer")
include(":runtime:provider-core")
include(":sdk:runtime-sdk")
include(":sdk:provider-sdk")
include(":sdk:host-sdk")
include(":libraries:common")