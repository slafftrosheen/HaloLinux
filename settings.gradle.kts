pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
rootProject.name = "HaloLinux"
include(":host:linux")
include(":adapters:inference", ":adapters:audio", ":adapters:storage")
include(":runtime:core", ":runtime:events", ":runtime:lifecycle", ":runtime:observer", ":runtime:provider-core")
include(":sdk:runtime-sdk", ":sdk:provider-sdk", ":sdk:host-sdk")
include(":libraries:common")
// JSON schemas in protocol/schemas are resources, not a Gradle project.
