import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
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

val verifyCanonicalSources by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verify the Android source revision and every vendored production file"
    workingDir(rootDir)
    commandLine("python3", "-B", "scripts/sync-canonical-sources.py")
    providers.gradleProperty("haloCanonicalRoot").orNull?.let { upstream ->
        args("--halo-repo", upstream)
        if (providers.gradleProperty("haloCanonicalCognition").orNull == "true") {
            args("--include-cognition")
        }
    }
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        dependsOn(verifyCanonicalSources)
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
 * The default standalone build uses the checked-in, hash-verified snapshot.
 *
 * Run with -PhaloCanonicalRoot=/absolute/path/to/pinned/Halo to compile
 * portable SDK code directly from canonical Halo instead of the copies.
 */
val canonicalRootArg = providers.gradleProperty("haloCanonicalRoot").orNull
// Keep source switching and the reproducible snapshot importer on one allowlist.
val snapshotModules = file("config/canonical-modules.txt").readLines()
    .map { it.substringBefore('#').trim() }
    .filter { it.isNotEmpty() }
val canonicalModules = (snapshotModules + "runtime/cognition")
    .associateBy { ":" + it.replace('/', ':') }
if (canonicalRootArg == null) {
    subprojects {
        val relative = canonicalModules[path] ?: return@subprojects
        val sourceDir = rootProject.file("$relative/src/main/java")
        plugins.withId("org.jetbrains.kotlin.jvm") {
            extensions.configure<KotlinJvmProjectExtension>("kotlin") {
                sourceSets.named("main") { kotlin.setSrcDirs(listOf(sourceDir)) }
            }
        }
        plugins.withId("java") {
            extensions.configure<SourceSetContainer>("sourceSets") {
                named("main") { java.setSrcDirs(listOf(sourceDir)) }
            }
        }
    }
}
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
    val sourcePaths = canonicalModules.values.map { "$it/src/main/java" }
    val dirtySources = providers.exec {
        commandLine(listOf("git", "-C", canonicalRoot.absolutePath, "status",
            "--porcelain=v1", "--untracked-files=all", "--") + sourcePaths)
    }.standardOutput.asText.get().trim()
    require(dirtySources.isEmpty()) {
        "Canonical Halo production sources contain local changes. " +
            "Use a clean checkout at the pinned SHA; a matching HEAD alone is insufficient."
    }
    subprojects {
        val relative = canonicalModules[path] ?: return@subprojects
        val sourceDir = canonicalRoot.resolve("$relative/src/main/java")
        require(sourceDir.isDirectory) { "Missing canonical SDK sources: $sourceDir" }
        plugins.withId("org.jetbrains.kotlin.jvm") {
            extensions.configure<KotlinJvmProjectExtension>("kotlin") {
                sourceSets.named("main") {
                    kotlin.setSrcDirs(listOf(sourceDir))
                }
            }
        }
        plugins.withId("java") {
            extensions.configure<SourceSetContainer>("sourceSets") {
                named("main") { java.setSrcDirs(listOf(sourceDir)) }
            }
        }
    }
    logger.lifecycle("HaloLinux canonical SDK source mode: SHA $revision (no vendored SDK main sources compiled)")
}
