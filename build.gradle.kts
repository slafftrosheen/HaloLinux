allprojects {
    group = "com.halo.linux"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        google()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    dependencies {
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
        testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
        testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
        testImplementation("org.mockito:mockito-core:5.12.0")
        testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    }

    tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile) {
        kotlinOptions {
            jvmTarget = "21"
            freeCompilerArgs.add("-Xjsr305=strict")
            freeCompilerArgs.add("-Xopt-in=kotlin.RequiresOptIn")
            freeCompilerArgs.add("-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
            freeCompilerArgs.add("-Xopt-in=kotlinx.serialization.ExperimentalSerializationApi")
        }
    }

    tasks.withType(Test) {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = "full"
        }
    }
}