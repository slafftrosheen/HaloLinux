plugins {
    id("org.jetbrains.kotlin.jvm")
}
dependencies {
    implementation(project(":libraries:common"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
}
