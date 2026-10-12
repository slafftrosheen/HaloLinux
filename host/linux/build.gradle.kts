plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.gradleup.shadow")
    application
}

dependencies {
    implementation(project(":libraries:common"))
    implementation(project(":sdk:runtime-sdk"))
    implementation(project(":sdk:provider-sdk"))
    implementation(project(":sdk:host-sdk"))
    implementation(project(":runtime:core"))
    implementation(project(":runtime:events"))
    implementation(project(":runtime:lifecycle"))
    implementation(project(":runtime:provider-core"))
    implementation(project(":adapters:inference"))
    implementation(project(":adapters:audio"))
    implementation(project(":adapters:storage"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("org.yaml:snakeyaml:2.3")
    implementation("org.json:json:20240303")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

application {
    mainClass.set("com.halo.host.linux.HaloNodeApplicationKt")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "com.halo.host.linux.HaloNodeApplicationKt"
    }
}
