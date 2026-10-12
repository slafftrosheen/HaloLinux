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
include(":libraries:common", ":libraries:halo-cognition-language")
// JSON schemas in protocol/schemas are resources, not a Gradle project.

// An opt-in portability gate. Never include full canonical cognition in
// standalone Pi builds until its pinned build and compatibility tests pass.
if (providers.gradleProperty("haloCanonicalCognition").orNull == "true") {
    require(providers.gradleProperty("haloCanonicalRoot").isPresent) {
        "haloCanonicalCognition=true requires -PhaloCanonicalRoot=/path/to/pinned/Halo"
    }
    include(":runtime:cognition")
}
