plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(project(":sdk:runtime-sdk"))
    implementation(project(":sdk:host-sdk"))
    implementation(project(":sdk:provider-sdk"))
    implementation(project(":runtime:provider-core"))
    api(project(":libraries:halo-cognition-language"))
    api("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}
