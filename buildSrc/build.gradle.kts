plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Lets the Kotlin/test conventions apply the Kotlin Gradle plugin.
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)
    implementation(libs.sonarlint.gradle.plugin)
    // Exposes the generated `libs` catalog accessors to the precompiled
    // convention plugins under src/main/kotlin.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
