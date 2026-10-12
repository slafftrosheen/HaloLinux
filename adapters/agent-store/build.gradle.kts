plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(project(":runtime:cognition"))
    implementation(project(":sdk:runtime-sdk"))
    implementation(project(":libraries:halo-cognition-language"))
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}
