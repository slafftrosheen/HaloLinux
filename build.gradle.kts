import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
    id("com.gradleup.shadow") version "8.3.9" apply false
}

allprojects {
    group = "com.halo.linux"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

/**
 * Optional SHA-pinned source-of-truth mode.
 * The default standalone build uses the checked-in bootstrap snapshot.
 *
 * Run with -PhaloCanonicalRoot=/absolute/path/to/pinned/Halo to compile
 * portable SDK code directly from canonical Halo instead of the copies.
 */
val canonicalRootArg = providers.gradleProperty("haloCanonicalRoot").orNull
val canonicalModules = mapOf(
    ":libraries:common" to "libraries/common",
    ":libraries:halo-cognition-language" to "libraries/halo-cognition-language",
    ":sdk:host-sdk" to "sdk/host-sdk",
    ":sdk:runtime-sdk" to "sdk/runtime-sdk",
    ":sdk:provider-sdk" to "sdk/provider-sdk",
    ":runtime:events" to "runtime/events",
)
if (canonicalRootArg != null) {
    val canonicalRoot = file(canonicalRootArg).canonicalFile
    require(canonicalRoot.isDirectory && file("$canonicalRoot/settings.gradle.kts").exists()) {
        "haloCanonicalRoot must reference a checked-out Halo repository"
    }
    val expected = file("config/canonical-halo.sha").readText().trim()
    val revision = providers.exec {
        commandLine("git", "-C", canonicalRoot.absolutePath, "rev-parse", "HEAD")
    }.standardOutput.asText.get().trim()
    require(revision == expected) {
        "Canonical Halo revision $revision differs from pinned $expected. " +
            "Use a detached checkout at the pinned commit; never silently mix SDK versions."
    }
    subprojects {
        val relative = canonicalModules[path] ?: return@subprojects
        val sourceDir = canonicalRoot.resolve("$relative/src/main/java")
        require(sourceDir.isDirectory) { "Missing canonical SDK sources: $sourceDir" }
        plugins.withId("org.jetbrains.kotlin.jvm") {
            extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
                sourceSets.named("main") {
                    kotlin.setSrcDirs(listOf(sourceDir))
                }
            }
        }
    }
    logger.lifecycle("HaloLinux canonical SDK source mode: SHA $revision (no vendored SDK main sources compiled)")
}
